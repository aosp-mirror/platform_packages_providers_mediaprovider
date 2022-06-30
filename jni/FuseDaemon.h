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

#ifndef MEDIAPROVIDER_JNI_FUSEDAEMON_H_
#define MEDIAPROVIDER_JNI_FUSEDAEMON_H_

#include <android-base/unique_fd.h>

#include <memory>
#include <string>

#include "MediaProviderWrapper.h"
#include "jni.h"
#include "node-inl.h"

struct fuse;
namespace mediaprovider {
namespace fuse {
class FuseDaemon final {
  public:
    FuseDaemon(JNIEnv* env, jobject mediaProvider);

    ~FuseDaemon() = default;

    /**
     * Start the FUSE daemon loop that will handle filesystem calls.
     */
    void Start(android::base::unique_fd fd, const std::string& path, const bool uncached_mode,
               const std::vector<std::string>& supported_transcoding_relative_paths,
               const std::vector<std::string>& supported_uncached_relative_paths);

    /**
     * Checks if the FUSE daemon is started.
     */
    bool IsStarted() const;

    /**
     * Check if file should be opened with FUSE
     */
    bool ShouldOpenWithFuse(int fd, bool for_read, const std::string& path);

    /**
     * Check if the FUSE daemon uses FUSE passthrough
     */
    bool UsesFusePassthrough() const;

    /**
     * Invalidate FUSE VFS dentry cache entry for path
     */
    void InvalidateFuseDentryCache(const std::string& path);

    /**
     * Checks if the given uid has access to the given fd with or without redaction.
     */
    std::unique_ptr<FdAccessResult> CheckFdAccess(int fd, uid_t uid) const;

    /**
     * Initialize device id for the FUSE daemon with the FUSE device id of the given path.
     */
    void InitializeDeviceId(const std::string& path);

  private:
    FuseDaemon(const FuseDaemon&) = delete;
    void operator=(const FuseDaemon&) = delete;
    MediaProviderWrapper mp;
    std::atomic_bool active;
    struct ::fuse* fuse;
};

}  // namespace fuse
}  // namespace mediaprovider

#endif  // MEDIAPROVIDER_JNI_FUSEDAEMON_H_
