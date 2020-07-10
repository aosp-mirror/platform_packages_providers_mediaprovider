// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#define LOG_TAG "FuseUtils"

#include "include/libfuse_jni/FuseUtils.h"

#include <string>
#include <vector>

#include "android-base/strings.h"

using std::string;

namespace mediaprovider {
namespace fuse {

bool containsMount(const string& path, const string& userid) {
    // This method is called from lookup, so it's called rather frequently.
    // Hence, we avoid concatenating the strings and we use 3 separate suffixes.

    static const string prefix = "/storage/emulated/";
    if (!android::base::StartsWithIgnoreCase(path, prefix)) {
        return false;
    }

    const string& rest_of_path = path.substr(prefix.length());
    if (!android::base::StartsWithIgnoreCase(rest_of_path, userid)) {
        return false;
    }

    static const string android_suffix = "/Android";
    static const string data_suffix = "/Android/data";
    static const string obb_suffix = "/Android/obb";

    const string& path_suffix = rest_of_path.substr(userid.length());
    return android::base::EqualsIgnoreCase(path_suffix, android_suffix) ||
           android::base::EqualsIgnoreCase(path_suffix, data_suffix) ||
           android::base::EqualsIgnoreCase(path_suffix, obb_suffix);
}

}  // namespace fuse
}  // namespace mediaprovider
