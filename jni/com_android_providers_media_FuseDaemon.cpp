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

// Need to use LOGE_EX.
#define LOG_TAG "FuseDaemonJNI"

#include <nativehelper/scoped_utf_chars.h>

#include <string>

#include "FuseDaemon.h"
#include "MediaProviderWrapper.h"
#include "android-base/logging.h"
#include "android-base/unique_fd.h"

namespace mediaprovider {
namespace {

constexpr const char* CLASS_NAME = "com/android/providers/media/fuse/FuseDaemon";
static jclass gFuseDaemonClass;

jlong com_android_providers_media_FuseDaemon_new(JNIEnv* env, jobject self,
                                                 jobject media_provider) {
    LOG(DEBUG) << "Creating the FUSE daemon...";
    return reinterpret_cast<jlong>(new fuse::FuseDaemon(env, media_provider));
}

void com_android_providers_media_FuseDaemon_start(JNIEnv* env, jobject self, jlong java_daemon,
                                                  jint fd, jstring java_path) {
    LOG(DEBUG) << "Starting the FUSE daemon...";
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);

    android::base::unique_fd ufd(fd);

    ScopedUtfChars utf_chars_path(env, java_path);
    if (!utf_chars_path.c_str()) {
        return;
    }

    daemon->Start(std::move(ufd), utf_chars_path.c_str());
}

bool com_android_providers_media_FuseDaemon_is_started(JNIEnv* env, jobject self,
                                                       jlong java_daemon) {
    LOG(DEBUG) << "Checking if FUSE daemon started...";
    const fuse::FuseDaemon* daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    return daemon->IsStarted();
}

void com_android_providers_media_FuseDaemon_delete(JNIEnv* env, jobject self, jlong java_daemon) {
    LOG(DEBUG) << "Destroying the FUSE daemon...";
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    delete daemon;
}

jboolean com_android_providers_media_FuseDaemon_should_open_with_fuse(JNIEnv* env, jobject self,
                                                                      jlong java_daemon,
                                                                      jstring java_path,
                                                                      jboolean for_read, jint fd) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    if (daemon) {
        ScopedUtfChars utf_chars_path(env, java_path);
        if (!utf_chars_path.c_str()) {
            // TODO(b/145741852): Throw exception
            return JNI_FALSE;
        }

        return daemon->ShouldOpenWithFuse(fd, for_read, utf_chars_path.c_str());
    }
    // TODO(b/145741852): Throw exception
    return JNI_FALSE;
}

void com_android_providers_media_FuseDaemon_invalidate_fuse_dentry_cache(JNIEnv* env, jobject self,
                                                                         jlong java_daemon,
                                                                         jstring java_path) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    if (daemon) {
        ScopedUtfChars utf_chars_path(env, java_path);
        if (!utf_chars_path.c_str()) {
            // TODO(b/145741152): Throw exception
            return;
        }

        CHECK(pthread_getspecific(fuse::MediaProviderWrapper::gJniEnvKey) == nullptr);
        daemon->InvalidateFuseDentryCache(utf_chars_path.c_str());
    }
    // TODO(b/145741152): Throw exception
}

bool com_android_providers_media_FuseDaemon_is_fuse_thread(JNIEnv* env, jclass clazz) {
    return pthread_getspecific(fuse::MediaProviderWrapper::gJniEnvKey) != nullptr;
}

const JNINativeMethod methods[] = {
        {"native_new", "(Lcom/android/providers/media/MediaProvider;)J",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_new)},
        {"native_start", "(JILjava/lang/String;)V",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_start)},
        {"native_delete", "(J)V",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_delete)},
        {"native_should_open_with_fuse", "(JLjava/lang/String;ZI)Z",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_should_open_with_fuse)},
        {"native_is_fuse_thread", "()Z",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_is_fuse_thread)},
        {"native_is_started", "(J)Z",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_is_started)},
        {"native_invalidate_fuse_dentry_cache", "(JLjava/lang/String;)V",
         reinterpret_cast<void*>(
                 com_android_providers_media_FuseDaemon_invalidate_fuse_dentry_cache)}};
}  // namespace

void register_android_providers_media_FuseDaemon(JavaVM* vm, JNIEnv* env) {
    gFuseDaemonClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass(CLASS_NAME)));

    if (gFuseDaemonClass == nullptr) {
        LOG(FATAL) << "Unable to find class : " << CLASS_NAME;
    }

    if (env->RegisterNatives(gFuseDaemonClass, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        LOG(FATAL) << "Unable to register native methods";
    }

    fuse::MediaProviderWrapper::OneTimeInit(vm);
}
}  // namespace mediaprovider
