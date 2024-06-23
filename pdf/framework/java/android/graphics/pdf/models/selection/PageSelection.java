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
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.utils.Preconditions;

import java.util.List;

/**
 * <p>
 * Represents the list of selected content on a particular page of the PDF document. By
 * default, the selection boundary is represented from left to right.
 * <strong>Note: </strong>Currently supports {@link TextSelection} only.
 */
@FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
public final class PageSelection {
    private final int mPage;

    private final boolean mIsRtl;

    private final SelectionBoundary mLeft;

    private final SelectionBoundary mRight;

    private final List<TextSelection> mTextSelections;

    /**
     * Creates a new instance of {@link PageSelection} for the specified page, the left and right
     * selection edge and the selected text content. Rtl defaults to {@code false}.
     *
     * @param page          The page number of the selection.
     * @param left          Left edge of the selection.
     * @param right         right edge of the selection.
     * @param textSelection Selected text content.
     * @throws IllegalArgumentException If the page number is negative.
     * @throws NullPointerException     If left/right edge or text selection is null.
     */
    public PageSelection(int page, @NonNull SelectionBoundary left,
            @NonNull SelectionBoundary right,
            @NonNull List<TextSelection> textSelection) {
        this(page, left, right, textSelection, /* isRtl = */ false);
    }

    /**
     * Creates a new instance of {@link PageSelection} for the specified page, the left and right
     * selection edge and the selected text content.
     *
     * @param page          The page number of the selection.
     * @param left          Left edge of the selection.
     * @param right         right edge of the selection.
     * @param textSelection Selected text content.
     * @param isRtl         Determines the rtl mode of the selection.
     * @throws IllegalArgumentException If the page number is negative.
     * @throws NullPointerException     If left/right edge or text selection is null.
     */
    public PageSelection(int page, @NonNull SelectionBoundary left,
            @NonNull SelectionBoundary right,
            @NonNull List<TextSelection> textSelection, boolean isRtl) {
        Preconditions.checkArgument(page >= 0, "Page number cannot be negative");
        Preconditions.checkNotNull(left, "Left boundary cannot be null");
        Preconditions.checkNotNull(right, "Right boundary cannot be null");
        Preconditions.checkNotNull(textSelection, "Selected text content cannot be null");
        this.mLeft = left;
        this.mRight = right;
        this.mPage = page;
        this.mTextSelections = textSelection;
        this.mIsRtl = isRtl;
    }

    /**
     * Gets the particular page for which the selection is highlighted.
     *
     * @return The page number on which the current selection resides.
     */
    public int getPage() {
        return mPage;
    }

    /**
     * Determines if the selected content is from right-to-left. If true then the {@link #getLeft()}
     * returns the end of the selection and {@link #getRight()} the start of the selection.
     *
     * @return If the selection is from right-to-left.
     */
    public boolean isRtl() {
        return mIsRtl;
    }

    /**
     * <p>
     * Gets the left edge of the selection - index is inclusive.
     * <strong>Note: </strong>Represents the right edge if {@link #isRtl()} returns true.
     *
     * @return The left edge of the selection.
     */
    @NonNull
    public SelectionBoundary getLeft() {
        return mLeft;
    }

    /**
     * <p>
     * Gets the right edge of the selection - index is inclusive.
     * <strong>Note: </strong>Represents the left edge if {@link #isRtl()} returns true.
     *
     * @return The right edge of the selection.
     */
    @NonNull
    public SelectionBoundary getRight() {
        return mRight;
    }

    /**
     * Returns the text within the selection boundaries on the page. In case there are
     * non-continuous selections, this method returns the list of those selections in order of
     * viewing.
     *
     * @return list of text selections on a page.
     */
    @NonNull
    public List<TextSelection> getTextSelections() {
        return mTextSelections;
    }
}
