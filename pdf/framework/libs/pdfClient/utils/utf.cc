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

#include <android-base/logging.h>

#include <algorithm>
#include <iterator>
#include <string>
#include <vector>

#include "../unchecked.h"
#include "byte_value.h"
#include "fpdfview.h"

namespace pdfClient_utils {
namespace {
typedef char16_t value_t;
constexpr size_t kValueBytes = sizeof(value_t);
constexpr value_t kLeadSurrogateMin = 0xD800u;
constexpr value_t kLeadSurrogateMax = 0xDBFFu;

bool IsLeadingSurrogate(value_t code_point) {
    return code_point >= kLeadSurrogateMin && code_point <= kLeadSurrogateMax;
}
}  // namespace

template <class T>
std::string GetUtf8Result(const std::function<size_t(T*, size_t)>& f) {
    std::vector<char> buffer;
    GetBytes<T>(&buffer, f);

    size_t result_size = buffer.size();
    DCHECK_EQ(result_size % kValueBytes, 0)
            << "Pdfium function should always return an even number of bytes.";

    value_t* start = reinterpret_cast<value_t*>(buffer.data());
    value_t* end = reinterpret_cast<value_t*>(buffer.data() + result_size);
    // Remove null terminators if there are any.
    while (start != end && *(end - 1) == 0) --end;

    // If the last UTF-16 character is a leading surrogate, UTF8-CPP will fail to
    // properly check the boundary and go off the end of the buffer. Since leading
    // surrogates not followed by a trailing surrogate are invalid UTF-16 anyway,
    // just remove them.
    while (start != end && IsLeadingSurrogate(*(end - 1))) {
        --end;
    }

    std::string result;
    pdfClient::unchecked::utf16to8(start, end, std::back_inserter(result));
    return result;
}

// Instantiate all known template specializations
template std::string GetUtf8Result<void>(const std::function<size_t(void*, size_t)>& f);
template std::string GetUtf8Result<FPDF_WCHAR>(const std::function<size_t(FPDF_WCHAR*, size_t)>& f);

std::u16string Utf8ToUtf16Le(std::string_view utf8) {
    std::u16string result;
    pdfClient::unchecked::utf8to16(utf8.begin(), utf8.end(), std::back_inserter(result));
#ifdef IS_BIG_ENDIAN
    // Convert from big-endian to little-endian.
    std::transform(result.begin(), result.end(), result.begin(), &LittleEndian::FromHost16);
#endif
    return result;
}

}  // namespace pdfClient_utils