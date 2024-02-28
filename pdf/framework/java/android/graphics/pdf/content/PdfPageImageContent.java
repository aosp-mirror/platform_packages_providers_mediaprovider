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
import android.graphics.pdf.utils.Preconditions;

/**
 * <p>
 * Represents the content associated with an image type in a page of a PDF document.
 */
@FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
public final class PdfPageImageContent {
    private final String mAltText;

    /**
     * Creates a new instance of {@link PdfPageImageContent} using the alternate text of the image
     * on the page.
     *
     * @param altText Alternate text for the image.
     * @throws NullPointerException If the alternate text is null.
     */
    public PdfPageImageContent(@NonNull String altText) {
        Preconditions.checkNotNull(altText, "Alternate text cannot be null");
        this.mAltText = altText;
    }

    /**
     * Gets the alternate text associated with the image represented by this instance. If there
     * is no such text associated with the image, the method will return an empty string.
     *
     * @return the alternate text of the image.
     */
    @NonNull
    public String getAltText() {
        return mAltText;
    }
}
