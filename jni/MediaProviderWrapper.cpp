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
using std::string;

namespace {

constexpr const char* kPropRedactionEnabled = "persist.sys.fuse.redaction-enabled";

constexpr uid_t ROOT_UID = 0;
constexpr uid_t SHELL_UID = 2000;

// These need to stay in sync with MediaProvider.java's DIRECTORY_ACCESS_FOR_* constants.
enum DirectoryAccessRequestType {
    kReadDirectoryRequest = 1,
    kWriteDirectoryRequest = 2,
    kCreateDirectoryRequest = 3,
    kDeleteDirectoryRequest = 4,
};

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

/**
 * Auxiliary for caching class fields
 */
static jfieldID CacheField(JNIEnv* env, jclass clazz, const char field_name[], const char type[]) {
    jfieldID fid;
    string actual_field_name(field_name);
    fid = env->GetFieldID(clazz, actual_field_name.c_str(), type);
    if (!fid) {
        LOG(FATAL) << "Error caching field: " << field_name << type;
    }
    return fid;
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

int isDirAccessAllowedInternal(JNIEnv* env, jobject media_provider_object,
                               jmethodID mid_is_diraccess_allowed, const string& path, uid_t uid,
                               int accessType) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    int res = env->CallIntMethod(media_provider_object, mid_is_diraccess_allowed, j_path.get(), uid,
                                 accessType);

    if (CheckForJniException(env)) {
        return EFAULT;
    }
    return res;
}

bool isUidAllowedAccessToDataOrObbPathInternal(JNIEnv* env, jobject media_provider_object,
                                               jmethodID mid_is_uid_allowed_path_access_, uid_t uid,
                                               const string& path) {
    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    bool res = env->CallBooleanMethod(media_provider_object, mid_is_uid_allowed_path_access_, uid,
                                      j_path.get());

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
    mid_insert_file_ = CacheMethod(env, "insertFileIfNecessary", "(Ljava/lang/String;I)I");
    mid_delete_file_ = CacheMethod(env, "deleteFile", "(Ljava/lang/String;I)I");
    mid_on_file_open_ = CacheMethod(env, "onFileOpen",
                                    "(Ljava/lang/String;Ljava/lang/String;IIIZZZ)Lcom/android/"
                                    "providers/media/FileOpenResult;");
    mid_is_diraccess_allowed_ = CacheMethod(env, "isDirAccessAllowed", "(Ljava/lang/String;II)I");
    mid_get_files_in_dir_ =
            CacheMethod(env, "getFilesInDirectory", "(Ljava/lang/String;I)[Ljava/lang/String;");
    mid_rename_ = CacheMethod(env, "rename", "(Ljava/lang/String;Ljava/lang/String;I)I");
    mid_is_uid_allowed_access_to_data_or_obb_path_ =
            CacheMethod(env, "isUidAllowedAccessToDataOrObbPath", "(ILjava/lang/String;)Z");
    mid_on_file_created_ = CacheMethod(env, "onFileCreated", "(Ljava/lang/String;)V");
    mid_should_allow_lookup_ = CacheMethod(env, "shouldAllowLookup", "(II)Z");
    mid_is_app_clone_user_ = CacheMethod(env, "isAppCloneUser", "(I)Z");
    mid_transform_ = CacheMethod(env, "transform", "(Ljava/lang/String;Ljava/lang/String;IIIII)Z");
    mid_file_lookup_ =
            CacheMethod(env, "onFileLookup",
                        "(Ljava/lang/String;II)Lcom/android/providers/media/FileLookupResult;");

    // FileLookupResult
    file_lookup_result_class_ = env->FindClass("com/android/providers/media/FileLookupResult");
    if (!file_lookup_result_class_) {
        LOG(FATAL) << "Could not find class FileLookupResult";
    }
    file_lookup_result_class_ =
            reinterpret_cast<jclass>(env->NewGlobalRef(file_lookup_result_class_));
    fid_file_lookup_transforms_ = CacheField(env, file_lookup_result_class_, "transforms", "I");
    fid_file_lookup_transforms_reason_ =
            CacheField(env, file_lookup_result_class_, "transformsReason", "I");
    fid_file_lookup_uid_ = CacheField(env, file_lookup_result_class_, "uid", "I");
    fid_file_lookup_transforms_complete_ =
            CacheField(env, file_lookup_result_class_, "transformsComplete", "Z");
    fid_file_lookup_transforms_supported_ =
            CacheField(env, file_lookup_result_class_, "transformsSupported", "Z");
    fid_file_lookup_io_path_ =
            CacheField(env, file_lookup_result_class_, "ioPath", "Ljava/lang/String;");

    // FileOpenResult
    file_open_result_class_ = env->FindClass("com/android/providers/media/FileOpenResult");
    if (!file_open_result_class_) {
        LOG(FATAL) << "Could not find class FileOpenResult";
    }
    file_open_result_class_ = reinterpret_cast<jclass>(env->NewGlobalRef(file_open_result_class_));
    fid_file_open_status_ = CacheField(env, file_open_result_class_, "status", "I");
    fid_file_open_uid_ = CacheField(env, file_open_result_class_, "uid", "I");
    fid_file_open_transforms_uid_ = CacheField(env, file_open_result_class_, "transformsUid", "I");
    fid_file_open_redaction_ranges_ =
            CacheField(env, file_open_result_class_, "redactionRanges", "[J");
    fid_file_open_fd_ = CacheField(env, file_open_result_class_, "nativeFd", "I");
}

MediaProviderWrapper::~MediaProviderWrapper() {
    JNIEnv* env = MaybeAttachCurrentThread();
    env->DeleteGlobalRef(media_provider_object_);
    env->DeleteGlobalRef(media_provider_class_);
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

std::unique_ptr<FileOpenResult> MediaProviderWrapper::OnFileOpen(const string& path,
                                                                 const string& io_path, uid_t uid,
                                                                 pid_t tid, int transforms_reason,
                                                                 bool for_write, bool redact,
                                                                 bool log_transforms_metrics) {
    JNIEnv* env = MaybeAttachCurrentThread();
    if (shouldBypassMediaProvider(uid)) {
        return std::make_unique<FileOpenResult>(0, uid, /* transforms_uid */ 0, /* nativeFd */ -1,
                                                new RedactionInfo());
    }

    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));
    ScopedLocalRef<jstring> j_io_path(env, env->NewStringUTF(io_path.c_str()));
    ScopedLocalRef<jobject> j_res_file_open_object(
            env, env->CallObjectMethod(media_provider_object_, mid_on_file_open_, j_path.get(),
                                       j_io_path.get(), uid, tid, transforms_reason, for_write,
                                       redact, log_transforms_metrics));

