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

constexpr uid_t ROOT_UID = 0;
constexpr uid_t SHELL_UID = 2000;

/** Private helper functions **/

inline bool shouldBypassMediaProvider(uid_t uid) {
    return uid == SHELL_UID || uid == ROOT_UID;
}

static bool CheckForJniException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

std::unique_ptr<RedactionInfo> getRedactionInfoInternal(JNIEnv* env, jobject media_provider_object,
                                                        jmethodID mid_get_redaction_ranges,
                                                        uid_t uid, pid_t tid, const string& path) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    ScopedLocalRef<jlongArray> redaction_ranges_local_ref(
            env, static_cast<jlongArray>(env->CallObjectMethod(
                         media_provider_object, mid_get_redaction_ranges, j_path.get(), uid, tid)));
    ScopedLongArrayRO redaction_ranges(env, redaction_ranges_local_ref.get());

    if (CheckForJniException(env)) {
        return nullptr;
    }

    std::unique_ptr<RedactionInfo> ri;
    if (redaction_ranges.size() % 2) {
        LOG(ERROR) << "Error while calculating redaction ranges: array length is uneven";
    } else if (redaction_ranges.size() > 0) {
        ri = std::make_unique<RedactionInfo>(redaction_ranges.size() / 2, redaction_ranges.get());
    } else {
        // No ranges to redact
        ri = std::make_unique<RedactionInfo>();
    }

    return ri;
}

int insertFileInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_insert_file,
                       const string& path, uid_t uid) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_insert_file, j_path.get(), uid);

    if (CheckForJniException(env)) {
        return EFAULT;
    }
    return res;
}

int deleteFileInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_delete_file,
                       const string& path, uid_t uid) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_delete_file, j_path.get(), uid);

    if (CheckForJniException(env)) {
        return EFAULT;
    }
    return res;
}

int isOpenAllowedInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_is_open_allowed,
                          const string& path, uid_t uid, bool for_write) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_is_open_allowed, j_path.get(), uid,
                                 for_write);

    if (CheckForJniException(env)) {
        return EFAULT;
    }
    return res;
}

void scanFileInternal(JNIEnv* env, jobject media_provider_object, jmethodID mid_scan_file,
                      const string& path) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    env->CallVoidMethod(media_provider_object, mid_scan_file, j_path.get());
    CheckForJniException(env);
}

int isMkdirOrRmdirAllowedInternal(JNIEnv* env, jobject media_provider_object,
                                  jmethodID mid_is_mkdir_or_rmdir_allowed, const string& path,
                                  uid_t uid, bool forCreate) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_is_mkdir_or_rmdir_allowed, j_path.get(),
                                 uid, forCreate);

    if (CheckForJniException(env)) {
        return EFAULT;
    }
    return res;
}

int isOpendirAllowedInternal(JNIEnv* env, jobject media_provider_object,
                             jmethodID mid_is_opendir_allowed, const string& path, uid_t uid,
                             bool forWrite) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_is_opendir_allowed, j_path.get(), uid,
                                 forWrite);

    if (CheckForJniException(env)) {
        return EFAULT;
    }
    return res;
}

bool isUidForPackageInternal(JNIEnv* env, jobject media_provider_object,
                             jmethodID mid_is_uid_for_package, const string& pkg, uid_t uid) {
    ScopedLocalRef<jstring> j_pkg(env, env->NewStringUTF(pkg.c_str()));
    bool res = env->CallBooleanMethod(media_provider_object, mid_is_uid_for_package, j_pkg.get(),
            uid);

    if (CheckForJniException(env)) {
        return false;
    }
    return res;
}

std::vector<std::shared_ptr<DirectoryEntry>> getFilesInDirectoryInternal(
        JNIEnv* env, jobject media_provider_object, jmethodID mid_get_files_in_dir, uid_t uid,
        const string& path) {
    std::vector<std::shared_ptr<DirectoryEntry>> directory_entries;
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));

    ScopedLocalRef<jobjectArray> files_list(
            env, static_cast<jobjectArray>(env->CallObjectMethod(
                         media_provider_object, mid_get_files_in_dir, j_path.get(), uid)));

    if (CheckForJniException(env)) {
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
    ScopedLocalRef<jstring> j_old_path(env, env->NewStringUTF(old_path.c_str()));
    ScopedLocalRef<jstring> j_new_path(env, env->NewStringUTF(new_path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_rename, j_old_path.get(),
                                 j_new_path.get(), uid);

    if (CheckForJniException(env)) {
        return EFAULT;
    }
    return res;
}

void onFileCreatedInternal(JNIEnv* env, jobject media_provider_object,
                           jmethodID mid_on_file_created, const string& path) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));

    env->CallVoidMethod(media_provider_object, mid_on_file_created, j_path.get());
    CheckForJniException(env);
    return;
}

}  // namespace
/*****************************************************************************************/
/******************************* Public API Implementation *******************************/
/*****************************************************************************************/

