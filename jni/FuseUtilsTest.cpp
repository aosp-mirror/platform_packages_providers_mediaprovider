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

using namespace mediaprovider::fuse;

TEST(FuseUtilsTest, testContainsMount_isTrueForAndroidDataObb) {
    EXPECT_TRUE(containsMount("/storage/emulated/1234/Android", "1234"));
    EXPECT_TRUE(containsMount("/storage/emulated/1234/Android/data", "1234"));
    EXPECT_TRUE(containsMount("/storage/emulated/1234/Android/obb", "1234"));
}

TEST(FuseUtilsTest, testContainsMount) {
    EXPECT_FALSE(containsMount("/random/path", "1234"));
    EXPECT_FALSE(containsMount("/storage/abc-123", "1234"));
    EXPECT_FALSE(containsMount("/storage/emulated/1234/Android/data/and/more", "1234"));
}

TEST(FuseUtilsTest, testContainsMount_isCaseInsensitive) {
    EXPECT_TRUE(containsMount("/storage/emulated/1234/android", "1234"));
    EXPECT_TRUE(containsMount("/storage/emulated/1234/Android/Data", "1234"));
    EXPECT_TRUE(containsMount("/storage/emulated/1234/ANDroid/dATa", "1234"));
    EXPECT_TRUE(containsMount("/storage/emulated/1234/ANDROID/OBB", "1234"));
    EXPECT_TRUE(containsMount("/Storage/EMULATED/1234/Android/obb", "1234"));
}

TEST(FuseUtilsTest, testContainsMount_isCaseInsensitiveForUserid) {
    EXPECT_TRUE(containsMount("/storage/emulated/UserId/Android", "UserId"));
    EXPECT_TRUE(containsMount("/storage/emulated/userid/Android/obb", "Userid"));
    EXPECT_TRUE(containsMount("/storage/emulated/Userid/Android/obb", "userid"));
}

TEST(FuseUtilsTest, testContainsMount_isFalseForPathWithAdditionalSlash) {
    EXPECT_FALSE(containsMount("/storage/emulated/1234/Android/", "1234"));
    EXPECT_FALSE(containsMount("/storage/emulated/1234/Android/data/", "1234"));
    EXPECT_FALSE(containsMount("/storage/emulated/1234/Android/obb/", "1234"));

    EXPECT_FALSE(containsMount("//storage/emulated/1234/Android", "1234"));
    EXPECT_FALSE(containsMount("/storage/emulated//1234/Android/data", "1234"));
    EXPECT_FALSE(containsMount("/storage/emulated/1234//Android/data", "1234"));
}

TEST(FuseUtilsTest, testContainsMount_isFalseForPathWithWrongUserid) {
    EXPECT_FALSE(containsMount("/storage/emulated/11234/Android", "1234"));
    EXPECT_FALSE(containsMount("/storage/emulated/0/Android/data", "1234"));
    EXPECT_FALSE(containsMount("/storage/emulated/12345/Android/obb", "1234"));
    EXPECT_FALSE(containsMount("/storage/emulated/1234/Android/obb", "5678"));
}