    if (CheckForJniException(env)) {
        return nullptr;
    }

    const int status = env->GetIntField(j_res_file_open_object.get(), fid_file_open_status_);
    const int original_uid = env->GetIntField(j_res_file_open_object.get(), fid_file_open_uid_);
    const int transforms_uid =
            env->GetIntField(j_res_file_open_object.get(), fid_file_open_transforms_uid_);
    const int fd = env->GetIntField(j_res_file_open_object.get(), fid_file_open_fd_);

    if (redact) {
        ScopedLocalRef<jlongArray> redaction_ranges_local_ref(
                env, static_cast<jlongArray>(env->GetObjectField(j_res_file_open_object.get(),
                                                                 fid_file_open_redaction_ranges_)));
        ScopedLongArrayRO redaction_ranges(env, redaction_ranges_local_ref.get());

        std::unique_ptr<RedactionInfo> ri;
        if (redaction_ranges.size() % 2) {
            LOG(ERROR) << "Error while calculating redaction ranges: array length is uneven";
        } else if (redaction_ranges.size() > 0) {
            ri = std::make_unique<RedactionInfo>(redaction_ranges.size() / 2,
                                                 redaction_ranges.get());
        } else {
            // No ranges to redact
            ri = std::make_unique<RedactionInfo>();
        }
        return std::make_unique<FileOpenResult>(status, original_uid, transforms_uid, fd,
                                                ri.release());
    } else {
        return std::make_unique<FileOpenResult>(status, original_uid, transforms_uid, fd,
                                                new RedactionInfo());
    }
}

