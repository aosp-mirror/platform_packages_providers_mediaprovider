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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTF_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTF_H_

#include <string>

namespace std {
// Typedef std::u16string and std::u32string in case they are missing.
typedef std::basic_string<char16_t> u16string;
typedef std::basic_string<char32_t> u32string;
}  // namespace std

namespace pdfClient {

// Converts a string from UTF-8 to UTF-32.
std::u32string Utf8ToUtf32(std::string_view utf8);

// Converts a C-string from UTF-8 to UTF-32.
std::u32string Utf8ToUtf32(const char* utf8);

// Converts a string from UTF-16 to UTF-8.
std::string Utf16ToUtf8(const std::u16string& utf16);

// Converts an individual unicode codepoint to one or more UTF-8 chars,
// and appends them to the output string.
void AppendCodepointAsUtf8(const char32_t codepoint, std::string* output);

// If a C-string is copied directly into a string, it can end up with a trailing
// '\0' character. This trims it.
void EraseTrailingNulls(std::string* str);

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTF_H_