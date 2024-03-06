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
import android.graphics.Rect;
import android.graphics.pdf.content.PdfPageTextContent;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.utils.Preconditions;

import java.util.List;

/**
 * Represents text selection on a page of the PDF document.
 */
@FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
public final class TextSelection {
    @NonNull
    private final List<Rect> mSelectionBounds;

    @NonNull
    private final PdfPageTextContent mTextContent;

    /**
     * Creates a new instance of {@link TextSelection} using the text content on the page of
     * the PDF document and the bounds of the text.
     *
     * @param bounds      Bounds for the selected text.
     * @param textContent Text content in the specified bounds.
     * @throws NullPointerException If bounds or the text content is null.
     */
    public TextSelection(@NonNull List<Rect> bounds, @NonNull PdfPageTextContent textContent) {
        Preconditions.checkNotNull(bounds, "Bounds cannot be null");
        Preconditions.checkNotNull(textContent, "Content cannot be null");
        mSelectionBounds = bounds;
        mTextContent = textContent;
    }

    /**
     * Gets the bounds of the highlighter for the selected text content represented as a list of
     * {@link Rect}. Each {@link Rect} represents a bound of the selected text content in a single
     * line and defines the coordinates of its 4 edges (left, top, right and bottom) in
     * points (1/72"). Selections which are spread across multiple lines will be represented by
     * list of {@link Rect} in the order of selection.
     *
     * @return The bounds of the selections.
     */
    @NonNull
    public List<Rect> getSelectionBounds() {
        return mSelectionBounds;
    }

    /**
     * Gets the selected text content.
     *
     * @return The selected text content.
     */
    @NonNull
    public PdfPageTextContent getSelectedTextContents() {
        return mTextContent;
    }
}