int MediaProviderWrapper::IsCreatingDirAllowed(const string& path, uid_t uid) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return isDirAccessAllowedInternal(env, media_provider_object_, mid_is_diraccess_allowed_, path,
                                      uid, kCreateDirectoryRequest);
}

int MediaProviderWrapper::IsDeletingDirAllowed(const string& path, uid_t uid) {
    if (shouldBypassMediaProvider(uid)) {
        return 0;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return isDirAccessAllowedInternal(env, media_provider_object_, mid_is_diraccess_allowed_, path,
                                      uid, kDeleteDirectoryRequest);
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
    return isDirAccessAllowedInternal(env, media_provider_object_, mid_is_diraccess_allowed_, path,
                                      uid,
                                      forWrite ? kWriteDirectoryRequest : kReadDirectoryRequest);
}

bool MediaProviderWrapper::isUidAllowedAccessToDataOrObbPath(uid_t uid, const string& path) {
    if (shouldBypassMediaProvider(uid)) {
        return true;
    }

    JNIEnv* env = MaybeAttachCurrentThread();
    return isUidAllowedAccessToDataOrObbPathInternal(
            env, media_provider_object_, mid_is_uid_allowed_access_to_data_or_obb_path_, uid, path);
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

std::unique_ptr<FileLookupResult> MediaProviderWrapper::FileLookup(const std::string& path,
                                                                   uid_t uid, pid_t tid) {
    JNIEnv* env = MaybeAttachCurrentThread();

    ScopedLocalRef<jstring> j_path(env, env->NewStringUTF(path.c_str()));

    ScopedLocalRef<jobject> j_res_file_lookup_object(
            env, env->CallObjectMethod(media_provider_object_, mid_file_lookup_, j_path.get(), uid,
                                       tid));

    if (CheckForJniException(env)) {
        return nullptr;
    }

    int transforms = env->GetIntField(j_res_file_lookup_object.get(), fid_file_lookup_transforms_);
    int transforms_reason =
            env->GetIntField(j_res_file_lookup_object.get(), fid_file_lookup_transforms_reason_);
    int original_uid = env->GetIntField(j_res_file_lookup_object.get(), fid_file_lookup_uid_);
    bool transforms_complete = env->GetBooleanField(j_res_file_lookup_object.get(),
                                                    fid_file_lookup_transforms_complete_);
    bool transforms_supported = env->GetBooleanField(j_res_file_lookup_object.get(),
                                                     fid_file_lookup_transforms_supported_);
    ScopedLocalRef<jstring> j_io_path(
            env,
            (jstring)env->GetObjectField(j_res_file_lookup_object.get(), fid_file_lookup_io_path_));
    ScopedUtfChars j_io_path_utf(env, j_io_path.get());

    std::unique_ptr<FileLookupResult> file_lookup_result = std::make_unique<FileLookupResult>(
            transforms, transforms_reason, original_uid, transforms_complete, transforms_supported,
            string(j_io_path_utf.c_str()));
    return file_lookup_result;
}

bool MediaProviderWrapper::Transform(const std::string& src, const std::string& dst, int transforms,
                                     int transforms_reason, uid_t read_uid, uid_t open_uid,
                                     uid_t transforms_uid) {
    JNIEnv* env = MaybeAttachCurrentThread();

    ScopedLocalRef<jstring> j_src(env, env->NewStringUTF(src.c_str()));
    ScopedLocalRef<jstring> j_dst(env, env->NewStringUTF(dst.c_str()));
    bool res = env->CallBooleanMethod(media_provider_object_, mid_transform_, j_src.get(),
                                      j_dst.get(), transforms, transforms_reason, read_uid,
                                      open_uid, transforms_uid);

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
                                            const char signature[]) {
    jmethodID mid;
    string actual_method_name(method_name);
    actual_method_name.append("ForFuse");
    mid = env->GetMethodID(media_provider_class_, actual_method_name.c_str(), signature);

    if (!mid) {
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
