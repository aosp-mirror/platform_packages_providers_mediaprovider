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
#include "libfuse_jni/ReaddirHelper.h"

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <jni.h>
#include <nativehelper/scoped_local_ref.h>
#include <nativehelper/scoped_primitive_array.h>
#include <nativehelper/scoped_utf_chars.h>

#include <pthread.h>

#include <mutex>
#include <unordered_map>

namespace mediaprovider {
namespace fuse {
using android::base::GetBoolProperty;
using std::string;

namespace {

constexpr const char* kPropRedactionEnabled = "persist.sys.fuse.redaction-enabled";
constexpr const char* kPropShellBypass = "persist.sys.fuse.shell-bypass";

constexpr uid_t ROOT_UID = 0;
constexpr uid_t SHELL_UID = 2000;

/** Private helper functions **/

inline bool shouldBypassMediaProvider(uid_t uid) {
    return (uid == SHELL_UID && GetBoolProperty(kPropShellBypass, false)) || uid == ROOT_UID;
}

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
                                                        uid_t uid, const string& path) {
    LOG(DEBUG) << "Computing redaction ranges for uid = " << uid << " file = " << path;
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    ScopedLongArrayRO redaction_ranges(
            env, static_cast<jlongArray>(env->CallObjectMethod(
                         media_provider_object, mid_get_redaction_ranges, j_path.get(), uid)));

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

int insertFileInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_insert_file,
                       const string& path, uid_t uid) {
    LOG(DEBUG) << "Inserting file for UID = " << uid << ". Path = " << path;
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_insert_file, j_path.get(), uid);

    if (CheckForJniException(env)) {
        LOG(DEBUG) << "Java exception while creating file";
        return EFAULT;
    }
    LOG(DEBUG) << "res = " << res;
    return res;
}

int deleteFileInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_delete_file,
                       const string& path, uid_t uid) {
    LOG(DEBUG) << "Delete file for UID = " << uid << ". Path = " << path;
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_delete_file, j_path.get(), uid);

    if (CheckForJniException(env)) {
        LOG(DEBUG) << "Java exception while deleting file";
        return EFAULT;
    }
    LOG(DEBUG) << "res = " << res;
    return res;
}

int isOpenAllowedInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_is_open_allowed,
                          const string& path, uid_t uid, bool for_write) {
    LOG(DEBUG) << "Checking if UID = " << uid << " can open file " << path << " for "
               << (for_write ? "write" : "read only");
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_is_open_allowed, j_path.get(), uid,
                                 for_write);

    if (CheckForJniException(env)) {
        LOG(DEBUG) << "Java exception while checking permissions for file";
        return EFAULT;
    }
    LOG(DEBUG) << "res = " << res;
    return res;
}

void scanFileInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_scan_file,
                      const string& path) {
    LOG(DEBUG) << "Notifying MediaProvider that a file has been modified. path = " << path;
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    env->CallObjectMethod(media_provider_object, mid_scan_file, j_path.get());
    if (CheckForJniException(env)) {
        LOG(DEBUG) << "Java exception while checking permissions for file";
    }
    LOG(DEBUG) << "MediaProvider has been notified";
}

int isDirectoryOperationAllowedInternal(JNIEnv* env, jobject media_provider_object,
                                        jmethodID mid_is_dir_op_allowed, const string& path,
                                        uid_t uid) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_is_dir_op_allowed, j_path.get(), uid);

    if (CheckForJniException(env)) {
        LOG(DEBUG) << "Java exception while checking permissions for creating/deleting/opening dir";
        return EFAULT;
    }
    LOG(DEBUG) << "res = " << res;
    return res;
}

