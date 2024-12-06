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

/**
 * Represents a PDF annotation on a page of a PDF document.
 * This abstract class provides a base implementation for different types of PDF annotations
 * such as text ({@link FreeTextAnnotation}),
 * highlight ({@link HighlightAnnotation}) etc
 */
@FlaggedApi(Flags.FLAG_ENABLE_EDIT_PDF_ANNOTATIONS)
public abstract class PdfAnnotation {
    private int mId;
    private int mType;
    @NonNull  private RectF mBounds;

    /**
     * Creates a new PDF annotation with the specified type and bounds.
     *
     * @param type   The type of annotation. See {@link PdfAnnotationType} for possible values.
     * @param bounds The bounding rectangle of the annotation.
     */
    PdfAnnotation(@PdfAnnotationType.Type int type, @NonNull RectF bounds) {
        Preconditions.checkNotNull(bounds, "Bounds cannot be null");
        Preconditions.checkArgument(type == PdfAnnotationType.UNKNOWN
                || type == PdfAnnotationType.FREETEXT
                || type == PdfAnnotationType.HIGHLIGHT
                || type == PdfAnnotationType.STAMP, "Invalid Annotation Type");

        this.mId = -1;
        this.mType = type;
        this.mBounds = bounds;
    }

    /**
     * Returns the id of the annotation.
     * <p>
     * Id of an annotation will be unique in a page.
     *
     * @return The annotation ID.
     */
    public int getId() {
        return mId;
    }

    /**
     * Sets the id of the annotation.
     * <p>
     * When the annotation is created, it's assigned default id as -1, when it will
     * added to a page using
     * {@link  android.graphics.pdf.PdfRenderer.Page#addPageAnnotation(PdfAnnotation)}
     * or {@link android.graphics.pdf.PdfRendererPreV.Page#addPageAnnotation(PdfAnnotation)},
     * it will get assigned a unique id in the page.
     * </p>
     *
     * @param id to be assigned to the annotation
     * @hide
     */
    protected void setId(int id) {
        mId = id;
    }

    /**
     * Returns the type of the annotation.
     *
     * @return The annotation type. See {@link PdfAnnotationType} for possible values.
     */
    public @PdfAnnotationType.Type int getPdfAnnotationType() {
        return mType;
    }

    /**
     * Sets the bounding rectangle of the annotation.
     *
     * @param bounds The new bounding rectangle.
     */
    public void setBounds(@NonNull RectF bounds) {
        this.mBounds = bounds;
    }

    /**
     * Returns the bounding rectangle of the annotation.
     *
     * @return The bounding rectangle.
     */
    @NonNull public RectF getBounds() {
        return mBounds;
    }

}
