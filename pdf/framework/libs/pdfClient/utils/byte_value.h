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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_BYTE_VALUE_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_BYTE_VALUE_H_

#include <functional>
#include <vector>

namespace pdfClient_utils {
// Wrapper around a Pdfium function to make it easier to get a bytestring.
// Many Pdfium functions have the following form:
// size_t FPDF_GetFooString(other_args ..., void* buffer, size_t buffer_len);
// These functions return the number of bytes in the result, regardless of the
// value of buffer_len. If buffer_len >= the number of bytes in the result,
// buffer is also filled in with the result value.
//
// GetBytes accepts a |result| vector, where the result will be stored, and a
// std::function that accepts a void* buffer and size_t buffer_len (with any
// other arguments being pre-bound using, e.g., BindFront, a lambda, or a
// wrapper).
// |result| will be filled in with the bytes returned, and be resized to match
// the size of the returned bytestring.
// Any data already in |result| will be overridden or deleted.
template <class T>
void GetBytes(std::vector<char>* result, const std::function<size_t(T*, size_t)>& f);
}  // namespace pdfClient_utils
#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_BYTE_VALUE_H_