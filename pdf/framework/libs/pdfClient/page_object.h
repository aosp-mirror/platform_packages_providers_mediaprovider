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

#include <vector>

#include "cpp/fpdf_scopers.h"
#include "fpdfview.h"

typedef unsigned int uint;

namespace pdfClient {

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

struct Matrix {
    float a;
    float b;
    float c;
    float d;
    float e;
    float f;
};

class PageObject {
  public:
    enum class Type {
        Unknown = 0,
        Path = 2,
        Image = 3,
    };

    Type GetType() const;
    // Returns a FPDF Instance for a PageObject.
    virtual ScopedFPDFPageObject CreateFPDFInstance(FPDF_DOCUMENT document) = 0;
    // Updates the FPDF Instance of PageObject present on Page.
    virtual bool UpdateFPDFInstance(FPDF_PAGEOBJECT page_object) = 0;
    // Populates data from FPDFInstance of PageObject present on Page.
    virtual bool PopulateFromFPDFInstance(FPDF_PAGEOBJECT page_object) = 0;

    virtual ~PageObject();

    Matrix matrix;  // Matrix used to scale, rotate, shear and translate the page object.
    Color fill_color;
    Color stroke_color;
    float stroke_width = 1.0f;

  protected:
    PageObject(Type type = Type::Unknown);

  private:
    Type type;
};

class PathObject : public PageObject {
  public:
    PathObject();

    ScopedFPDFPageObject CreateFPDFInstance(FPDF_DOCUMENT document) override;
    bool UpdateFPDFInstance(FPDF_PAGEOBJECT path_object) override;
    bool PopulateFromFPDFInstance(FPDF_PAGEOBJECT path_object) override;

    ~PathObject();

    class Segment {
      public:
        enum class Command {
            Unknown = 0,
            Move,
            Line,
        };

        Command command;
        float x;
        float y;
        bool is_closed;  // Checks if the path_segment is closed

        Segment(Command command, float x, float y, bool is_closed = false)
            : command(command), x(x), y(y), is_closed(is_closed) {}
    };

    bool is_fill_mode = true;
    bool is_stroke = false;

    std::vector<Segment> segments;
};

class ImageObject : public PageObject {
  public:
    ImageObject();

    ScopedFPDFPageObject CreateFPDFInstance(FPDF_DOCUMENT document) override;
    bool UpdateFPDFInstance(FPDF_PAGEOBJECT image_object) override;
    bool PopulateFromFPDFInstance(FPDF_PAGEOBJECT image_object) override;

    void* GetBitmapReadableBuffer() const;

    ~ImageObject();

    int width;
    int height;
    ScopedFPDFBitmap bitmap;
};

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_PAGE_OBJECT_H_