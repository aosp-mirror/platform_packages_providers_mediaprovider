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

#define LOG_TAG "MediaProviderWrapper"

#include "MediaProviderWrapper.h"

#include <android-base/logging.h>
#include <jni.h>
#include <nativehelper/scoped_local_ref.h>
#include <nativehelper/scoped_primitive_array.h>
#include <pthread.h>

#include <mutex>
#include <unordered_map>

namespace mediaprovider {
namespace fuse {
using std::string;

namespace {

/** Private helper functions **/

bool CheckForJniException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

std::unique_ptr<RedactionInfo> getRedactionInfoInternal(JNIEnv* env, jobject media_provider_object,
                                                        jmethodID mid_get_redaction_ranges,
                                                        uid_t uid, int fd) {
    LOG(DEBUG) << "Computing redaction ranges for uid = " << uid << " fd = " << fd;
    ScopedLongArrayRO redaction_ranges(
            env, static_cast<jlongArray>(env->CallObjectMethod(media_provider_object,
                                                               mid_get_redaction_ranges, uid, fd)));

    if (lseek(fd, 0, SEEK_SET)) {
        return nullptr;
    }
    if (CheckForJniException(env)) {
        LOG(ERROR) << "Exception occurred while calling MediaProvider#getRedactionRanges";
        return nullptr;
    }

    std::unique_ptr<RedactionInfo> ri;
    if (redaction_ranges.size() % 2) {
        LOG(ERROR) << "Error while calculating redaction ranges: array length is uneven";
    } else if (redaction_ranges.size() > 0) {
        ri = std::make_unique<RedactionInfo>(redaction_ranges.size() / 2, redaction_ranges.get());
        LOG(DEBUG) << "Redaction ranges computed. Number of ranges = " << ri->size();
    } else {
        // No ranges to redact
        ri = std::make_unique<RedactionInfo>();
        LOG(DEBUG) << "Redaction ranges computed. No ranges to redact.";
    }

    return ri;
}

int createFileInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_create_file,
                       const string& path, uid_t uid) {
    LOG(DEBUG) << "Create file for UID = " << uid << ". Path = " << path;
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int fd = env->CallIntMethod(media_provider_object, mid_create_file, j_path.get(), uid);

    if (CheckForJniException(env)) {
        LOG(DEBUG) << "Java exception while creating file";
        return -EFAULT;
    }
    LOG(DEBUG) << "fd = " << fd;
    return fd;
}

int deleteFileInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_delete_file,
                       const string& path, uid_t uid) {
    LOG(DEBUG) << "Delete file for UID = " << uid << ". Path = " << path;
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_delete_file, j_path.get(), uid);

    if (CheckForJniException(env)) {
        LOG(DEBUG) << "Java exception while deleting file";
        return -EFAULT;
    }
    LOG(DEBUG) << "res = " << res;
    return res;
}

}  // namespace
/*****************************************************************************************/
/******************************* Public API Implementation *******************************/
/*****************************************************************************************/

MediaProviderWrapper::MediaProviderWrapper(JNIEnv* env, jobject media_provider) {
    if (!media_provider) {
        LOG(FATAL) << "MediaProvider is null!";
    }
    JavaVM* jvm;
    env->GetJavaVM(&jvm);
    if (CheckForJniException(env)) {
        LOG(FATAL) << "Could not get JavaVM!";
    }
    media_provider_object_ = reinterpret_cast<jobject>(env->NewGlobalRef(media_provider));
    media_provider_class_ = env->FindClass("com/android/providers/media/MediaProvider");
    if (!media_provider_class_) {
        LOG(FATAL) << "Could not find class MediaProvider";
    }
    media_provider_class_ = reinterpret_cast<jclass>(env->NewGlobalRef(media_provider_class_));

    // Cache methods - Before calling a method, make sure you cache it here
    mid_get_redaction_ranges_ =
            CacheMethod(env, "getRedactionRanges", "(II)[J", /*is_static*/ false);
    mid_create_file_ = CacheMethod(env, "createFile", "(Ljava/lang/String;I)I", /*is_static*/ false);
    mid_delete_file_ = CacheMethod(env, "deleteFile", "(Ljava/lang/String;I)I", /*is_static*/ false);

    jni_tasks_welcome_ = true;
    jni_thread_terminated_ = false;

    jni_thread_ = std::thread(&MediaProviderWrapper::JniThreadLoop, this, jvm);
    pthread_setname_np(jni_thread_.native_handle(), "media_provider_jni_thr");
    LOG(INFO) << "Successfully initialized MediaProviderWrapper";
}

