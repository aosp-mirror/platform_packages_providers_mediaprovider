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

#include "text.h"

#include <string>
#include <vector>

#include "cpp/fpdf_scopers.h"
#include "fpdf_annot.h"
#include "fpdf_edit.h"
#include "fpdf_structtree.h"
#include "fpdfview.h"
#include "utf.h"
// #include "util/gtl/map_util.h" // @Todo(b/312339259) - find a way to uncomment it

namespace pdfClient_utils {

namespace {
// Maximum number of struct tree levels to recurse over.
constexpr int kRecursionLimit = 100;
}  // namespace

std::string FPDF_StructElement_GetAltText(FPDF_STRUCTELEMENT elem) {
    return GetUtf8Result<void>(std::bind(::FPDF_StructElement_GetAltText, elem,
                                         std::placeholders::_1, std::placeholders::_2));
}

std::string FPDFAnnot_GetStringValue(FPDF_ANNOTATION annot, FPDF_BYTESTRING key) {
    return GetUtf8Result<FPDF_WCHAR>(std::bind(::FPDFAnnot_GetStringValue, annot, key,
                                               std::placeholders::_1, std::placeholders::_2));
}

std::string FPDFAnnot_GetOptionLabel(FPDF_FORMHANDLE hHandle, FPDF_ANNOTATION annot, int index) {
    return GetUtf8Result<FPDF_WCHAR>(std::bind(::FPDFAnnot_GetOptionLabel, hHandle, annot, index,
                                               std::placeholders::_1, std::placeholders::_2));
}

std::string FORM_GetFocusedText(FPDF_FORMHANDLE hHandle, FPDF_PAGE page) {
    return GetUtf8Result<void>(std::bind(::FORM_GetFocusedText, hHandle, page,
                                         std::placeholders::_1, std::placeholders::_2));
}

// Extracts alt text from |elem| and puts it in the |result| vector if
// non-empty.
void GetAltTextFromElement(const FPDF_STRUCTELEMENT elem, std::vector<std::string>* result) {
    std::string alt = FPDF_StructElement_GetAltText(elem);
    if (!alt.empty()) {
        result->push_back(alt);
    }
}

// Extracts alt text from |elem| and puts it in the |result| map keyed by marked
// content ID if non-empty. Skips duplicate IDs.
void GetAltTextFromElement(const FPDF_STRUCTELEMENT elem,
                           std::unordered_map<int, std::string>* result) {
    std::string alt = FPDF_StructElement_GetAltText(elem);
    if (!alt.empty()) {
        int id = FPDF_StructElement_GetMarkedContentID(elem);
        //    if (!gtl::InsertIfNotPresent(result, id, alt)) {
        //      VLOG(2) << "Duplicate alt text marked content ID found! Ignoring.";
        //    } // @Todo(b/312339259)
    }
}

// Recursively traverses the element tree under |elem| and inserts alt text into
// |result|.
template <typename ResultType>
void GetAltTextFromElementTree(const FPDF_STRUCTELEMENT elem, int recursion_level,
                               ResultType* result) {
    GetAltTextFromElement(elem, result);

    if (recursion_level > kRecursionLimit) return;

    int num_children = FPDF_StructElement_CountChildren(elem);
    for (int i = 0; i < num_children; i++) {
        GetAltTextFromElementTree(FPDF_StructElement_GetChildAtIndex(elem, i), recursion_level + 1,
                                  result);
    }
}

// Extracts alt text from all child element trees in |page| and inserts into
// |result|.
template <typename ResultType>
void GetAltTextFromPage(const FPDF_PAGE page, ResultType* result) {
    ScopedFPDFStructTree tree(FPDF_StructTree_GetForPage(page));
    int num_children = FPDF_StructTree_CountChildren(tree.get());
    for (int i = 0; i < num_children; ++i) {
        GetAltTextFromElementTree(FPDF_StructTree_GetChildAtIndex(tree.get(), i), 0, result);
    }
}

void GetAltText(const FPDF_PAGE page, std::vector<std::string>* result) {
    GetAltTextFromPage(page, result);
}

void GetAltText(const FPDF_PAGE page, std::unordered_map<int, std::string>* result) {
    GetAltTextFromPage(page, result);
}

}  // namespace pdfClient_utils