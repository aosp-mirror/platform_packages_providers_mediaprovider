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

#include "page_object.h"

#include <stddef.h>
#include <stdint.h>

#include "cpp/fpdf_scopers.h"
#include "fpdf_edit.h"
#include "fpdfview.h"
#include "logging.h"

#define LOG_TAG "page_object"

namespace pdfClient {

PageObject::PageObject(Type type) : type(type) {}

PageObject::Type PageObject::GetType() const {
    return type;
}

PageObject::~PageObject() = default;

PathObject::PathObject() : PageObject(Type::Path) {}

ScopedFPDFPageObject PathObject::CreateFPDFInstance(FPDF_DOCUMENT document) {
    int segment_count = segments.size();
    if (segment_count == 0) {
        return nullptr;
    }
    // Get Start Points
    float x = segments[0].x;
    float y = segments[0].y;

    // Create a scoped PDFium path object.
    ScopedFPDFPageObject scoped_path_object(FPDFPageObj_CreateNewPath(x, y));
    if (!scoped_path_object) {
        return nullptr;
    }

    // Insert all segments into PDFium Path Object
    for (int i = 1; i < segment_count; ++i) {
        // Get EndPoint for current segment.
        x = segments[i].x;
        y = segments[i].y;
        switch (segments[i].command) {
            case Segment::Command::Move: {
                FPDFPath_MoveTo(scoped_path_object.get(), x, y);
                break;
            }
            case Segment::Command::Line: {
                FPDFPath_LineTo(scoped_path_object.get(), x, y);
                break;
            }
            default:
                break;
        }
        if (segments[i].is_closed) {
            FPDFPath_Close(scoped_path_object.get());
        }
    }

    // Update attributes of PDFium path object.
    if (!UpdateFPDFInstance(scoped_path_object.get())) {
        return nullptr;
    }

    return scoped_path_object;
}

bool PathObject::UpdateFPDFInstance(FPDF_PAGEOBJECT path_object) {
    if (!path_object) {
        return false;
    }

    // Check for Type Correctness.
    if (FPDFPageObj_GetType(path_object) != FPDF_PAGEOBJ_PATH) {
        return false;
    }

    // Set the updated Draw Mode
    int fill_mode = this->is_fill_mode ? FPDF_FILLMODE_WINDING : FPDF_FILLMODE_NONE;
    if (!FPDFPath_SetDrawMode(path_object, fill_mode, is_stroke)) {
        return false;
    }

    // Set the updated matrix.
    if (!FPDFPageObj_SetMatrix(path_object, reinterpret_cast<FS_MATRIX*>(&matrix))) {
        return false;
    }

    // Set the updated Stroke Width
    FPDFPageObj_SetStrokeWidth(path_object, stroke_width);
    // Set the updated Stroke Color
    FPDFPageObj_SetStrokeColor(path_object, stroke_color.r, stroke_color.g, stroke_color.b,
                               stroke_color.a);
    // Set the updated Fill Color
    FPDFPageObj_SetFillColor(path_object, fill_color.r, fill_color.g, fill_color.b, fill_color.a);

    return true;
}

bool PathObject::PopulateFromFPDFInstance(FPDF_PAGEOBJECT path_object) {
    // Count the number of Segments in the Path
    int segment_count = FPDFPath_CountSegments(path_object);
    if (segment_count == 0) {
        return false;
    }

    // Get Path Segments
    for (int index = 0; index < segment_count; ++index) {
        FPDF_PATHSEGMENT path_segment = FPDFPath_GetPathSegment(path_object, index);

        // Get Type for the current Path Segment
        int type = FPDFPathSegment_GetType(path_segment);
        if (type == FPDF_SEGMENT_UNKNOWN || type == FPDF_SEGMENT_BEZIERTO) {
            // Control point extraction of bezier curve is not supported by Pdfium as of now.
            return false;
        }

        // Get EndPoint for the current Path Segment
        float x, y;
        FPDFPathSegment_GetPoint(path_segment, &x, &y);

        // Get Close for the current Path Segment
        bool is_closed = FPDFPathSegment_GetClose(path_segment);

        // Add Segment to PageObject Data
        if (type == FPDF_SEGMENT_LINETO) {
            segments.emplace_back(Segment::Command::Line, x, y, is_closed);
        } else {
            segments.emplace_back(Segment::Command::Move, x, y, is_closed);
        }
    }

    // Get Draw Mode
    int fill_mode;
    FPDF_BOOL stroke;
    if (!FPDFPath_GetDrawMode(path_object, &fill_mode, &stroke)) {
        LOGE("Path GetDrawMode Failed!");
        return false;
    }
    this->is_fill_mode = fill_mode;
    this->is_stroke = stroke;

    // Get Matrix
    if (!FPDFPageObj_GetMatrix(path_object, reinterpret_cast<FS_MATRIX*>(&matrix))) {
        return false;
    }

    // Get Fill Color
    FPDFPageObj_GetFillColor(path_object, &fill_color.r, &fill_color.g, &fill_color.b,
                             &fill_color.a);
    // Get Stroke Color
    FPDFPageObj_GetStrokeColor(path_object, &stroke_color.r, &stroke_color.g, &stroke_color.b,
                               &stroke_color.a);
    // Get Stroke Width
    FPDFPageObj_GetStrokeWidth(path_object, &stroke_width);

    return true;
}

PathObject::~PathObject() = default;

ImageObject::ImageObject() : PageObject(Type::Image) {}

ScopedFPDFPageObject ImageObject::CreateFPDFInstance(FPDF_DOCUMENT document) {
    // Create a scoped PDFium image object.
    ScopedFPDFPageObject scoped_image_object(FPDFPageObj_NewImageObj(document));
    if (!scoped_image_object) {
        return nullptr;
    }
    // Update attributes of PDFium image object.
    if (!UpdateFPDFInstance(scoped_image_object.get())) {
        return nullptr;
    }
    return scoped_image_object;
}

bool ImageObject::UpdateFPDFInstance(FPDF_PAGEOBJECT image_object) {
    if (!image_object) {
        return false;
    }

    // Check for Type Correctness.
    if (FPDFPageObj_GetType(image_object) != FPDF_PAGEOBJ_IMAGE) {
        return false;
    }

    // Set the updated bitmap.
    if (!FPDFImageObj_SetBitmap(nullptr, 0, image_object, bitmap.get())) {
        return false;
    }

    // Set the updated matrix.
    if (!FPDFPageObj_SetMatrix(image_object, reinterpret_cast<FS_MATRIX*>(&matrix))) {
        return false;
    }

    width = FPDFBitmap_GetWidth(bitmap.get());
    height = FPDFBitmap_GetHeight(bitmap.get());

    return true;
}

bool ImageObject::PopulateFromFPDFInstance(FPDF_PAGEOBJECT image_object) {
    // Get Bitmap
    bitmap = ScopedFPDFBitmap(FPDFImageObj_GetBitmap(image_object));
    if (bitmap.get() == nullptr) {
        return false;
    }

    // Get Matrix
    if (!FPDFPageObj_GetMatrix(image_object, reinterpret_cast<FS_MATRIX*>(&matrix))) {
        return false;
    }

    width = FPDFBitmap_GetWidth(bitmap.get());
    height = FPDFBitmap_GetHeight(bitmap.get());

    return true;
}

void* ImageObject::GetBitmapReadableBuffer() const {
    return FPDFBitmap_GetBuffer(bitmap.get());
}

ImageObject::~ImageObject() = default;

}  // namespace pdfClient