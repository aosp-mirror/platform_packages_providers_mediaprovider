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

#include "annotation.h"

#include "logging.h"

#define LOG_TAG "annotation"

namespace pdfClient {

std::vector<PageObject*> StampAnnotation::GetObjects() const {
    std::vector<PageObject*> page_objects;
    for (const auto& page_object : pageObjects_) {
        page_objects.push_back(page_object.get());
    }

    return page_objects;
}

bool StampAnnotation::PopulateFromPdfiumInstance(FPDF_ANNOTATION fpdf_annot) {
    int num_of_objects = FPDFAnnot_GetObjectCount(fpdf_annot);

    for (int object_index = 0; object_index < num_of_objects; object_index++) {
        FPDF_PAGEOBJECT page_object = FPDFAnnot_GetObject(fpdf_annot, object_index);
        int objectType = FPDFPageObj_GetType(page_object);

        std::unique_ptr<PageObject> page_object_;

        switch (objectType) {
            case FPDF_PAGEOBJ_PATH: {
                page_object_ = std::make_unique<PathObject>();
                break;
            }
            case FPDF_PAGEOBJ_IMAGE: {
                page_object_ = std::make_unique<ImageObject>();
                break;
            }
            default: {
                break;
            }
        }

        if (page_object_ && !page_object_->PopulateFromFPDFInstance(page_object)) {
            LOGE("Failed to get all the data corresponding to object with index "
                 "%d ",
                 object_index);
            page_object_ = nullptr;
        }

        // Add the page_object_ to the stamp annotation even if page_object_ is null
        // as we are storing empty unique ptr for the unsupported page objects
        AddObject(std::move(page_object_));
    }
    return true;
}

ScopedFPDFAnnotation StampAnnotation::CreatePdfiumInstance(FPDF_DOCUMENT document, FPDF_PAGE page) {
    // Create a ScopedFPDFAnnotation, If it will fail to populate this pdfium annot with desired
    // params, we will return null that will lead to scoped annot getting out of scope and thus
    // getting destroyed
    ScopedFPDFAnnotation scoped_annot =
            ScopedFPDFAnnotation(FPDFPage_CreateAnnot(page, FPDF_ANNOT_STAMP));

    if (!scoped_annot) {
        LOGE("Failed to create stamp Annotation.");
        return nullptr;
    }

    Rectangle_f annotation_bounds = GetBounds();
    FS_RECTF rect;
    rect.left = annotation_bounds.left;
    rect.bottom = annotation_bounds.bottom;
    rect.right = annotation_bounds.right;
    rect.top = annotation_bounds.top;

    if (!FPDFAnnot_SetRect(scoped_annot.get(), &rect)) {
        LOGE("Stamp Annotation bounds couldn't be set");
        return nullptr;
    }

    std::vector<PageObject*> pageObjects = GetObjects();
    for (auto pageObject : pageObjects) {
        ScopedFPDFPageObject scoped_page_object = pageObject->CreateFPDFInstance(document);

        if (!scoped_page_object) {
            LOGE("Failed to create page object to add in the stamp annotation");
            return nullptr;
        }

        if (!FPDFAnnot_AppendObject(scoped_annot.get(), scoped_page_object.release())) {
            LOGE("Page object couldn't be inserted in the stamp annotation");
            return nullptr;
        }
    }

    return scoped_annot;
}

bool StampAnnotation::UpdatePdfiumInstance(FPDF_ANNOTATION fpdf_annot, FPDF_DOCUMENT document) {
    if (FPDFAnnot_GetSubtype(fpdf_annot) != FPDF_ANNOT_STAMP) {
        LOGE("Unsupported operation - can't update a stamp annotation with some other type of "
             "annotation");
        return false;
    }

    Rectangle_f new_bounds = GetBounds();
    FS_RECTF rect;
    rect.left = new_bounds.left;
    rect.bottom = new_bounds.bottom;
    rect.right = new_bounds.right;
    rect.top = new_bounds.top;
    if (!FPDFAnnot_SetRect(fpdf_annot, &rect)) {
        LOGE("Failed to update the bounds of the stamp annotation at given index");
        return false;
    }

    // First Remove all the known existing objects from the stamp annotation, and then rewrite
    int num_objects = FPDFAnnot_GetObjectCount(fpdf_annot);
    for (int object_index = 0; object_index < num_objects; object_index++) {
        FPDF_PAGEOBJECT pageObject = FPDFAnnot_GetObject(fpdf_annot, object_index);
        int object_type = FPDFPageObj_GetType(pageObject);
        if (pageObject != nullptr &&
            (object_type == FPDF_PAGEOBJ_IMAGE || object_type == FPDF_PAGEOBJ_PATH)) {
            if (!FPDFAnnot_RemoveObject(fpdf_annot, object_index)) {
                LOGE("Failed to remove existing object from stamp annotation");
                return false;
            }
        }
    }

    // Rewrite
    std::vector<PageObject*> newPageObjects = GetObjects();
    for (auto pageObject : newPageObjects) {
        ScopedFPDFPageObject scoped_page_object = pageObject->CreateFPDFInstance(document);

        if (!scoped_page_object) {
            LOGE("Failed to create new page object to add in the stamp annotation");
            return false;
        }

        if (!FPDFAnnot_AppendObject(fpdf_annot, scoped_page_object.release())) {
            LOGE("Page object couldn't be inserted in the stamp annotation");
            return false;
        }
    }
    return true;
}

bool HighlightAnnotation::PopulateFromPdfiumInstance(FPDF_ANNOTATION fpdf_annot) {
    // Get color
    unsigned int R;
    unsigned int G;
    unsigned int B;
    unsigned int A;

    if (!FPDFAnnot_GetColor(fpdf_annot, FPDFANNOT_COLORTYPE_Color, &R, &G, &B, &A)) {
        LOGE("Couldn't get color of highlight annotation");
        return false;
    }

    Color color(R, G, B, A);
    this->SetColor(color);
    return true;
}

ScopedFPDFAnnotation HighlightAnnotation::CreatePdfiumInstance(FPDF_DOCUMENT document,
                                                               FPDF_PAGE page) {
    ScopedFPDFAnnotation scoped_annot =
            ScopedFPDFAnnotation(FPDFPage_CreateAnnot(page, FPDF_ANNOT_HIGHLIGHT));

    if (!scoped_annot) {
        LOGE("Failed to create highlight Annotation.");
        return nullptr;
    }

    if (!this->UpdatePdfiumInstance(scoped_annot.get(), document)) {
        LOGE("Failed to create highlight annotation with given parameters");
    }

    return scoped_annot;
}

bool HighlightAnnotation::UpdatePdfiumInstance(FPDF_ANNOTATION fpdf_annot, FPDF_DOCUMENT document) {
    if (FPDFAnnot_GetSubtype(fpdf_annot) != FPDF_ANNOT_HIGHLIGHT) {
        LOGE("Unsupported operation - can't update a highlight annotation with some other type of "
             "annotation");
        return false;
    }

    Rectangle_f annotation_bounds = this->GetBounds();
    FS_RECTF rect;
    rect.left = annotation_bounds.left;
    rect.bottom = annotation_bounds.bottom;
    rect.right = annotation_bounds.right;
    rect.top = annotation_bounds.top;

    if (!FPDFAnnot_SetRect(fpdf_annot, &rect)) {
        LOGE("Highlight Annotation bounds couldn't be updated");
        return false;
    }

    Color new_color = this->GetColor();
    if (!FPDFAnnot_SetColor(fpdf_annot, FPDFANNOT_COLORTYPE_Color, new_color.r, new_color.g,
                            new_color.b, new_color.a)) {
        LOGE("Highlight Annotation color couldn't be updated");
        return false;
    }
    return true;
}

}  // namespace pdfClient