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

package android.graphics.pdf.component;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.graphics.RectF;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.utils.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a stamp annotation in a PDF document.
 * <p>
 * Only path, image, or text objects created using the {@link PdfPagePathObject},
 * {@link PdfPageImageObject}, or {@link PdfPageTextObject} constructors respectively
 * can be added to a stamp annotation.
 */
@FlaggedApi(Flags.FLAG_ENABLE_EDIT_PDF_STAMP_ANNOTATIONS)
public final class StampAnnotation extends PdfAnnotation {
    @NonNull private List<PdfPageObject> mObjects;

    /**
     * Creates a new stamp annotation with the specified bounds
     *
     * @param bounds The bounding rectangle of the annotation.
     */
    public StampAnnotation(@NonNull RectF bounds) {
        super(PdfAnnotationType.STAMP, bounds);
        mObjects = new ArrayList<>();
    }

    /**
     * Adds a PDF page object to the stamp annotation.
     * <p>
     * The page object should be a path, text or an image. The page object which has been
     * already added to a page can't be added to the annotation and one page object can be added
     * to one annotation only.
     * When the annotation will be added to the page using
     * @link android.graphics.pdf.PdfRenderer.Page#addPageAnnotation(PdfAnnotation)} or
     * {@link android.graphics.pdf.PdfRendererPreV.Page#addPageAnnotation(PdfAnnotation)}, the
     * page object will get assigned a unique id.
     *
     * @param pageObject The PDF page object to add.
     * @throws IllegalArgumentException if the page object is already added to a page or an
     *         annotation.
     */
    public void addObject(@NonNull PdfPageObject pageObject) {
        Preconditions.checkArgument(pageObject.getObjectId() == -1,
                "This page object is already added to the page");
        Preconditions.checkArgument(pageObject.isAddedInAnnotation(),
                "This page object is already added to an annotation");
        mObjects.add(pageObject);
        pageObject.setAddedInAnnotation();
    }


    /**
     * Returns all the known PDF page objects in the stamp annotation.
     *
     * @return The list of page objects in the annotation.
     */
    @NonNull
    public List<PdfPageObject> getObjects() {
        return mObjects;
    }

    /**
     * Remove the page object from the stamp annotation.
     *
     * @param id - id of the object to be removed
     * @throws IllegalArgumentException if there is no object in the annotation with the given
     *         id
     */
    public void removeObject(int id) {
        throwIfIdNotPresentInAnnotation(id);
        mObjects.remove(id);
    }

    private boolean throwIfIdNotPresentInAnnotation(int id) {
        for (PdfPageObject pageObject : mObjects) {
            if (pageObject.getObjectId() == id) {
                return true;
            }
        }
        return false;
    }
}
