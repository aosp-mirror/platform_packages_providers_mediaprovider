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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.graphics.RectF;
import android.graphics.pdf.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a path object on a PDF page. This class extends
 * {@link PdfPageObject} and provides methods to access and modify the
 * path's content, such as its shape, fill color, stroke color, line width,
 * and line style.
 */
@FlaggedApi(Flags.FLAG_ENABLE_EDIT_PDF_PAGE_OBJECTS)
public final class PdfPagePathObject extends PdfPageObject {
    private Path mPath;
    private PathEffect mLineStyle;
    private Color mStrokeColor;
    private float mStrokeWidth;
    private Color mFillColor;

    /**
     * Constructor for the PdfPagePathObject. Sets the object type
     * to {@link PdfPageObjectType#PATH}.
     */
    public PdfPagePathObject() {
        super(PdfPageObjectType.PATH);
        this.mPath = new Path();
        this.mStrokeColor = new Color(); // Default is opaque black in the sRGB color space.
        this.mStrokeWidth = 1.0f;
    }

    /**
     * Returns the path of the object.
     *
     * @return The path.
     */
    @NonNull
    public Path getPath() {
        return mPath;
    }

    /**
     * Sets the path of the object.
     *
     * @param path The path to set.
     */
    public void setPath(@NonNull Path path) {
        this.mPath = path;
    }

    /**
     * Returns the line style of the object's stroke.
     *
     * @return The {@link PathEffect} representing the line style, or null if no
     * style is set.
     */
    @Nullable
    public PathEffect getLineStyle() {
        return mLineStyle;
    }

    /**
     * Returns the stroke color of the object.
     *
     * @return The stroke color of the object.
     */
    @NonNull
    public Color getStrokeColor() {
        return mStrokeColor;
    }

    /**
     * Sets the stroke color of the object.
     *
     * @param strokeColor The stroke color of the object.
     */
    public void setStrokeColor(@NonNull Color strokeColor) {
        this.mStrokeColor = strokeColor;
    }

    /**
     * Returns the stroke width of the object.
     *
     * @return The stroke width of the object.
     */
    public float getStrokeWidth() {
        return mStrokeWidth;
    }

    /**
     * Sets the stroke width of the object.
     *
     * @param strokeWidth The stroke width of the object.
     */
    public void setStrokeWidth(float strokeWidth) {
        this.mStrokeWidth = strokeWidth;
    }

    /**
     * Sets the line style of the object's stroke.
     *
     * @param lineStyle An integer representing the line style to set.
     */
    public void setLineStyle(int lineStyle) {
        switch (lineStyle) {
            case LineStyle.DASHED: // Example: Dashed line
                this.mLineStyle = new DashPathEffect(new float[]{10, 5}, 0);
                break;
            case LineStyle.DOTTED: // Example: Dotted line
                this.mLineStyle = new DashPathEffect(new float[]{2, 2}, 0);
                break;
            default: // Solid line (no effect)
                this.mLineStyle = null;
                break;
        }
    }

    /**
     * Returns the fill color of the object.
     *
     * @return The fill color of the object.
     */
    @Nullable
    public Color getFillColor() {
        return mFillColor;
    }

    /**
     * Sets the fill color of the object.
     *
     * @param fillColor The fill color of the object.
     */
    public void setFillColor(@Nullable Color fillColor) {
        this.mFillColor = fillColor;
    }

    /**
     * Overrides the
     * {@link PdfPageObject#transform(float, float, float, float, float, float)}
     * method to correctly transform the Path object.
     *
     * This method applies the given affine transformation matrix to the path and
     * also updates the object's bounding rectangle.
     *
     * @param a The a value of the transformation matrix.
     * @param b The b value of the transformation matrix.
     * @param c The c value of the transformation matrix.
     * @param d The d value of the transformation matrix.
     * @param e The e value of the transformation matrix.
     * @param f The f value of the transformation matrix.
     */
    @Override
    public void transform(float a, float b, float c, float d, float e, float f) {
        Matrix matrix = new Matrix();
        matrix.setValues(new float[]{a, c, e, b, d, f, 0, 0, 1});
        this.mPath.transform(matrix);

        // Also transform the objectRect
        RectF newRect = new RectF(this.getBounds());
        matrix.mapRect(newRect);
        this.getBounds().set(newRect);
    }

    /** @hide */
    @IntDef({LineStyle.SOLID, LineStyle.DASHED, LineStyle.DOTTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface LineStyle {
        /** Solid line (no effect). */
        int SOLID = 0;
        /** Dashed line. */
        int DASHED = 1;
        /** Dotted line. */
        int DOTTED = 2;
    }
}
