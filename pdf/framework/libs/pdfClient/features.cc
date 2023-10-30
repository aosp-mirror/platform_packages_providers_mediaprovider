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

#include "features.h"

#include <stdint.h>

#include <string>

#include "cpp/fpdf_scopers.h"
#include "fpdf_annot.h"
#include "fpdfview.h"
#include "utils/text.h"

namespace pdfClient {

namespace {

constexpr char kFormTypeKey[] = "FT";

int32_t ClassifyFeature(FPDF_ANNOTATION annot) {
    FPDF_ANNOTATION_SUBTYPE subtype = FPDFAnnot_GetSubtype(annot);
    switch (subtype) {
        case FPDF_ANNOT_WIDGET: {
            // See pdf_reference_1-7.pdf section 8.6.2: Field Dictionaries.

            std::string form_type = pdfClient_utils::FPDFAnnot_GetStringValue(annot, kFormTypeKey);

            if (form_type == "Tx") {
                return Feature::FORM_TEXT_FIELD;
            }
            if (form_type == "Ch") {
                return Feature::FORM_CHOICE;
            }
            if (form_type == "Sig") {
                return Feature::FORM_SIGNATURE;
            }
            return Feature::FORM_BUTTON;
        }

        case FPDF_ANNOT_TEXT:
        case FPDF_ANNOT_FREETEXT:
            return Feature::ANNOTATION_FIXED_TEXT;

        case FPDF_ANNOT_POPUP:
        case FPDF_ANNOT_INK:
            return Feature::ANNOTATION_POPUP_TEXT;

        case FPDF_ANNOT_HIGHLIGHT:
        case FPDF_ANNOT_UNDERLINE:
        case FPDF_ANNOT_SQUIGGLY:
        case FPDF_ANNOT_STRIKEOUT:
            return Feature::ANNOTATION_MARKUP;

        case FPDF_ANNOT_LINE:
        case FPDF_ANNOT_SQUARE:
        case FPDF_ANNOT_CIRCLE:
        case FPDF_ANNOT_POLYGON:
        case FPDF_ANNOT_POLYLINE:
            return Feature::ANNOTATION_SHAPE;

        case FPDF_ANNOT_LINK:
            return Feature::LINK;

        case FPDF_ANNOT_FILEATTACHMENT:
        case FPDF_ANNOT_SOUND:
        case FPDF_ANNOT_MOVIE:
        case FPDF_ANNOT_THREED:
            return Feature::MULTIMEDIA;

        default:
            return 0;
    }
}

}  // namespace

int32_t GetFeatures(FPDF_PAGE page) {
    int32_t result = 0;
    int count = FPDFPage_GetAnnotCount(page);
    for (int i = 0; i < count; i++) {
        ScopedFPDFAnnotation annot(FPDFPage_GetAnnot(page, i));
        result |= ClassifyFeature(annot.get());
    }
    return result;
}

}  // namespace pdfClient