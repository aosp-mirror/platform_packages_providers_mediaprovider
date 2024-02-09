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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTF8_H
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTF8_H

#include <iterator>

namespace pdfClient {
namespace utf8 {

const uint16_t LEAD_SURROGATE_MIN = 0xd800u;
const uint16_t LEAD_SURROGATE_MAX = 0xdbffu;
const uint16_t TRAIL_SURROGATE_MIN = 0xdc00u;
const uint16_t LEAD_OFFSET = LEAD_SURROGATE_MIN - (0x10000 >> 10);
const uint32_t SURROGATE_OFFSET = 0x10000u - (LEAD_SURROGATE_MIN << 10) - TRAIL_SURROGATE_MIN;

template <typename octet_type>
inline uint8_t mask8(octet_type oc) {
    return static_cast<uint8_t>(0xff & oc);
}

template <typename u16_type>
inline uint16_t mask16(u16_type oc) {
    return static_cast<uint16_t>(0xffff & oc);
}

template <typename u16>
inline bool is_lead_surrogate(u16 cp) {
    return (cp >= LEAD_SURROGATE_MIN && cp <= LEAD_SURROGATE_MAX);
}

template <typename octet_iterator>
inline typename std::iterator_traits<octet_iterator>::difference_type sequence_length(
        octet_iterator lead_it) {
    uint8_t lead = mask8(*lead_it);
    if (lead < 0x80)
        return 1;
    else if ((lead >> 5) == 0x6)
        return 2;
    else if ((lead >> 4) == 0xe)
        return 3;
    else if ((lead >> 3) == 0x1e)
        return 4;
    else
        return 0;
}

}  // namespace utf8
}  // namespace pdfClient

#endif  // header guard