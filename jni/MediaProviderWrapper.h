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

#include <android-base/logging.h>
#include <jni.h>
#include <sys/types.h>

#include <dirent.h>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <queue>
#include <string>
#include <thread>

#include "libfuse_jni/ReaddirHelper.h"
#include "libfuse_jni/RedactionInfo.h"

namespace mediaprovider {
namespace fuse {

/** Represents file open result from MediaProvider */
struct FileOpenResult {
    FileOpenResult(const int status, const int uid, const uid_t transforms_uid, const int fd,
                   const RedactionInfo* redaction_info)
        : status(status),
          uid(uid),
          transforms_uid(transforms_uid),
          fd(fd),
          redaction_info(redaction_info) {}

    const int status;
    const int uid;
    const uid_t transforms_uid;
    const int fd;
    std::unique_ptr<const RedactionInfo> redaction_info;
};

/**
 * Represents transform info for a file, containing the transforms, the transforms completion
 * status and the ioPath. Provided by MediaProvider.java via a JNI call.
 */
struct FileLookupResult {
    FileLookupResult(int transforms, int transforms_reason, uid_t uid, bool transforms_complete,
                     bool transforms_supported, const std::string& io_path)
        : transforms(transforms),
          transforms_reason(transforms_reason),
          uid(uid),
          transforms_complete(transforms_complete),
          transforms_supported(transforms_supported),
          io_path(io_path) {
        if (transforms != 0) {
            CHECK(transforms_supported);
        }
    }

    /**
     * These fields are not to be interpreted, they are determined and populated from MediaProvider
     * via a JNI call.
     */
    const int transforms;
    const int transforms_reason;
    const uid_t uid;
    const bool transforms_complete;
    const bool transforms_supported;
    const std::string io_path;
};

/**
 * Class that wraps MediaProvider.java and all of the needed JNI calls to make
 * interaction with MediaProvider easier.
 */
class MediaProviderWrapper final {
  public:
    MediaProviderWrapper(JNIEnv* env, jobject media_provider);
    ~MediaProviderWrapper();

    /**
     * Computes and returns the RedactionInfo for a given file and UID.
     *
     * @param uid UID of the app requesting the read
     * @param path path of the requested file that will be used for database operations
     * @param io_path path of the requested file that will be used for IO
     * @return RedactionInfo on success, nullptr on failure to calculate
     * redaction ranges (e.g. exception was thrown in Java world)
     */
    std::unique_ptr<RedactionInfo> GetRedactionInfo(const std::string& path,
                                                    const std::string& io_path, uid_t uid,
                                                    pid_t tid);

    /**
     * Inserts a new entry for the given path and UID.
     *
     * @param path the path of the file to be created
     * @param uid UID of the calling app
     * @return 0 if the operation succeeded,
     * or errno error code if operation fails.
     */
    int InsertFile(const std::string& path, uid_t uid);

    /**
     * Delete the file denoted by the given path on behalf of the given UID.
     *
     * @param path the path of the file to be deleted
     * @param uid UID of the calling app
     * @return 0 upon success, or errno error code if operation fails.
     */
    int DeleteFile(const std::string& path, uid_t uid);

    /**
     * Gets directory entries for given path from MediaProvider database and lower file system
     *
     * @param uid UID of the calling app.
     * @param path Relative path of the directory.
     * @param dirp Pointer to directory stream, used to query lower file system.
     * @return DirectoryEntries with list of directory entries on success.
     * File names in a directory are obtained from MediaProvider. If a path is unknown to
     * MediaProvider, file names are obtained from lower file system. All directory names in the
     * given directory are obtained from lower file system.
     * An empty string in first directory entry name indicates the error occurred while obtaining
     * directory entries, directory entry type will hold the corresponding errno information.
     */
    std::vector<std::shared_ptr<DirectoryEntry>> GetDirectoryEntries(uid_t uid,
                                                                     const std::string& path,
                                                                     DIR* dirp);

    /**
     * Determines if the given UID is allowed to open the file denoted by the given path.
     *
     * Also computes and returns the RedactionInfo for a given file and |uid|
     *
     * @param path path of the requested file that will be used for database operations
     * @param io_path path of the requested file that will be used for IO
     * @param uid UID of the calling app
     * @param tid UID of the calling app
     * @param for_write specifies if the file is to be opened for write
     * @param redact specifies whether to attempt redaction
     * @return FileOpenResult containing status, uid and redaction_info
     */
    std::unique_ptr<FileOpenResult> OnFileOpen(const std::string& path, const std::string& io_path,
                                               uid_t uid, pid_t tid, int transforms_reason,
                                               bool for_write, bool redact,
                                               bool log_transforms_metrics);

    /**
     * Determines if the given UID is allowed to create a directory with the given path.
     *
     * @param path the path of the directory to be created
     * @param uid UID of the calling app
     * @return 0 if it's allowed, or errno error code if operation isn't allowed.
     */
    int IsCreatingDirAllowed(const std::string& path, uid_t uid);

