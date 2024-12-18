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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_PAGE_OBJECT_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_PAGE_OBJECT_H_

#include <stdint.h>

#include "fpdfview.h"

typedef unsigned int uint;

namespace pdfClient {

struct ImageObject;

struct Color {
    uint r;
    uint g;
    uint b;
    uint a;

    Color(uint r, uint g, uint b, uint a) : r(r), g(g), b(b), a(a) {}
    Color() : Color(INVALID_COLOR, INVALID_COLOR, INVALID_COLOR, INVALID_COLOR) {}

  private:
    static constexpr uint INVALID_COLOR = 256;
};

struct PageObject {
    enum class Type {
        Unknown = 0,
        Image,
    };

    PageObject(Type t = Type::Unknown) : type(t) {}

    // Returns a pointer to the ImageObject if the PageObject is of type Image.
    // Returns nullptr otherwise.
    virtual ImageObject* AsImage() { return nullptr; }

    Type GetType() { return type; }

    virtual ~PageObject() = default;

    FS_MATRIX matrix;  // Matrix used to scale, rotate, shear and translate the page object.
    Color fill_color;
    Color stroke_color;
    float stroke_width = 1.0f;

  private:
    Type type;
};

struct ImageObject : public PageObject {
    ImageObject() : PageObject(Type::Image) {}

    ImageObject* AsImage() override { return this; }

    ~ImageObject() { FPDFBitmap_Destroy(bitmap); }

    FPDF_BITMAP bitmap;
};

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_PAGE_OBJECT_H_