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

#include <nativehelper/scoped_local_ref.h>
#include <nativehelper/scoped_utf_chars.h>

#include <string>
#include <vector>

#include "FuseDaemon.h"
#include "MediaProviderWrapper.h"
#include "android-base/logging.h"
#include "android-base/unique_fd.h"

namespace mediaprovider {
namespace {

constexpr const char* FUSE_DAEMON_CLASS_NAME = "com/android/providers/media/fuse/FuseDaemon";
constexpr const char* FD_ACCESS_RESULT_CLASS_NAME = "com/android/providers/media/FdAccessResult";
static jclass gFuseDaemonClass;
static jclass gFdAccessResultClass;
static jmethodID gFdAccessResultCtor;

static std::vector<std::string> convert_object_array_to_string_vector(
        JNIEnv* env, jobjectArray java_object_array, const std::string& element_description) {
    ScopedLocalRef<jobjectArray> j_ref_object_array(env, java_object_array);
    std::vector<std::string> utf_strings;

    const int object_array_length = env->GetArrayLength(j_ref_object_array.get());
    for (int i = 0; i < object_array_length; i++) {
        ScopedLocalRef<jstring> j_ref_string(
                env, (jstring)env->GetObjectArrayElement(j_ref_object_array.get(), i));
        ScopedUtfChars utf_chars(env, j_ref_string.get());
        const char* utf_string = utf_chars.c_str();

        if (utf_string) {
            utf_strings.push_back(utf_string);
        } else {
            LOG(ERROR) << "Error reading " << element_description << " at index: " << i;
        }
    }

    return utf_strings;
}

static jobjectArray convert_string_vector_to_object_array(JNIEnv* env,
                                                          std::vector<std::string> string_vector) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(string_vector.size(), stringClass, NULL);
    for (int i = 0; i < string_vector.size(); i++) {
        ScopedLocalRef<jstring> path(env, env->NewStringUTF(string_vector.at(i).c_str()));
        env->SetObjectArrayElement(arr, i, path.get());
    }
    return arr;
}

static std::vector<std::string> get_supported_transcoding_relative_paths(
        JNIEnv* env, jobjectArray java_supported_transcoding_relative_paths) {
    return convert_object_array_to_string_vector(env, java_supported_transcoding_relative_paths,
                                                 "supported transcoding relative path");
}

static std::vector<std::string> get_supported_uncached_relative_paths(
        JNIEnv* env, jobjectArray java_supported_uncached_relative_paths) {
    return convert_object_array_to_string_vector(env, java_supported_uncached_relative_paths,
                                                 "supported uncached relative path");
}

jlong com_android_providers_media_FuseDaemon_new(JNIEnv* env, jobject self,
                                                 jobject media_provider) {
    LOG(DEBUG) << "Creating the FUSE daemon...";
    return reinterpret_cast<jlong>(new fuse::FuseDaemon(env, media_provider));
}

void com_android_providers_media_FuseDaemon_start(
        JNIEnv* env, jobject self, jlong java_daemon, jint fd, jstring java_path,
        jboolean uncached_mode, jobjectArray java_supported_transcoding_relative_paths,
        jobjectArray java_supported_uncached_relative_paths) {
    LOG(DEBUG) << "Starting the FUSE daemon...";
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);

    android::base::unique_fd ufd(fd);

    ScopedUtfChars utf_chars_path(env, java_path);
    if (!utf_chars_path.c_str()) {
        return;
    }

    const std::vector<std::string>& transcoding_relative_paths =
            get_supported_transcoding_relative_paths(env,
                    java_supported_transcoding_relative_paths);
    const std::vector<std::string>& uncached_relative_paths =
            get_supported_uncached_relative_paths(env, java_supported_uncached_relative_paths);

    daemon->Start(std::move(ufd), utf_chars_path.c_str(), uncached_mode, transcoding_relative_paths,
                  uncached_relative_paths);
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

jboolean com_android_providers_media_FuseDaemon_uses_fuse_passthrough(JNIEnv* env, jobject self,
                                                                      jlong java_daemon) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    if (daemon) {
        return daemon->UsesFusePassthrough();
    }
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

jobject com_android_providers_media_FuseDaemon_check_fd_access(JNIEnv* env, jobject self,
                                                               jlong java_daemon, jint fd,
                                                               jint uid) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    const std::unique_ptr<fuse::FdAccessResult> result = daemon->CheckFdAccess(fd, uid);
    return env->NewObject(gFdAccessResultClass, gFdAccessResultCtor,
                          env->NewStringUTF(result->file_path.c_str()), result->should_redact);
}

