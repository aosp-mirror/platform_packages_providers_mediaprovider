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

#include "byte_value.h"

#include <android-base/logging.h>

#include <vector>

#include "fpdfview.h"

namespace pdfClient_utils {
namespace {
constexpr size_t kBufferSize = 128;
}  // namespace

template <class T>
void GetBytes(std::vector<char>* result, const std::function<size_t(T*, size_t)>& f) {
    result->resize(kBufferSize);
    size_t result_bytes = f(reinterpret_cast<T*>(result->data()), kBufferSize);

    result->resize(result_bytes);

    if (result_bytes > kBufferSize) {
        size_t result_bytes_2 = f(reinterpret_cast<T*>(result->data()), result_bytes);
        DCHECK_EQ(result_bytes, result_bytes_2) << "Pdfium function called with "
                                                   "the same arguments returned a "
                                                   "value that's a different size.";
    }
}

// Explicitly instantiate all specializations here:
template void GetBytes<void>(std::vector<char>* result,
                             const std::function<size_t(void*, size_t)>& f);
template void GetBytes<FPDF_WCHAR>(std::vector<char>* result,
                                   const std::function<size_t(FPDF_WCHAR*, size_t)>& f);

}  // namespace pdfClient_utils