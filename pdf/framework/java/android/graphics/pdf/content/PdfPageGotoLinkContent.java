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
import android.graphics.Rect;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.utils.Preconditions;

import java.util.List;

/**
 * Represents the content associated with a goto link on a page in the PDF document. Goto Link is an
 * internal navigation link which directs the user to a different location within the same pdf
 * document
 */
@FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
public class PdfPageGotoLinkContent {
    @NonNull
    private final List<Rect> mBounds;
    @NonNull
    private final Destination mDestination;


    /**
     * Creates a new instance of {@link PdfPageGotoLinkContent} using the bounds of the goto link
     * and the destination where it is directing
     *
     * @param bounds      Bounds which envelop the goto link
     * @param destination Destination where the goto link is directing
     * @throws NullPointerException     If bounds or destination is null.
     * @throws IllegalArgumentException If the bounds list is empty.
     */
    public PdfPageGotoLinkContent(@NonNull List<Rect> bounds, @NonNull Destination
            destination) {
        Preconditions.checkNotNull(bounds, "Bounds cannot be null");
        Preconditions.checkArgument(!bounds.isEmpty(), "Bounds cannot be empty");
        Preconditions.checkNotNull(destination, "Destination cannot be null");
        this.mBounds = bounds;
        this.mDestination = destination;
    }


    /**
     * Gets the bounds of a {@link PdfPageGotoLinkContent} represented as a list of {@link Rect}.
     * Links which are spread across multiple lines will be surrounded by multiple {@link Rect}
     * in order of viewing.
     *
     * @return The bounds of the goto link.
     */
    @NonNull
    public List<Rect> getBounds() {
        return mBounds;
    }


    /**
     * Gets the destination {@link Destination} of the {@link PdfPageGotoLinkContent}.
     *
     * @return Destination where goto link is directing the user.
     */
    @NonNull
    public Destination getDestination() {
        return mDestination;
    }

    /**
     * Represents the content associated with the destination where a goto link is directing
     */
    public static class Destination {
        private final int mPageNumber;

        private final float mXCoordinate;

        private final float mYCoordinate;
        private final float mZoom;

        /**
         * Creates a new instance of {@link Destination} using the page number, x coordinate, and
         * y coordinate of the destination where goto link is directing, and the zoom factor of the
         * page when goto link takes to the destination
         *
         * @param pageNumber  Page number of the goto link Destination
         * @param xCoordinate X coordinate of the goto link Destination in points (1/72")
         * @param yCoordinate Y coordinate of the goto link Destination in points (1/72")
         * @param zoom        Zoom factor {@link Destination#getZoom()} of the page when goto link
         *                    takes to the destination
         * @throws IllegalArgumentException If pageNumber or either of the coordinates or zoom are
         *                                  less than zero
         */
        public Destination(int pageNumber, float xCoordinate, float yCoordinate, float zoom) {
            Preconditions.checkArgument(pageNumber >= 0, "Page number must be"
                    + " greater than or equal to 0");
            Preconditions.checkArgument(xCoordinate >= 0, "X coordinate "
                    + "must be greater than or equal to 0");
            Preconditions.checkArgument(yCoordinate >= 0, "Y coordinate must "
                    + "be greater than or equal to 0");
            Preconditions.checkArgument(zoom >= 0, "Zoom factor number must be "
                    + "greater than or equal to 0");
            this.mPageNumber = pageNumber;
            this.mXCoordinate = xCoordinate;
            this.mYCoordinate = yCoordinate;
            this.mZoom = zoom;
        }


        /**
         * Gets the page number of the destination where the {@link PdfPageGotoLinkContent}
         * is directing.
         *
         * @return page number of the destination where goto link is directing the user.
         */
        public int getPageNumber() {
            return mPageNumber;
        }


        /**
         * Gets the x coordinate of the destination where the {@link PdfPageGotoLinkContent}
         * is directing.
         * <p><strong>Note:</strong> If underlying pdfium library can't determine the x coordinate,
         * it will be set to 0
         *
         * @return x coordinate of the Destination where the goto link is directing the user.
         */
        public float getXCoordinate() {
            return mXCoordinate;
        }


        /**
         * Gets the y coordinate of the destination where the {@link PdfPageGotoLinkContent}
         * is directing.
         * <p><strong>Note:</strong> If underlying pdfium library can't determine the y coordinate,
         * it will be set to 0
         *
         * @return y coordinate of the Destination where the goto link is directing the user.
         */
        public float getYCoordinate() {
            return mYCoordinate;
        }


        /**
         * Gets the zoom factor of the page when the goto link takes to the destination
         * <p><strong>Note:</strong> If there is no zoom value embedded, default value of zoom
         * will be zero. Otherwise it will be less than 1.0f in case of zoom out and greater
         * than 1.0f in case of zoom in.
         *
         * @return zoom factor of the page when the goto link takes to the destination
         */
        public float getZoom() {
            return mZoom;
        }
    }
}