void com_android_providers_media_FuseDaemon_initialize_device_id(JNIEnv* env, jobject self,
                                                                 jlong java_daemon,
                                                                 jstring java_path) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    ScopedUtfChars utf_chars_path(env, java_path);
    if (!utf_chars_path.c_str()) {
        LOG(WARNING) << "Couldn't initialise FUSE device id";
        return;
    }
    daemon->InitializeDeviceId(utf_chars_path.c_str());
}

void com_android_providers_media_FuseDaemon_setup_volume_db_backup(JNIEnv* env, jobject self,
                                                                   jlong java_daemon) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    daemon->SetupLevelDbInstances();
}

void com_android_providers_media_FuseDaemon_delete_db_backup(JNIEnv* env, jobject self,
                                                             jlong java_daemon, jstring java_path) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    ScopedUtfChars utf_chars_path(env, java_path);
    if (!utf_chars_path.c_str()) {
        LOG(WARNING) << "Couldn't initialise FUSE device id";
        return;
    }
    daemon->DeleteFromLevelDb(utf_chars_path.c_str());
}

void com_android_providers_media_FuseDaemon_backup_volume_db_data(JNIEnv* env, jobject self,
                                                                  jlong java_daemon,
                                                                  jstring java_path, jstring value) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    ScopedUtfChars utf_chars_path(env, java_path);
    ScopedUtfChars utf_chars_value(env, value);
    if (!utf_chars_path.c_str()) {
        LOG(WARNING) << "Couldn't initialise FUSE device id";
        return;
    }
    daemon->InsertInLevelDb(utf_chars_path.c_str(), utf_chars_value.c_str());
}

bool com_android_providers_media_FuseDaemon_is_fuse_thread(JNIEnv* env, jclass clazz) {
    return pthread_getspecific(fuse::MediaProviderWrapper::gJniEnvKey) != nullptr;
}

jobjectArray com_android_providers_media_FuseDaemon_read_backed_up_file_paths(
        JNIEnv* env, jobject self, jlong java_daemon, jstring volumeName, jstring lastReadValue,
        jint limit) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    ScopedUtfChars utf_chars_volumeName(env, volumeName);
    ScopedUtfChars utf_chars_lastReadValue(env, lastReadValue);
    if (!utf_chars_volumeName.c_str()) {
        LOG(WARNING) << "Couldn't initialise FUSE device id";
        return nullptr;
    }
    return convert_string_vector_to_object_array(
            env, daemon->ReadFilePathsFromLevelDb(utf_chars_volumeName.c_str(),
                                                  utf_chars_lastReadValue.c_str(), limit));
}

jstring com_android_providers_media_FuseDaemon_read_backed_up_data(JNIEnv* env, jobject self,
                                                                   jlong java_daemon,
                                                                   jstring java_path) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    ScopedUtfChars utf_chars_path(env, java_path);
    if (!utf_chars_path.c_str()) {
        LOG(WARNING) << "Couldn't initialise FUSE device id";
        return nullptr;
    }
    return env->NewStringUTF(daemon->ReadBackedUpDataFromLevelDb(utf_chars_path.c_str()).c_str());
}

jstring com_android_providers_media_FuseDaemon_read_ownership(JNIEnv* env, jobject self,
                                                              jlong java_daemon, jstring key) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    ScopedUtfChars utf_chars_key(env, key);
    return env->NewStringUTF(daemon->ReadOwnership(utf_chars_key.c_str()).c_str());
}

void com_android_providers_media_FuseDaemon_create_owner_id_relation(JNIEnv* env, jobject self,
                                                                     jlong java_daemon,
                                                                     jstring owner_id,
                                                                     jstring owner_pkg_identifier) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    ScopedUtfChars utf_chars_owner_id(env, owner_id);
    ScopedUtfChars utf_chars_owner_pkg_identifier(env, owner_pkg_identifier);
    daemon->CreateOwnerIdRelation(utf_chars_owner_id.c_str(),
                                  utf_chars_owner_pkg_identifier.c_str());
}

jobject com_android_providers_media_FuseDaemon_read_owner_relations(JNIEnv* env, jobject self,
                                                                    jlong java_daemon) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    // Create a Java map object.
    jobject map =
            env->NewObject(env->FindClass("java/util/HashMap"),
                           env->GetMethodID(env->FindClass("java/util/HashMap"), "<init>", "()V"));

    // Get the key-value pairs from the native method.
    std::map<std::string, std::string> myMap = daemon->GetOwnerRelationship();

    // Iterate over the map and add the key-value pairs to the Java map.
    for (auto it = myMap.begin(); it != myMap.end(); ++it) {
        env->CallObjectMethod(
                map,
                env->GetMethodID(env->FindClass("java/util/HashMap"), "put",
                                 "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
                env->NewStringUTF(it->first.c_str()), env->NewStringUTF(it->second.c_str()));
    }

    // Return the Java map object.
    return map;
}

