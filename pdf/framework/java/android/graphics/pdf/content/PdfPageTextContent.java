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

package android.graphics.pdf.content;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.graphics.pdf.flags.Flags;

import com.google.common.base.Preconditions;

/**
 * <p>
 * Represents a continuous stream of text in a page of a PDF document in the order of viewing.
 */
@FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
public final class PdfPageTextContent {
    private final String mText;

    /**
     * Creates a new instance of {@link PdfPageTextContent} using the raw text on the page of the
     * document.
     *
     * @param text Text content on the page.
     * @throws NullPointerException If text is null.
     */
    public PdfPageTextContent(@NonNull String text) {
        Preconditions.checkNotNull(text, "Text cannot be null");
        this.mText = text;
    }

    /**
     * Gets the text content on the page of the document.
     *
     * @return The text content on the page.
     */
    @NonNull
    public String getText() {
        return mText;
    }
}