MediaProviderWrapper::~MediaProviderWrapper() {
    {
        std::lock_guard<std::mutex> guard(jni_task_lock_);
        jni_tasks_welcome_ = false;
    }

    // Threads might slip in here and try to post a task, but it will be rejected
    // because the flag value has already been changed. This ensures that the
    // termination task is the last task in the queue.

    LOG(DEBUG) << "Posting task to terminate JNI thread";
    PostAndWaitForTask([this](JNIEnv* env) {
        env->DeleteGlobalRef(media_provider_object_);
        env->DeleteGlobalRef(media_provider_class_);
        jni_thread_terminated_ = true;
    });

    LOG(INFO) << "Successfully destroyed MediaProviderWrapper";
}

std::unique_ptr<RedactionInfo> MediaProviderWrapper::GetRedactionInfo(uid_t uid, int fd) {
    std::unique_ptr<RedactionInfo> res;

    // TODO: Consider what to do if task doesn't get posted. This could happen
    // if MediaProviderWrapper's d'tor was called and before it's done, a thread slipped in
    // and requested to read a file.
    PostAndWaitForTask([this, uid, fd, &res](JNIEnv* env) {
        auto ri = getRedactionInfoInternal(env, media_provider_object_, mid_get_redaction_ranges_,
                                           uid, fd);
        res = std::move(ri);
    });

    return res;
}

int MediaProviderWrapper::CreateFile(const string& path, uid_t uid) {
    int res = -EIO;  // Default value in case JNI thread was being terminated

    PostAndWaitForTask([this, &path, uid, &res](JNIEnv* env) {
        res = createFileInternal(env, media_provider_object_, mid_create_file_, path, uid);
    });

    return res;
}

int MediaProviderWrapper::DeleteFile(const string& path, uid_t uid) {
    int res = -EIO;  // Default value in case JNI thread was being terminated

    PostAndWaitForTask([this, &path, uid, &res](JNIEnv* env) {
        res = deleteFileInternal(env, media_provider_object_, mid_delete_file_, path, uid);
    });

    return res;
}

/*****************************************************************************************/
/******************************** Private member functions *******************************/
/*****************************************************************************************/
/**
 * Handles the synchronization details of posting a task to the JNI thread and waiting for its
 * result.
 */
bool MediaProviderWrapper::PostAndWaitForTask(const JniTask& t) {
    bool task_done = false;
    std::condition_variable cond_task_done;

    std::unique_lock<std::mutex> lock(jni_task_lock_);
    if (!jni_tasks_welcome_) return false;

    jni_tasks_.push([&task_done, &cond_task_done, &t](JNIEnv* env) {
        t(env);
        task_done = true;
        cond_task_done.notify_one();
    });

    // trigger the call to jni_thread_
    pending_task_cond_.notify_one();

    // wait for the call to be performed
    cond_task_done.wait(lock, [&task_done] { return task_done; });
    return true;
}

/**
 * Main loop for jni_thread_.
 * This method makes the running thread sleep until another thread calls
 * this.pending_task_cond_.notify_one(), the calling thread must manually wait for the result.
 */
void MediaProviderWrapper::JniThreadLoop(JavaVM* jvm) {
    JNIEnv* env;
    jvm->AttachCurrentThread(&env, NULL);

    while (!jni_thread_terminated_) {
        std::unique_lock<std::mutex> cond_lock(jni_task_lock_);
        pending_task_cond_.wait(cond_lock, [this] { return !jni_tasks_.empty(); });

        JniTask task = jni_tasks_.front();
        jni_tasks_.pop();
        cond_lock.unlock();

        task(env);
    }

    jvm->DetachCurrentThread();
}

/**
 * Finds MediaProvider method and adds it to methods map so it can be quickly called later.
 */
jmethodID MediaProviderWrapper::CacheMethod(JNIEnv* env, const char method_name[],
                                            const char signature[], bool is_static) {
    jmethodID mid;
    if (is_static) {
        mid = env->GetStaticMethodID(media_provider_class_, method_name, signature);
    } else {
        mid = env->GetMethodID(media_provider_class_, method_name, signature);
    }
    if (!mid) {
        // SHOULD NOT HAPPEN!
        LOG(FATAL) << "Error caching method: " << method_name << signature;
    }
    return mid;
}

}  // namespace fuse
}  // namespace mediaprovider
