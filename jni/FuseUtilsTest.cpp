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

#define LOG_TAG "FuseUtilsTest"

#include "libfuse_jni/FuseUtils.h"

#include <gtest/gtest.h>

namespace mediaprovider::fuse {

TEST(FuseUtilsTest, testContainsMount_isTrueForAndroidDataObb) {
    EXPECT_TRUE(containsMount("/storage/emulated/1234/Android"));
    EXPECT_TRUE(containsMount("/storage/emulated/5678/Android"));
    EXPECT_TRUE(containsMount("/storage/emulated/1234/Android/data"));
    EXPECT_TRUE(containsMount("/storage/emulated/5678/Android/obb"));
    EXPECT_TRUE(containsMount("/storage/emulated/1234/Android/obb"));
    EXPECT_TRUE(containsMount("/storage/emulated/5678/Android/obb"));
}

TEST(FuseUtilsTest, testContainsMount) {
    EXPECT_FALSE(containsMount("/random/path"));
    EXPECT_FALSE(containsMount("/storage/abc-123"));
    EXPECT_FALSE(containsMount("/storage/emulated/1234/Android/data/and/more"));
    EXPECT_FALSE(containsMount("/storage/emulated"));
    EXPECT_FALSE(containsMount("/storage/emulated/"));
    EXPECT_FALSE(containsMount("/storage/emulated//"));
    EXPECT_FALSE(containsMount("/storage/emulated/0/"));
}

TEST(FuseUtilsTest, testContainsMount_isCaseInsensitive) {
    EXPECT_TRUE(containsMount("/storage/emulated/1234/android"));
    EXPECT_TRUE(containsMount("/storage/emulated/1234/Android/Data"));
    EXPECT_TRUE(containsMount("/storage/emulated/1234/ANDroid/dATa"));
    EXPECT_TRUE(containsMount("/storage/emulated/1234/ANDROID/OBB"));
    EXPECT_TRUE(containsMount("/Storage/EMULATED/1234/Android/obb"));
}

TEST(FuseUtilsTest, testContainsMount_isFalseForPathWithAdditionalSlash) {
    EXPECT_FALSE(containsMount("/storage/emulated/1234/Android/"));
    EXPECT_FALSE(containsMount("/storage/emulated/1234/Android/data/"));
    EXPECT_FALSE(containsMount("/storage/emulated/1234/Android/obb/"));

    EXPECT_FALSE(containsMount("//storage/emulated/1234/Android"));
    EXPECT_FALSE(containsMount("/storage/emulated//1234/Android/data"));
    EXPECT_FALSE(containsMount("/storage/emulated/1234//Android/data"));
}

}  // namespace mediaprovider::fuse
