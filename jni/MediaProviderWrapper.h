/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specic language governing permissions and
 * limitations under the License.
 */

#ifndef MEDIAPROVIDER_FUSE_MEDIAPROVIDERWRAPPER_H_
#define MEDIAPROVIDER_FUSE_MEDIAPROVIDERWRAPPER_H_

#include <jni.h>
#include <sys/types.h>

#include <atomic>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <queue>
#include <string>
#include <thread>

#include "libfuse_jni/RedactionInfo.h"

namespace mediaprovider {
namespace fuse {

/**
 * Type describing a JNI task, sent to the JNI thread.
 * The function only takes JNIEnv because that's the parameter that JNI thread
 * must provide. The rest of the arguments can be captured by the lambda,
 * the return value should be captured by reference.
 */
typedef std::function<void(JNIEnv*)> JniTask;

/**
 * Class that wraps MediaProvider.java and all of the needed JNI calls to make
 * interaction with MediaProvider easier.
 */
class MediaProviderWrapper final {
  public:
    MediaProviderWrapper(JNIEnv* env, jobject media_provider);
    ~MediaProviderWrapper();

    /**
     * Computes and returns the RedactionInfo for a given FD and UID.
     *
     * @param uid UID of the app requesting the read
     * @param fd FD of the requested file
     * @return RedactionInfo on success, nullptr on failure to calculate
     * redaction ranges (e.g. exception was thrown in Java world)
     */
    std::unique_ptr<RedactionInfo> GetRedactionInfo(uid_t uid, int fd);

    /**
     * Create a new file under the given path for the given UID.
     *
     * @param path the path of the file to be created
     * @param uid UID of the calling app
     * @return opened file descriptor of the newly created file,
     * or negated errno error code if operation fails.
     */
    int CreateFile(const std::string& path, uid_t uid);

    /**
     * Delete the file denoted by the given path on behalf of the given UID.
     *
     * @param path the path of the file to be deleted
     * @param uid UID of the calling app
     * @return 0 upon success,
     * or negated errno error code if operation fails.
     */
    int DeleteFile(const std::string& path, uid_t uid);

  private:
    jclass media_provider_class_;
    jobject media_provider_object_;
    /** Cached MediaProvider method IDs **/
    jmethodID mid_get_redaction_ranges_;
    jmethodID mid_create_file_;
    jmethodID mid_delete_file_;
    /**
     * All JNI calls are delegated to this thread
     */
    std::thread jni_thread_;
    /**
     * jniThread loops until d'tor is called, waiting for a notification on condition_variable to
     * perform a task
     */
    std::condition_variable pending_task_cond_;
    /**
     * Communication with jniThread is done through this JniTasks queue.
     */
    std::queue<JniTask> jni_tasks_;
    /**
     * Threads can post a JNI task if and only if this is true.
     */
    std::atomic<bool> jni_tasks_welcome_;
    /**
     * JNI thread keeps running until it finishes a task after which this value
     * is set to false
     */
    std::atomic<bool> jni_thread_terminated_;
    /**
     * All member variables prefixed with jni should be guarded by this lock.
     */
    std::mutex jni_task_lock_;
    /**
     * Auxiliary for caching MediaProvider methods
     */
    jmethodID CacheMethod(JNIEnv* env, const char method_name[], const char signature[],
                          bool is_static);
    /**
     * Main loop for the JNI thread
     */
    void JniThreadLoop(JavaVM* jvm);
    /**
     * Mechanism for posting JNI tasks and waiting until they're done
     */
    bool PostAndWaitForTask(const JniTask& t);
};

}  // namespace fuse
}  // namespace mediaprovider

#endif  // MEDIAPROVIDER_FUSE_MEDIAPROVIDERWRAPPER_H_
