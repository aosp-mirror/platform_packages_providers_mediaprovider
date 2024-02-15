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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_ANNOT_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_ANNOT_H_

#include <span>
#include <unordered_set>
#include <vector>

#include "cpp/fpdf_scopers.h"
#include "fpdfview.h"

namespace pdfClient_utils {

// Gets all annotations of the types in |types| on |page| and stores them in
// |annots|. See external/pdfium/public/fpdf_annot.h for type definitions.
void GetVisibleAnnotsOfType(FPDF_PAGE page, const std::unordered_set<int>& types,
                            std::vector<ScopedFPDFAnnotation>* annots);

// Adds the hidden flag to each of the annotations in |annots|.
void HideAnnots(std::span<const ScopedFPDFAnnotation> annots);

// Removes the hidden flag from each of the annotations in |annots|.
void UnhideAnnots(std::span<const ScopedFPDFAnnotation> annots);

}  // namespace pdfClient_utils

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_ANNOT_H_