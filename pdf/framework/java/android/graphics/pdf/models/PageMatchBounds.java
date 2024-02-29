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

package android.graphics.pdf.models;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.graphics.Rect;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.utils.Preconditions;

import java.util.List;

/**
 * Represents the bounds of a single search match on a page of the PDF document.
 */
@FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
public final class PageMatchBounds {
    private final List<Rect> mBounds;
    private final int mTextStartIndex;

    /**
     * Creates a new instance of {@link PageMatchBounds} for the text match found on the page. The
     * match is represented by bounds of the text match and the starting index of the character
     * "stream" (0-based index).
     *
     * @param bounds         Bounds of the text match.
     * @param textStartIndex starting index of the text match.
     * @throws NullPointerException     If bounds if null.
     * @throws IllegalArgumentException If bounds list is empty or if the text starting index is
     *                                  negative.
     */
    public PageMatchBounds(@NonNull List<Rect> bounds, int textStartIndex) {
        Preconditions.checkNotNull(bounds, "Bounds cannot be null");
        Preconditions.checkArgument(!bounds.isEmpty(), "Match bounds cannot be empty");
        Preconditions.checkArgument(textStartIndex >= 0, "Index cannot be negative");
        this.mBounds = bounds;
        this.mTextStartIndex = textStartIndex;
    }

    /**
     * <p>
     * Represents the {@link Rect} bounds of a match. Matches which are spread across multiple lines
     * will be represented by multiple {@link Rect} in order of viewing.
     * <p><strong>Note:</strong> The bounds only represent the coordinates of the bounds of a
     * single line using {@link Rect}. The developer will need to render the highlighter as well as
     * intercept the touch events for any additional UI interactions.
     *
     * @return list of bounds for the match on the page.
     */
    @NonNull
    public List<Rect> getBounds() {
        return mBounds;
    }

    /**
     * Gets the starting index of the match found on the page. Characters in a page form a "stream"
     * and inside the stream, each character has an index starting from 0.
     *
     * @return the starting index of the match on the page.
     */
    public int getTextStartIndex() {
        return mTextStartIndex;
    }
}

