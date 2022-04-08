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

#include <memory>
#include <string>

#include <android-base/unique_fd.h>

#include "MediaProviderWrapper.h"
#include "jni.h"

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
    void Start(android::base::unique_fd fd, const std::string& path);

    /**
     * Checks if the FUSE daemon is started.
     */
    bool IsStarted() const;

    /**
     * Check if file should be opened with FUSE
     */
    bool ShouldOpenWithFuse(int fd, bool for_read, const std::string& path);

    /**
     * Invalidate FUSE VFS dentry cache entry for path
     */
    void InvalidateFuseDentryCache(const std::string& path);

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
