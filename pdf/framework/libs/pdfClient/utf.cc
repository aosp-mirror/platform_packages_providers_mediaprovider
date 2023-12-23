/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "utf.h"

#include <cstring>
#include <iterator>
#include <string>

#include "unchecked.h"

namespace pdfClient {

std::u32string Utf8ToUtf32(std::string_view utf8) {
    std::u32string result;
    unchecked::utf8to32(utf8.begin(), utf8.end(), std::back_inserter(result));
    return result;
}

std::u32string Utf8ToUtf32(const char* utf8) {
    std::u32string result;
    unchecked::utf8to32(utf8, utf8 + strlen(utf8), std::back_inserter(result));
    return result;
}

std::string Utf16ToUtf8(const std::u16string& utf16) {
    std::string result;
    unchecked::utf16to8(utf16.begin(), utf16.end(), std::back_inserter(result));
    return result;
}

void AppendCodepointAsUtf8(const char32_t codepoint, std::string* output) {
    unchecked::append(codepoint, std::back_inserter(*output));
}

void EraseTrailingNulls(std::string* str) {
    str->erase(str->find_last_not_of('\0') + 1);
}

}  // namespace pdfClient