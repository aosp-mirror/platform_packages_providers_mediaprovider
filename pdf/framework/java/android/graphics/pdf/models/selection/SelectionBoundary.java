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

package android.graphics.pdf.models.selection;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.pdf.content.PdfPageTextContent;
import android.graphics.pdf.flags.Flags;

import com.google.common.base.Preconditions;

/**
 * Represents one edge of the selected content.
 */
@FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
public class SelectionBoundary {
    private final int mIndex;

    private final Point mPoint;

    /**
     * <p>
     * Create a new instance of {@link SelectionBoundary} if index of boundary is known. The text
     * returned by {@link PdfPageTextContent#getText()} form a "stream" and inside this "stream"
     * each character has an index.
     * <strong>Note: </strong>Point defaults to {@code null} in this case.
     *
     * @param index index of the selection boundary.
     * @throws IllegalArgumentException If the index is negative.
     */
    public SelectionBoundary(int index) {
        Preconditions.checkArgument(index >= 0, "Index cannot be negative");
        this.mIndex = index;
        this.mPoint = null;
    }

    /**
     * Create a new instance of {@link SelectionBoundary} if the boundary {@link Point} is known.
     * Index defaults to -1.
     *
     * @param point The point of selection boundary.
     * @throws NullPointerException If the point is null.
     */
    public SelectionBoundary(@NonNull Point point) {
        Preconditions.checkNotNull(point, "Point cannot be null");
        this.mIndex = -1;
        this.mPoint = point;
    }

    /**
     * Gets the index of the text as determined by the text stream processed. If the value is -1
     * then the {@link #getPoint()} will determine the selection boundary.
     *
     * @return index of the selection boundary.
     */
    public int getIndex() {
        return mIndex;
    }

    /**
     * Gets the {@link Point} for selection boundary. If the value is {@code null} then the
     * {@link #getIndex()} will determine the selection boundary.
     *
     * @return The point of the selection boundary.
     */
    @Nullable
    public Point getPoint() {
        return mPoint;
    }
}