std::vector<std::shared_ptr<DirectoryEntry>> getFilesInDirectoryInternal(
        JNIEnv* env, jobject media_provider_object, jmethodID mid_get_files_in_dir, uid_t uid,
        const string& path) {
    LOG(DEBUG) << "Getting file names in path " << path << " for UID = " << uid;
    std::vector<std::shared_ptr<DirectoryEntry>> directory_entries;
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));

    ScopedLocalRef<jobjectArray> files_list(
            env, static_cast<jobjectArray>(env->CallObjectMethod(
                         media_provider_object, mid_get_files_in_dir, j_path.get(), uid)));

    if (CheckForJniException(env)) {
        LOG(ERROR) << "Exception occurred while calling MediaProvider#getFilesInDirectoryForFuse";
        directory_entries.push_back(std::make_shared<DirectoryEntry>("", EFAULT));
        return directory_entries;
    }

    int de_count = env->GetArrayLength(files_list.get());
    if (de_count == 1) {
        ScopedLocalRef<jstring> j_d_name(env,
                                         (jstring)env->GetObjectArrayElement(files_list.get(), 0));
        ScopedUtfChars d_name(env, j_d_name.get());
        if (d_name.c_str() == nullptr) {
            LOG(ERROR) << "Error reading file name returned from MediaProvider at index " << 0;
            directory_entries.push_back(std::make_shared<DirectoryEntry>("", EFAULT));
            return directory_entries;
        } else if (d_name.c_str()[0] == '\0') {
            // Calling package has no storage permissions.
            directory_entries.push_back(std::make_shared<DirectoryEntry>("", EPERM));
            return directory_entries;
        }
    }

    for (int i = 0; i < de_count; i++) {
        ScopedLocalRef<jstring> j_d_name(env,
                                         (jstring)env->GetObjectArrayElement(files_list.get(), i));
        ScopedUtfChars d_name(env, j_d_name.get());

        if (d_name.c_str() == nullptr) {
            LOG(ERROR) << "Error reading file name returned from MediaProvider at index " << i;
            directory_entries.resize(0);
            directory_entries.push_back(std::make_shared<DirectoryEntry>("", EFAULT));
            break;
        }
        directory_entries.push_back(std::make_shared<DirectoryEntry>(d_name.c_str(), DT_REG));
    }
    return directory_entries;
}

int renameInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_rename,
                   const string& old_path, const string& new_path, uid_t uid) {
    LOG(DEBUG) << "Renaming " << old_path << " to " << new_path << ", uid: " << uid;
    ScopedLocalRef<jstring> j_old_path(env, env->NewStringUTF(old_path.c_str()));
    ScopedLocalRef<jstring> j_new_path(env, env->NewStringUTF(new_path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_rename, j_old_path.get(),
                                 j_new_path.get(), uid);
    if (CheckForJniException(env)) {
        LOG(DEBUG) << "Java exception occurred while renaming a file or directory";
        return EFAULT;
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
    mid_get_redaction_ranges_ = CacheMethod(env, "getRedactionRanges", "(Ljava/lang/String;I)[J",
                                            /*is_static*/ false);
    mid_insert_file_ = CacheMethod(env, "insertFileIfNecessary", "(Ljava/lang/String;I)I",
                                   /*is_static*/ false);
    mid_delete_file_ = CacheMethod(env, "deleteFile", "(Ljava/lang/String;I)I", /*is_static*/ false);
    mid_is_open_allowed_ = CacheMethod(env, "isOpenAllowed", "(Ljava/lang/String;IZ)I",
                                       /*is_static*/ false);
    mid_scan_file_ = CacheMethod(env, "scanFile", "(Ljava/lang/String;)Landroid/net/Uri;",
                                 /*is_static*/ false);
    mid_is_dir_op_allowed_ = CacheMethod(env, "isDirectoryOperationAllowed",
                                         "(Ljava/lang/String;I)I", /*is_static*/ false);
    mid_is_opendir_allowed_ = CacheMethod(env, "isOpendirAllowed", "(Ljava/lang/String;I)I",
                                          /*is_static*/ false);
    mid_get_files_in_dir_ =
            CacheMethod(env, "getFilesInDirectory", "(Ljava/lang/String;I)[Ljava/lang/String;",
                        /*is_static*/ false);
    mid_rename_ = CacheMethod(env, "rename", "(Ljava/lang/String;Ljava/lang/String;I)I",
                              /*is_static*/ false);

    jni_tasks_welcome_ = true;
    request_terminate_jni_thread_ = false;

    jni_thread_ = std::thread(&MediaProviderWrapper::JniThreadLoop, this, jvm);
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
    // async task doesn't check jni_tasks_welcome_ - but we will wait for the thread to terminate
    // anyway
    PostAsyncTask([this](JNIEnv* env) {
        env->DeleteGlobalRef(media_provider_object_);
        env->DeleteGlobalRef(media_provider_class_);
        request_terminate_jni_thread_ = true;
    });

    // wait for the thread to actually terminate
    jni_thread_.join();

    LOG(INFO) << "Successfully destroyed MediaProviderWrapper";
}

std::unique_ptr<RedactionInfo> MediaProviderWrapper::GetRedactionInfo(const string& path,
                                                                      uid_t uid) {
    if (shouldBypassMediaProvider(uid) || !GetBoolProperty(kPropRedactionEnabled, true)) {
        return std::make_unique<RedactionInfo>();
    }

    // Default value in case JNI thread was being terminated, causes the read to fail.
    std::unique_ptr<RedactionInfo> res = nullptr;

    PostAndWaitForTask([this, uid, &path, &res](JNIEnv* env) {
        auto ri = getRedactionInfoInternal(env, media_provider_object_, mid_get_redaction_ranges_,
                                           uid, path);
        res = std::move(ri);
    });

    return res;
}

int MediaProviderWrapper::InsertFile(const string& path, uid_t uid) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    int res = EIO;  // Default value in case JNI thread was being terminated

    PostAndWaitForTask([this, &path, uid, &res](JNIEnv* env) {
        res = insertFileInternal(env, media_provider_object_, mid_insert_file_, path, uid);
    });

    return res;
}

int MediaProviderWrapper::DeleteFile(const string& path, uid_t uid) {
    int res = EIO;  // Default value in case JNI thread was being terminated
    if (shouldBypassMediaProvider(uid)) {
        res = unlink(path.c_str());
        ScanFile(path);
        return res;
    }

    PostAndWaitForTask([this, &path, uid, &res](JNIEnv* env) {
        res = deleteFileInternal(env, media_provider_object_, mid_delete_file_, path, uid);
    });

    return res;
}

int MediaProviderWrapper::IsOpenAllowed(const string& path, uid_t uid, bool for_write) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    int res = EIO;  // Default value in case JNI thread was being terminated

    PostAndWaitForTask([this, &path, uid, for_write, &res](JNIEnv* env) {
        res = isOpenAllowedInternal(env, media_provider_object_, mid_is_open_allowed_, path, uid,
                                    for_write);
    });

    return res;
}

void MediaProviderWrapper::ScanFile(const string& path) {
    // Don't send in path by reference, since the memory might be deleted before we get the chances
    // to perfrom the task.
    PostAsyncTask([this, path](JNIEnv* env) {
        scanFileInternal(env, media_provider_object_, mid_scan_file_, path);
    });
}

int MediaProviderWrapper::IsCreatingDirAllowed(const string& path, uid_t uid) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    int res = EIO;  // Default value in case JNI thread was being terminated

    PostAndWaitForTask([this, &path, uid, &res](JNIEnv* env) {
        LOG(DEBUG) << "Checking if UID = " << uid << " can create dir " << path;
        res = isDirectoryOperationAllowedInternal(env, media_provider_object_,
                                                  mid_is_dir_op_allowed_, path, uid);
    });

    return res;
}