JavaVM* MediaProviderWrapper::gJavaVm = nullptr;
pthread_key_t MediaProviderWrapper::gJniEnvKey;

void MediaProviderWrapper::OneTimeInit(JavaVM* vm) {
    gJavaVm = vm;
    CHECK(gJavaVm != nullptr);

    pthread_key_create(&MediaProviderWrapper::gJniEnvKey,
                       MediaProviderWrapper::DetachThreadFunction);
}

MediaProviderWrapper::MediaProviderWrapper(JNIEnv* env, jobject media_provider) {
    if (!media_provider) {
        LOG(FATAL) << "MediaProvider is null!";
    }

    media_provider_object_ = reinterpret_cast<jobject>(env->NewGlobalRef(media_provider));
    media_provider_class_ = env->FindClass("com/android/providers/media/MediaProvider");
    if (!media_provider_class_) {
        LOG(FATAL) << "Could not find class MediaProvider";
    }
    media_provider_class_ = reinterpret_cast<jclass>(env->NewGlobalRef(media_provider_class_));

    // Cache methods - Before calling a method, make sure you cache it here
    mid_get_redaction_ranges_ = CacheMethod(env, "getRedactionRanges", "(Ljava/lang/String;II)[J",
                                            /*is_static*/ false);
    mid_insert_file_ = CacheMethod(env, "insertFileIfNecessary", "(Ljava/lang/String;I)I",
                                   /*is_static*/ false);
    mid_delete_file_ = CacheMethod(env, "deleteFile", "(Ljava/lang/String;I)I", /*is_static*/ false);
    mid_is_open_allowed_ = CacheMethod(env, "isOpenAllowed", "(Ljava/lang/String;IZ)I",
                                       /*is_static*/ false);
    mid_scan_file_ = CacheMethod(env, "scanFile", "(Ljava/lang/String;)V",
                                 /*is_static*/ false);
    mid_is_mkdir_or_rmdir_allowed_ = CacheMethod(env, "isDirectoryCreationOrDeletionAllowed",
                                                 "(Ljava/lang/String;IZ)I", /*is_static*/ false);
    mid_is_opendir_allowed_ = CacheMethod(env, "isOpendirAllowed", "(Ljava/lang/String;IZ)I",
                                          /*is_static*/ false);
    mid_get_files_in_dir_ =
            CacheMethod(env, "getFilesInDirectory", "(Ljava/lang/String;I)[Ljava/lang/String;",
                        /*is_static*/ false);
    mid_rename_ = CacheMethod(env, "rename", "(Ljava/lang/String;Ljava/lang/String;I)I",
                              /*is_static*/ false);
    mid_is_uid_for_package_ = CacheMethod(env, "isUidForPackage", "(Ljava/lang/String;I)Z",
                              /*is_static*/ false);
    mid_on_file_created_ = CacheMethod(env, "onFileCreated", "(Ljava/lang/String;)V",
                                       /*is_static*/ false);
    mid_should_allow_lookup_ = CacheMethod(env, "shouldAllowLookup", "(II)Z",
                                           /*is_static*/ false);
    mid_is_app_clone_user_ = CacheMethod(env, "isAppCloneUser", "(I)Z",
                                         /*is_static*/ false);
}

MediaProviderWrapper::~MediaProviderWrapper() {
    JNIEnv* env = MaybeAttachCurrentThread();
    env->DeleteGlobalRef(media_provider_object_);
    env->DeleteGlobalRef(media_provider_class_);
}

std::unique_ptr<RedactionInfo> MediaProviderWrapper::GetRedactionInfo(const string& path, uid_t uid,
                                                                      pid_t tid) {
    if (shouldBypassMediaProvider(uid) || !GetBoolProperty(kPropRedactionEnabled, true)) {
        return std::make_unique<RedactionInfo>();
    }

    // Default value in case JNI thread was being terminated, causes the read to fail.
    std::unique_ptr<RedactionInfo> res = nullptr;

    JNIEnv* env = MaybeAttachCurrentThread();
    auto ri = getRedactionInfoInternal(env, media_provider_object_, mid_get_redaction_ranges_, uid,
                                       tid, path);
    res = std::move(ri);

    return res;
}

