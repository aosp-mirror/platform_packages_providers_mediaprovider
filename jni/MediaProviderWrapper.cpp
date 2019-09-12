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

}  // namespace
/*****************************************************************************************/
/******************************* Public API Implementation *******************************/
/*****************************************************************************************/

MediaProviderWrapper::MediaProviderWrapper(JNIEnv* env, jobject mediaProvider) {
    if (!mediaProvider) {
        LOG(FATAL) << "MediaProvider is null!";
    }
    env->GetJavaVM(&jvm);
    if (CheckForJniException(env)) {
        LOG(FATAL) << "Could not get JavaVM!";
    }
    mediaProviderObject = reinterpret_cast<jobject>(env->NewGlobalRef(mediaProvider));
    mediaProviderClass = env->FindClass("com/android/providers/media/MediaProvider");
    if (!mediaProviderClass) {
        LOG(FATAL) << "Could not find class MediaProvider";
    }
    mediaProviderClass = reinterpret_cast<jclass>(env->NewGlobalRef(mediaProviderClass));

    // Cache methods - Before calling a method, make sure you cache it here
    mid_getRedactionRanges = CacheMethod(env, "getRedactionRanges", "(II)[J", /*isStatic*/ false);

    LOG(INFO) << "Successfully initialized MediaProviderWrapper";
}

MediaProviderWrapper::~MediaProviderWrapper() {
    JNIEnv* env;
    jvm->AttachCurrentThread(&env, NULL);
    env->DeleteGlobalRef(mediaProviderObject);
    env->DeleteGlobalRef(mediaProviderClass);
    jvm->DetachCurrentThread();
}

std::unique_ptr<RedactionInfo> MediaProviderWrapper::GetRedactionInfo(uid_t uid, int fd) {
    LOG(DEBUG) << "Computing redaction ranges for fd = " << fd << " uid = " << uid;
    JNIEnv* env;
    jvm->AttachCurrentThread(&env, NULL);

    // TODO: Consider using ScopedLocalRef?
    jlongArray redactionRangesObj = static_cast<jlongArray>(
            env->CallObjectMethod(mediaProviderObject, mid_getRedactionRanges, uid, fd));
    lseek(fd, 0, SEEK_SET);
    if (CheckForJniException(env)) {
        LOG(ERROR) << "Exception occurred while calling MediaProvider#getRedactionRanges";
        jvm->DetachCurrentThread();
        return nullptr;
    }

    jsize redactionRangesSize = env->GetArrayLength(redactionRangesObj);
    off64_t* redactionRanges = (off64_t*)env->GetLongArrayElements(redactionRangesObj, 0);

    std::unique_ptr<RedactionInfo> ri = nullptr;
    if (redactionRangesSize % 2) {
        LOG(ERROR) << "Error while calculating redaction ranges: array length is uneven";
    } else if (redactionRangesSize > 0) {
        ri = std::make_unique<RedactionInfo>(redactionRangesSize / 2, redactionRanges);
        LOG(DEBUG) << "Redaction ranges computed. Number of ranges = " << ri->size();
    } else {
        // No ranges to redact
        ri = std::make_unique<RedactionInfo>();
        LOG(DEBUG) << "Redaction ranges computed. No ranges to redact.";
    }

    env->ReleaseLongArrayElements(redactionRangesObj, (jlong*)redactionRanges, 0);
    jvm->DetachCurrentThread();
    return ri;
}

/*****************************************************************************************/
/******************************** Private member functions *******************************/
/*****************************************************************************************/

/**
 * Finds MediaProvider method and adds it to methods map so it can be quickly called later.
 */
jmethodID MediaProviderWrapper::CacheMethod(JNIEnv* env, const char methodName[],
                                            const char signature[], bool isStatic) {
    jmethodID mid;
    if (isStatic) {
        mid = env->GetStaticMethodID(mediaProviderClass, methodName, signature);
    } else {
        mid = env->GetMethodID(mediaProviderClass, methodName, signature);
    }
    if (!mid) {
        // SHOULD NOT HAPPEN!
        LOG(FATAL) << "Error caching method: " << methodName << signature;
    }
    return mid;
}

}  // namespace fuse
}  // namespace mediaprovider
