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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_TEXT_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_TEXT_H_

#include <map>
#include <string>
#include <unordered_map>
#include <vector>

#include "fpdf_structtree.h"
#include "fpdfview.h"

namespace pdfClient_utils {
std::string FPDF_StructElement_GetAltText(FPDF_STRUCTELEMENT elem);
std::string FPDFAnnot_GetStringValue(FPDF_ANNOTATION annot, FPDF_BYTESTRING key);
std::string FPDFAnnot_GetOptionLabel(FPDF_FORMHANDLE hHandle, FPDF_ANNOTATION annot, int index);
std::string FORM_GetFocusedText(FPDF_FORMHANDLE hHandle, FPDF_PAGE page);

// Extracts non-empty instances of alt text on |page| to put in |result|.
void GetAltText(const FPDF_PAGE page, std::vector<std::string>* result);

// Extracts non-empty instances of alt text on |page| to put in |result|, keyed
// by marked content ID. Does not preserve order and drops duplicate IDs.
void GetAltText(const FPDF_PAGE page, std::unordered_map<int, std::string>* result);
}  // namespace pdfClient_utils
#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_TEXT_H_