void com_android_providers_media_FuseDaemon_remove_owner_id_relation(JNIEnv* env, jobject self,
                                                                     jlong java_daemon,
                                                                     jstring owner_id,
                                                                     jstring owner_pkg_identifier) {
    fuse::FuseDaemon* const daemon = reinterpret_cast<fuse::FuseDaemon*>(java_daemon);
    ScopedUtfChars utf_chars_owner_id(env, owner_id);
    ScopedUtfChars utf_chars_owner_pkg_identifier(env, owner_pkg_identifier);
    daemon->RemoveOwnerIdRelation(utf_chars_owner_id.c_str(),
                                  utf_chars_owner_pkg_identifier.c_str());
}

const JNINativeMethod methods[] = {
        {"native_new", "(Lcom/android/providers/media/MediaProvider;)J",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_new)},
        {"native_start", "(JILjava/lang/String;Z[Ljava/lang/String;[Ljava/lang/String;)V",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_start)},
        {"native_delete", "(J)V",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_delete)},
        {"native_should_open_with_fuse", "(JLjava/lang/String;ZI)Z",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_should_open_with_fuse)},
        {"native_uses_fuse_passthrough", "(J)Z",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_uses_fuse_passthrough)},
        {"native_is_fuse_thread", "()Z",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_is_fuse_thread)},
        {"native_is_started", "(J)Z",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_is_started)},
        {"native_invalidate_fuse_dentry_cache", "(JLjava/lang/String;)V",
         reinterpret_cast<void*>(
                 com_android_providers_media_FuseDaemon_invalidate_fuse_dentry_cache)},
        {"native_check_fd_access", "(JII)Lcom/android/providers/media/FdAccessResult;",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_check_fd_access)},
        {"native_initialize_device_id", "(JLjava/lang/String;)V",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_initialize_device_id)},
        {"native_setup_volume_db_backup", "(J)V",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_setup_volume_db_backup)},
        {"native_delete_db_backup", "(JLjava/lang/String;)V",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_delete_db_backup)},
        {"native_backup_volume_db_data", "(JLjava/lang/String;Ljava/lang/String;)V",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_backup_volume_db_data)},
        {"native_read_backed_up_file_paths",
         "(JLjava/lang/String;Ljava/lang/String;I)[Ljava/lang/String;",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_read_backed_up_file_paths)},
        {"native_read_backed_up_data", "(JLjava/lang/String;)Ljava/lang/String;",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_read_backed_up_data)},
        {"native_read_ownership", "(JLjava/lang/String;)Ljava/lang/String;",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_read_ownership)},
        {"native_create_owner_id_relation", "(JLjava/lang/String;Ljava/lang/String;)V",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_create_owner_id_relation)},
        {"native_read_owner_relations", "(J)Ljava/util/HashMap;",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_read_owner_relations)},
        {"native_remove_owner_id_relation", "(JLjava/lang/String;Ljava/lang/String;)V",
         reinterpret_cast<void*>(com_android_providers_media_FuseDaemon_remove_owner_id_relation)}};
}  // namespace

void register_android_providers_media_FuseDaemon(JavaVM* vm, JNIEnv* env) {
    gFuseDaemonClass =
            static_cast<jclass>(env->NewGlobalRef(env->FindClass(FUSE_DAEMON_CLASS_NAME)));
    gFdAccessResultClass =
            static_cast<jclass>(env->NewGlobalRef(env->FindClass(FD_ACCESS_RESULT_CLASS_NAME)));

    if (gFuseDaemonClass == nullptr) {
        LOG(FATAL) << "Unable to find class : " << FUSE_DAEMON_CLASS_NAME;
    }

    if (gFdAccessResultClass == nullptr) {
        LOG(FATAL) << "Unable to find class : " << FD_ACCESS_RESULT_CLASS_NAME;
    }

    if (env->RegisterNatives(gFuseDaemonClass, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        LOG(FATAL) << "Unable to register native methods";
    }

    gFdAccessResultCtor = env->GetMethodID(gFdAccessResultClass, "<init>", "(Ljava/lang/String;Z)V");
    if (gFdAccessResultCtor == nullptr) {
        LOG(FATAL) << "Unable to find ctor for FdAccessResult";
    }

    fuse::MediaProviderWrapper::OneTimeInit(vm);
}
}  // namespace mediaprovider
