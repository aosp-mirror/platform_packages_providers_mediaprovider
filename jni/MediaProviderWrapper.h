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
     * @param path path of the requested file
     * @return RedactionInfo on success, nullptr on failure to calculate
     * redaction ranges (e.g. exception was thrown in Java world)
     */
    std::unique_ptr<RedactionInfo> GetRedactionInfo(const std::string& path, uid_t uid, pid_t tid);

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
     * @param path the path of the file to be opened
     * @param uid UID of the calling app
     * @param for_write specifies if the file is to be opened for write
     * @return 0 upon success or errno value upon failure.
     */
    int IsOpenAllowed(const std::string& path, uid_t uid, bool for_write);

    /**
     * Potentially triggers a scan of the file before closing it and reconciles it with the
     * MediaProvider database.
     *
     * @param path the path of the file to be scanned
     */
    void ScanFile(const std::string& path);

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
     * Determines if the given package name matches its uid
     * or has special access to priv-app directories
     *
     * @param pkg the package name of the app
     * @param uid UID of the app
     * @return true if it matches, otherwise return false.
     */
    bool IsUidForPackage(const std::string& pkg, uid_t uid);

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
    jclass media_provider_class_;
    jobject media_provider_object_;
    /** Cached MediaProvider method IDs **/
    jmethodID mid_get_redaction_ranges_;
    jmethodID mid_insert_file_;
    jmethodID mid_delete_file_;
    jmethodID mid_is_open_allowed_;
    jmethodID mid_scan_file_;
    jmethodID mid_is_mkdir_or_rmdir_allowed_;
    jmethodID mid_is_opendir_allowed_;
    jmethodID mid_get_files_in_dir_;
    jmethodID mid_rename_;
    jmethodID mid_is_uid_for_package_;
    jmethodID mid_on_file_created_;
    jmethodID mid_should_allow_lookup_;
    jmethodID mid_is_app_clone_user_;

    /**
     * Auxiliary for caching MediaProvider methods.
     */
    jmethodID CacheMethod(JNIEnv* env, const char method_name[], const char signature[],
                          bool is_static);

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