    /**
     * Determines if the given UID is allowed to delete the directory with the given path.
     *
     * @param path the path of the directory to be deleted
     * @param uid UID of the calling app
     * @return 0 if it's allowed, or errno error code if operation isn't allowed.
     */
    int IsDeletingDirAllowed(const std::string& path, uid_t uid);

    /**
     * Determines if the given UID is allowed to open the directory with the given path.
     *
     * @param path the path of the directory to be opened
     * @param uid UID of the calling app
     * @param forWrite if it's a write access
     * @return 0 if it's allowed, or errno error code if operation isn't allowed.
     */
    int IsOpendirAllowed(const std::string& path, uid_t uid, bool forWrite);

    /**
     * Determines if one of the follows is true:
     * 1. The package name of the given private path matches the given uid,
          then this uid has access to private-app directories for this package.
     * 2. The calling uid has special access to private-app directories:
     *    * DownloadProvider and ExternalStorageProvider has access to private
     *      app directories.
     *    * Installer apps have access to Android/obb directories
     *
     * @param uid UID of the app
     * @param path the private path that the UID wants to access
     * @return true if it matches, otherwise return false.
     */
    bool isUidAllowedAccessToDataOrObbPath(uid_t uid, const std::string& path);

    /**
     * Renames a file or directory to new path.
     *
     * @param old_path path of the file or directory to be renamed.
     * @param new_path new path of the file or directory to be renamed.
     * @param uid UID of the calling app.
     * @return 0 if rename is successful, errno if one of the rename fails. If return
     * value is 0, it's guaranteed that file/directory is moved to new_path. For any other errno
     * except EFAULT/EIO, it's guaranteed that file/directory is not renamed.
     */
    int Rename(const std::string& old_path, const std::string& new_path, uid_t uid);

    /**
     * Called whenever a file has been created through FUSE.
     *
     * @param path path of the file that has been created.
     */
    void OnFileCreated(const std::string& path);

    /**
     * Returns FileLookupResult to determine transform info for a path and uid.
     */
    std::unique_ptr<FileLookupResult> FileLookup(const std::string& path, uid_t uid, pid_t tid);

    /** Transforms from src to dst file */
    bool Transform(const std::string& src, const std::string& dst, int transforms,
                   int transforms_reason, uid_t read_uid, uid_t open_uid, uid_t transforms_uid);

    /**
     * Determines if to allow FUSE_LOOKUP for uid. Might allow uids that don't belong to the
     * MediaProvider user, depending on OEM configuration.
     *
     * @param uid linux uid to check
     */
    bool ShouldAllowLookup(uid_t uid, int path_user_id);

    /**
     * Determines if the passed in user ID is an app clone user (paired with user 0)
     *
     * @param userId the user ID to check
     */
    bool IsAppCloneUser(uid_t userId);

    /**
     * Initializes per-process static variables associated with the lifetime of
     * a managed runtime.
     */
    static void OneTimeInit(JavaVM* vm);

    /** TLS Key to map a given thread to its JNIEnv. */
    static pthread_key_t gJniEnvKey;

  private:
    jclass file_lookup_result_class_;
    jclass file_open_result_class_;
    jclass media_provider_class_;
    jobject media_provider_object_;
    /** Cached MediaProvider method IDs **/
    jmethodID mid_insert_file_;
    jmethodID mid_delete_file_;
    jmethodID mid_on_file_open_;
    jmethodID mid_scan_file_;
    jmethodID mid_is_diraccess_allowed_;
    jmethodID mid_get_files_in_dir_;
    jmethodID mid_rename_;
    jmethodID mid_is_uid_allowed_access_to_data_or_obb_path_;
    jmethodID mid_on_file_created_;
    jmethodID mid_should_allow_lookup_;
    jmethodID mid_is_app_clone_user_;
    jmethodID mid_transform_;
    jmethodID mid_file_lookup_;
    /** Cached FileLookupResult field IDs **/
    jfieldID fid_file_lookup_transforms_;
    jfieldID fid_file_lookup_transforms_reason_;
    jfieldID fid_file_lookup_uid_;
    jfieldID fid_file_lookup_transforms_complete_;
    jfieldID fid_file_lookup_transforms_supported_;
    jfieldID fid_file_lookup_io_path_;
    /** Cached FileOpenResult field IDs **/
    jfieldID fid_file_open_status_;
    jfieldID fid_file_open_uid_;
    jfieldID fid_file_open_transforms_uid_;
    jfieldID fid_file_open_redaction_ranges_;
    jfieldID fid_file_open_fd_;

    /**
     * Auxiliary for caching MediaProvider methods.
     */
    jmethodID CacheMethod(JNIEnv* env, const char method_name[], const char signature[]);

    // Attaches the current thread (if necessary) and returns the JNIEnv
    // associated with it.
    static JNIEnv* MaybeAttachCurrentThread();
    // Destructor function for a given native thread. Called precisely once
    // by the pthreads library.
    static void DetachThreadFunction(void* unused);

    static JavaVM* gJavaVm;
};

}  // namespace fuse
}  // namespace mediaprovider

#endif  // MEDIAPROVIDER_FUSE_MEDIAPROVIDERWRAPPER_H_
