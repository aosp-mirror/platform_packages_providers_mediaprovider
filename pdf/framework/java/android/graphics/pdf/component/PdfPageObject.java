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
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.flags.Flags;

/**
 * Represents a page object on a page of a pdf document.
 * This abstract class provides a base implementation for
 * different types of PDF page objects.
 */
@FlaggedApi(Flags.FLAG_ENABLE_EDIT_PDF_PAGE_OBJECTS)
public abstract class PdfPageObject {
    // Possible Values are {@link PdfPageObjectType}
    private final int mType;

    // Id of page object
    private int mObjectId;

    // Bound of page object
    private RectF mBounds;

    // Transformation matrix of page object
    private Matrix mTransform;

    /**
     * Constructor for the PageObject.
     *
     * @param type The type of the page object.
     */
    PdfPageObject(int type) {
        this.mType = type;
        this.mTransform = new Matrix(); // Initialize with identity matrix
        this.mObjectId = -1; // Initialize with -1
    }

    /**
     * Returns the type of the page object.
     *
     * @return The type of the page object.
     */
    public int getPdfObjectType() {
        return mType;
    }

    /**
     * Returns the ID of the object.
     *
     * @return The ID of the object.
     */
    public int getObjectId() {
        return mObjectId;
    }

    /**
     * Sets the objectIndex of the object.
     *
     * @param objectId The objectIndex of the object.
     * @hide
     */
    protected void setObjectId(int objectId) {
        this.mObjectId = objectId;
    }

    /**
     * Returns the bounding rectangle of the object.
     *
     * @return The bounding rectangle of the object.
     */
    @NonNull
    public RectF getBounds() {
        return mBounds;
    }

    /**
     * Sets the bounding rectangle of the object.
     *
     * @param bounds The bounding rectangle of the object.
     */
    public void setBounds(@NonNull RectF bounds) {
        this.mBounds = bounds;
    }

    /**
     * Transform the page object
     * The matrix is composed as:
     * |a c e|
     * |b d f|
     * and can be used to scale, rotate, shear and translate the |page_object|.
     */
    public void transform(float a, float b, float c, float d, float e, float f) {
        Matrix matrix = new Matrix();
        matrix.setValues(new float[]{a, e, d, c, b, f, 0, 0, 1}); // Set the matrix values
        this.mTransform.postConcat(matrix); // Apply the transformation

        // Update the objectRect based on the new transformation
        if (this.mBounds != null) {
            RectF newRect = new RectF(this.mBounds);
            matrix.mapRect(newRect);
            this.mBounds.set(newRect);
        }
    }

    /**
     * Returns the transformation matrix of the object.
     *
     * @return The transformation matrix of the object.
     */
    @NonNull
    public float[] getMatrix() {
        float[] value = new float[9];
        this.mTransform.getValues(value);
        return value;
    }

    /**
     * Sets the transformation matrix of the object.
     *
     * @param matrix The transformation matrix of the object.
     */
    public void setMatrix(@NonNull Matrix matrix) {
        this.mTransform = matrix;
    }
}