int MediaProviderWrapper::InsertFile(const string& path, uid_t uid) {
    if (uid == ROOT_UID) {
        return 0;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return insertFileInternal(env, media_provider_object_, mid_insert_file_, path, uid);
}

int MediaProviderWrapper::DeleteFile(const string& path, uid_t uid) {
    if (uid == ROOT_UID) {
        int res = unlink(path.c_str());
        return res;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return deleteFileInternal(env, media_provider_object_, mid_delete_file_, path, uid);
}

int MediaProviderWrapper::IsOpenAllowed(const string& path, uid_t uid, bool for_write) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return isOpenAllowedInternal(env, media_provider_object_, mid_is_open_allowed_, path, uid,
                                 for_write);
}

void MediaProviderWrapper::ScanFile(const string& path) {
    JNIEnv* env = MaybeAttachCurrentThread();
    scanFileInternal(env, media_provider_object_, mid_scan_file_, path);
}

int MediaProviderWrapper::IsCreatingDirAllowed(const string& path, uid_t uid) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return isMkdirOrRmdirAllowedInternal(env, media_provider_object_,
                                         mid_is_mkdir_or_rmdir_allowed_, path, uid,
                                         /*forCreate*/ true);
}

int MediaProviderWrapper::IsDeletingDirAllowed(const string& path, uid_t uid) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return isMkdirOrRmdirAllowedInternal(env, media_provider_object_,
                                         mid_is_mkdir_or_rmdir_allowed_, path, uid,
                                         /*forCreate*/ false);
}

std::vector<std::shared_ptr<DirectoryEntry>> MediaProviderWrapper::GetDirectoryEntries(
        uid_t uid, const string& path, DIR* dirp) {
    // Default value in case JNI thread was being terminated
    std::vector<std::shared_ptr<DirectoryEntry>> res;
    if (shouldBypassMediaProvider(uid)) {
        addDirectoryEntriesFromLowerFs(dirp, /* filter */ nullptr, &res);
        return res;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    res = getFilesInDirectoryInternal(env, media_provider_object_, mid_get_files_in_dir_, uid, path);

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

int MediaProviderWrapper::IsOpendirAllowed(const string& path, uid_t uid, bool forWrite) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return isOpendirAllowedInternal(env, media_provider_object_, mid_is_opendir_allowed_, path, uid,
                                    forWrite);
}

bool MediaProviderWrapper::IsUidForPackage(const string& pkg, uid_t uid) {
    if (shouldBypassMediaProvider(uid)) {
        return true;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return isUidForPackageInternal(env, media_provider_object_, mid_is_uid_for_package_, pkg, uid);
}

int MediaProviderWrapper::Rename(const string& old_path, const string& new_path, uid_t uid) {
    // Rename from SHELL_UID should go through MediaProvider to update database rows, so only bypass
    // MediaProvider for ROOT_UID.
    if (uid == ROOT_UID) {
        int res = rename(old_path.c_str(), new_path.c_str());
        if (res != 0) res = -errno;
        return res;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return renameInternal(env, media_provider_object_, mid_rename_, old_path, new_path, uid);
}

void MediaProviderWrapper::OnFileCreated(const string& path) {
    JNIEnv* env = MaybeAttachCurrentThread();

    return onFileCreatedInternal(env, media_provider_object_, mid_on_file_created_, path);
}

bool MediaProviderWrapper::ShouldAllowLookup(uid_t uid, int path_user_id) {
    JNIEnv* env = MaybeAttachCurrentThread();

    bool res = env->CallBooleanMethod(media_provider_object_, mid_should_allow_lookup_, uid,
                                      path_user_id);

    if (CheckForJniException(env)) {
        return false;
    }
    return res;
}

bool MediaProviderWrapper::IsAppCloneUser(uid_t userId) {
    JNIEnv* env = MaybeAttachCurrentThread();

    bool res = env->CallBooleanMethod(media_provider_object_, mid_is_app_clone_user_, userId);

    if (CheckForJniException(env)) {
        return false;
    }
    return res;
}

/*****************************************************************************************/
/******************************** Private member functions *******************************/
/*****************************************************************************************/

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

void MediaProviderWrapper::DetachThreadFunction(void* unused) {
    int detach = gJavaVm->DetachCurrentThread();
    CHECK_EQ(detach, 0);
}

JNIEnv* MediaProviderWrapper::MaybeAttachCurrentThread() {
    // We could use pthread_getspecific here as that's likely quicker but
    // that would result in wrong behaviour for threads that don't need to
    // be attached (e.g, those that were created in managed code).
    JNIEnv* env = nullptr;
    if (gJavaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_4) == JNI_OK) {
        return env;
    }

    // This thread is currently unattached, so it must not have any TLS
    // value. Note that we don't really care about the actual value we store
    // in TLS -- we only care about the value destructor being called, which
    // will happen as long as the key is not null.
    CHECK(pthread_getspecific(gJniEnvKey) == nullptr);
    CHECK_EQ(gJavaVm->AttachCurrentThread(&env, nullptr), 0);
    CHECK(env != nullptr);

    pthread_setspecific(gJniEnvKey, env);
    return env;
}

}  // namespace fuse
}  // namespace mediaprovider
