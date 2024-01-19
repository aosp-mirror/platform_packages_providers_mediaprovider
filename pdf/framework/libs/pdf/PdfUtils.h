/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef PDF_UTILS_H_
#define PDF_UTILS_H_

#include <nativehelper/JNIHelp.h>

#include "jni.h"

namespace android {

static inline jclass FindClassOrDie(JNIEnv* env, const char* class_name) {
    jclass clazz = env->FindClass(class_name);
    return clazz;
}

static inline jfieldID GetFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name,
                                       const char* field_signature) {
    jfieldID res = env->GetFieldID(clazz, field_name, field_signature);
    return res;
}

static inline int RegisterMethodsOrDie(JNIEnv* env, const char* className,
                                       const JNINativeMethod* gMethods, int numMethods) {
    int res = jniRegisterNativeMethods(env, className, gMethods, numMethods);
    return res;
}

int getBlock(void* param, unsigned long position, unsigned char* outBuffer, unsigned long size);

jlong nativeOpen(JNIEnv* env, jclass thiz, jint fd, jlong size);
void nativeClose(JNIEnv* env, jclass thiz, jlong documentPtr);

jint nativeGetPageCount(JNIEnv* env, jclass thiz, jlong documentPtr);
jboolean nativeScaleForPrinting(JNIEnv* env, jclass thiz, jlong documentPtr);

};  // namespace android

#endif /* PDF_UTILS_H_ */
