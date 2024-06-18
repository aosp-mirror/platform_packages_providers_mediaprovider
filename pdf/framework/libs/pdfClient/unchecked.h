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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_LIBS_UTF_H
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_LIBS_UTF_H

#include "core.h"

namespace pdfClient {
namespace unchecked {
template <typename octet_iterator>
octet_iterator append(uint32_t cp, octet_iterator result) {
    if (cp < 0x80)  // one octet
        *(result++) = static_cast<uint8_t>(cp);
    else if (cp < 0x800) {  // two octets
        *(result++) = static_cast<uint8_t>((cp >> 6) | 0xc0);
        *(result++) = static_cast<uint8_t>((cp & 0x3f) | 0x80);
    } else if (cp < 0x10000) {  // three octets
        *(result++) = static_cast<uint8_t>((cp >> 12) | 0xe0);
        *(result++) = static_cast<uint8_t>(((cp >> 6) & 0x3f) | 0x80);
        *(result++) = static_cast<uint8_t>((cp & 0x3f) | 0x80);
    } else {  // four octets
        *(result++) = static_cast<uint8_t>((cp >> 18) | 0xf0);
        *(result++) = static_cast<uint8_t>(((cp >> 12) & 0x3f) | 0x80);
        *(result++) = static_cast<uint8_t>(((cp >> 6) & 0x3f) | 0x80);
        *(result++) = static_cast<uint8_t>((cp & 0x3f) | 0x80);
    }
    return result;
}

template <typename octet_iterator>
uint32_t next(octet_iterator& it) {
    uint32_t cp = utf8::mask8(*it);
    typename std::iterator_traits<octet_iterator>::difference_type length =
            utf8::sequence_length(it);
    switch (length) {
        case 1:
            break;
        case 2:
            it++;
            cp = ((cp << 6) & 0x7ff) + ((*it) & 0x3f);
            break;
        case 3:
            ++it;
            cp = ((cp << 12) & 0xffff) + ((utf8::mask8(*it) << 6) & 0xfff);
            ++it;
            cp += (*it) & 0x3f;
            break;
        case 4:
            ++it;
            cp = ((cp << 18) & 0x1fffff) + ((utf8::mask8(*it) << 12) & 0x3ffff);
            ++it;
            cp += (utf8::mask8(*it) << 6) & 0xfff;
            ++it;
            cp += (*it) & 0x3f;
            break;
    }
    ++it;
    return cp;
}

template <typename u16bit_iterator, typename octet_iterator>
octet_iterator utf16to8(u16bit_iterator start, u16bit_iterator end, octet_iterator result) {
    while (start != end) {
        uint32_t cp = utf8::mask16(*start++);
        // Take care of surrogate pairs first
        if (utf8::is_lead_surrogate(cp)) {
            uint32_t trail_surrogate = utf8::mask16(*start++);
            cp = (cp << 10) + trail_surrogate + utf8::SURROGATE_OFFSET;
        }
        result = unchecked::append(cp, result);
    }
    return result;
}

template <typename u16bit_iterator, typename octet_iterator>
u16bit_iterator utf8to16(octet_iterator start, octet_iterator end, u16bit_iterator result) {
    while (start < end) {
        uint32_t cp = next(start);
        if (cp > 0xffff) {  // make a surrogate pair
            *result++ = static_cast<uint16_t>((cp >> 10) + utf8::LEAD_OFFSET);
            *result++ = static_cast<uint16_t>((cp & 0x3ff) + utf8::TRAIL_SURROGATE_MIN);
        } else
            *result++ = static_cast<uint16_t>(cp);
    }
    return result;
}

template <typename octet_iterator, typename u32bit_iterator>
u32bit_iterator utf8to32(octet_iterator start, octet_iterator end, u32bit_iterator result) {
    while (start < end) (*result++) = next(start);

    return result;
}

}  // namespace unchecked
}  // namespace pdfClient

#endif  // header guard