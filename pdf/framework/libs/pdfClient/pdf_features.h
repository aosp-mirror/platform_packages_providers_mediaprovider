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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_PDF_FEATURES_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_PDF_FEATURES_H_

#include <stdint.h>

#include "fpdfview.h"

namespace pdfClient {

class Feature {
  public:
    enum e {
        // A free-text field in a form:
        FORM_TEXT_FIELD = 0x001,
        // A button, checkbox, or radio-button in a form:
        FORM_BUTTON = 0x002,
        // A dropdown menu in a form.
        FORM_CHOICE = 0x004,
        // A signature field in a form.
        FORM_SIGNATURE = 0x008,

        // Text annotation that is fixed to the PDF page.
        ANNOTATION_FIXED_TEXT = 0x010,
        // Text annotation that pops out - like a Google Docs comment.
        ANNOTATION_POPUP_TEXT = 0x020,
        // Markup on existing PDF text - eg highlight or underline.
        ANNOTATION_MARKUP = 0x040,
        // A shape annotation that is drawn on the PDF, eg a line or circle.
        ANNOTATION_SHAPE = 0x080,

        // Any kind of hyperlink - could link to another page, or a web URL.
        LINK = 0x100,
        // An embedded file, movie or sound.
        MULTIMEDIA = 0x200,

        // Next field: 0x400
        // This enum can be reordered, but must be kept in sync with:
        // corresponding Feature.java class
    };
};

// Returns the bit-wise or of all of the features found on this page -
// for instance, this could return: (FORM_TEXT_FIELD | FORM_BUTTON | LINK).
// Returns 0 if no features are found.
int GetFeatures(FPDF_PAGE page);

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_PDF_FEATURES_H_