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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_NORMALIZE_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_NORMALIZE_H_

#include <string>

namespace pdfClient {

// Returns the codepoint that is representative of the group that this codepoint
// belongs to, for case-insensitive and accent-insensitive searching.
// For example, 'a' is returned for 'a', 'A', 'ä', 'Ä' and other 'a' variants.
char32_t NormalizeForSearch(char32_t codepoint);

// Normalize the entire string for case/accent-insensitive searching.
void NormalizeStringForSearch(std::u32string* search_str);

// Whether this character can be ignored when searching for matches.
// For example, the '\x2' character can be skipped because it is used to
// indicate that a word has been broken over two lines. Spaces can be skipped
// if they are repeated, so that "  " is equivalent to " ".
bool IsSkippableForSearch(char32_t codepoint, char32_t prev_codepoint);

// Whether this character is used by pdfClient to indicate the start of a new line.
bool IsLineBreak(char32_t codepoint);

// Holding down on some text selects a single word, and these characters
// are considered to separate words for this purpose. Not very rigorous.
bool IsWordBreak(char32_t codepoint);

// Append the given codepoint that came from pdfClient to the string as UTF-8.
// pdfClient gives certain codepoints special meaning eg '\x2' for broken word, so
// these codepoints are not appended verbatim.
void AppendpdfClientCodepointAsUtf8(char32_t codepoint, std::string* output);

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_NORMALIZE_H_