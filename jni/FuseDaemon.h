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

#include <string>

#include "MediaProviderWrapper.h"
#include "jni.h"

namespace mediaprovider {
namespace fuse {

class FuseDaemon final {
  public:
    FuseDaemon(JNIEnv* env, jobject mediaProvider);

    ~FuseDaemon() {
        // TODO(b/135341433): Ensure daemon is stopped and all resources are cleaned up
    }
    /**
     * Start the FUSE daemon loop that will handle filesystem calls.
     */
    void Start(const int fd, const std::string& dest_path, const std::string& source_path);
    /**
     * Stop the FUSE daemon and clean up resources.
     */
    void Stop();

  private:
    FuseDaemon(const FuseDaemon&) = delete;
    void operator=(const FuseDaemon&) = delete;
    MediaProviderWrapper mp;
};

}  // namespace fuse
}  // namespace mediaprovider

#endif  // MEDIAPROVIDER_JNI_FUSEDAEMON_H_