int MediaProviderWrapper::IsDeletingDirAllowed(const string& path, uid_t uid) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    int res = EIO;  // Default value in case JNI thread was being terminated

    PostAndWaitForTask([this, &path, uid, &res](JNIEnv* env) {
        LOG(DEBUG) << "Checking if UID = " << uid << " can delete dir " << path;
        res = isDirectoryOperationAllowedInternal(env, media_provider_object_,
                                                  mid_is_dir_op_allowed_, path, uid);
    });
    return res;
}

std::vector<std::shared_ptr<DirectoryEntry>> MediaProviderWrapper::GetDirectoryEntries(
        uid_t uid, const string& path, DIR* dirp) {
    // Default value in case JNI thread was being terminated
    std::vector<std::shared_ptr<DirectoryEntry>> res;
    if (shouldBypassMediaProvider(uid)) {
        addDirectoryEntriesFromLowerFs(dirp, /* filter */ nullptr, &res);
        return res;
    }

    PostAndWaitForTask([this, uid, path, &res](JNIEnv* env) {
        res = getFilesInDirectoryInternal(env, media_provider_object_, mid_get_files_in_dir_, uid,
                                          path);
    });

    const int res_size = res.size();
    if (res_size && res[0]->d_name[0] == '/') {
        // Path is unknown to MediaProvider, get files and directories from lower file system.
        res.resize(0);
        addDirectoryEntriesFromLowerFs(dirp, /* filter */ nullptr, &res);
    } else if (res_size == 0 || !res[0]->d_name.empty()) {
        // add directory names from lower file system.
        addDirectoryEntriesFromLowerFs(dirp, /* filter */ &isDirectory, &res);
    }
    return res;
}

int MediaProviderWrapper::IsOpendirAllowed(const string& path, uid_t uid) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    int res = EIO;  // Default value in case JNI thread was being terminated

    PostAndWaitForTask([this, &path, uid, &res](JNIEnv* env) {
        LOG(DEBUG) << "Checking if UID = " << uid << " can open dir " << path;
        res = isDirectoryOperationAllowedInternal(env, media_provider_object_,
                                                  mid_is_opendir_allowed_, path, uid);
    });

    return res;
}

int MediaProviderWrapper::Rename(const string& old_path, const string& new_path, uid_t uid) {
    int res = EIO;  // Default value in case JNI thread was being terminated
    if (shouldBypassMediaProvider(uid)) {
        res = rename(old_path.c_str(), new_path.c_str());
        if (res != 0) res = -errno;
        return res;
    }

    PostAndWaitForTask([this, old_path, new_path, uid, &res](JNIEnv* env) {
        res = renameInternal(env, media_provider_object_, mid_rename_, old_path, new_path, uid);
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
 * Handles the synchronization details of posting an async task to the JNI thread.
 */
void MediaProviderWrapper::PostAsyncTask(const JniTask& t) {
    std::unique_lock<std::mutex> lock(jni_task_lock_);

    jni_tasks_.push(t);
    // trigger the call to jni_thread_
    pending_task_cond_.notify_one();
}

/**
 * Main loop for jni_thread_.
 * This method makes the running thread sleep until another thread calls
 * this.pending_task_cond_.notify_one(), the calling thread must manually wait for the result.
 */
void MediaProviderWrapper::JniThreadLoop(JavaVM* jvm) {
    JNIEnv* env;
    jvm->AttachCurrentThread(&env, NULL);
    pthread_setname_np(pthread_self(), "jni_loop");

    while (!request_terminate_jni_thread_) {
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
    string actual_method_name(method_name);
    actual_method_name.append("ForFuse");
    if (is_static) {
        mid = env->GetStaticMethodID(media_provider_class_, actual_method_name.c_str(), signature);
    } else {
        mid = env->GetMethodID(media_provider_class_, actual_method_name.c_str(), signature);
    }
    if (!mid) {
        // SHOULD NOT HAPPEN!
        LOG(FATAL) << "Error caching method: " << method_name << signature;
    }
    return mid;
}

}  // namespace fuse
}  // namespace mediaprovider
