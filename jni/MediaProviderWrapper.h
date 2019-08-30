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

#include <string>

#include "libfuse_jni/RedactionInfo.h"

namespace mediaprovider {
namespace fuse {

/**
 * Class that wraps MediaProvider.java and all of the needed JNI calls to make
 * interaction with MediaProvider easier.
 */
class MediaProviderWrapper final {
  public:
    MediaProviderWrapper(JNIEnv* env, jobject mediaProvider);
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

  private:
    JavaVM* jvm;
    jclass mediaProviderClass;
    jobject mediaProviderObject;
    jmethodID CacheMethod(JNIEnv* env, const char methodName[], const char signature[],
                          bool isStatic);
    /** Add MediaProvider method IDs that you want to use here **/
    jmethodID mid_getRedactionRanges;
};

}  // namespace fuse
}  // namespace mediaprovider

#endif  // MEDIAPROVIDER_FUSE_MEDIAPROVIDERWRAPPER_H_
