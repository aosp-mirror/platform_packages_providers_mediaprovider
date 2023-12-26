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

package android.graphics.pdf;

import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.content.PdfPageImageContent;
import android.graphics.pdf.content.PdfPageLinkContent;
import android.graphics.pdf.content.PdfPageTextContent;
import android.graphics.pdf.models.PageMatchBounds;
import android.graphics.pdf.models.selection.PageSelection;
import android.graphics.pdf.models.selection.SelectionBoundary;
import android.os.ParcelFileDescriptor;

import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.List;

/**
 * Represents a PDF document processing class.
 *
 * @hide
 */
public class PdfProcessor {

    public PdfProcessor() {
    }

    /**
     * Creates an instance of {@link PdfDocumentProxy} on successful loading of the PDF document.
     * This method ensures that an older {@link PdfDocumentProxy} instance is closed and then loads
     * the new document. This method should be run on a {@link android.annotation.WorkerThread} as
     * it is long-running task.
     *
     * @param fileDescriptor {@link ParcelFileDescriptor} for the input PDF document.
     * @param params         instance of {@link LoadParams} which includes the password as well.
     * @throws IOException       if an error occurred during the processing of the PDF document.
     * @throws SecurityException if the password is incorrect.
     */
    public void create(ParcelFileDescriptor fileDescriptor, @Nullable LoadParams params)
            throws IOException {
        throw new UnsupportedOperationException();
    }

    /** Returns the number of pages in the PDF document */
    public int getNumPages() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the {@link List} of {@link PdfPageTextContent} for the page number specified. In case
     * of the multiple column textual content, the order is not guaranteed and the text is returned
     * as it is seen by the processing library.
     *
     * @param pageNum page number of the document
     * @return list of the textual content encountered on the page.
     */
    public List<PdfPageTextContent> getPageTextContents(int pageNum) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the alternate text for each image encountered on the specified page as a
     * {@link List} of {@link PdfPageImageContent}. The primary use case of this method is for
     * accessibility.
     *
     * @param pageNum page number of the document
     * @return list of the alt text for each image on the page.
     */
    public List<PdfPageImageContent> getPageImageContents(int pageNum) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the width of the given page of the PDF document. It is not guaranteed that all the
     * pages of the document will have the same dimensions
     */
    public int getPageWidth(int pageNum) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the height of the given page of the PDF document. It is not guaranteed that all the
     * pages of the document will have the same dimensions
     */
    public int getPageHeight(int pageNum) {
        throw new UnsupportedOperationException();
    }

    /**
     * Renders a page to a bitmap for the specified page number. The {@link Rect} parameter
     * represents the offset from top and left for tile generation purposes. If the `destClip` is
     * not {@code null} then the {@link PdfDocumentProxy#renderPageFd(int, int, int, boolean, int)}
     * is invoked else
     * {@link PdfDocumentProxy#renderTileFd(int, int, int, int, int, int, int, boolean, int)} is
     * invoked. In case of default zoom, the page dimensions will be equal to the bitmap
     * dimensions.
     * In case of zoom, the tile dimensions will be equal to the bitmap dimensions.
     * The method will take care of closing the bitmap fd to which pdfium writes to using
     * {@link BitmapParcel}. Should be invoked on the {@link android.annotation.WorkerThread} as it
     * is long-running task.
     */
    public void renderPage(int pageNum, Bitmap bitmap,
            Rect destClip,
            Matrix transform,
            RenderParams params) {
        Preconditions.checkNotNull(bitmap, "Destination bitmap cannot be null");
        Preconditions.checkNotNull(params, "RenderParams cannot be null");
        throw new UnsupportedOperationException();
    }

    /**
     * Searches the specified page with the specified query. Should be run on the
     * {@link android.annotation.WorkerThread} as it is a long-running task.
     *
     * @param pageNum page number of the document
     * @param query   the search query
     * @return list of {@link PageMatchBounds} that represents the highlighters which can span
     * multiple
     * lines as well.
     */
    public List<PageMatchBounds> searchPageText(int pageNum, String query) {
        Preconditions.checkNotNull(query, "Search query cannot be null");
        throw new UnsupportedOperationException();
    }

    /**
     * Return a PageSelection which represents the selected content that spans between the
     * two boundaries, both of which can be either exactly defined with text indexes, or
     * approximately defined with points on the page.The resulting Selection will also be
     * exactly defined with both indexes and points.If the start and stop boundary are both
     * the same point, selects the word at that point.
     */
    public PageSelection selectPageText(int pageNum,
            SelectionBoundary start, SelectionBoundary stop, boolean isRtl) {
        Preconditions.checkNotNull(start, "Start selection boundary cannot be null");
        Preconditions.checkNotNull(stop, "Stop selection boundary cannot be null");
        throw new UnsupportedOperationException();
    }

    /** Get the bounds and URLs of all the links on the given page. */
    public List<PdfPageLinkContent> getPageLinkContents(int pageNum) {
        throw new UnsupportedOperationException();
    }

    /** Releases object in memory related to a page when that page is no longer visible. */
    public void releasePage(int pageNum) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the linearization flag on the PDF document.
     */
    public int getDocumentLinearizationType() {
        throw new UnsupportedOperationException();
    }

    /** Ensures that any previous {@link PdfDocumentProxy} instance is closed. */
    public void ensurePdfDestroyed() {
        throw new UnsupportedOperationException();
    }

    /**
     * Saves the current state of the loaded PDF document to the given writable
     * ParcelFileDescriptor.
     */
    public void write(ParcelFileDescriptor destination, boolean removePasswordProtection) {
        Preconditions.checkNotNull(destination, "Destination FD cannot be null");
        if (removePasswordProtection) {
            cloneWithoutSecurity(destination);
        } else {
            saveAs(destination);
        }
    }

    /**
     * Creates a copy of the current document without security, if it is password protected. This
     * may be necessary for the PrintManager which can't handle password-protected files.
     *
     * @param destination points to where pdfclient should make a copy of the pdf without security.
     */
    private void cloneWithoutSecurity(ParcelFileDescriptor destination) {
        throw new UnsupportedOperationException();
    }

    /**
     * Saves the current document to the given {@link ParcelFileDescriptor}.
     *
     * @param destination where the currently open PDF should be written.
     */
    private void saveAs(ParcelFileDescriptor destination) {
        throw new UnsupportedOperationException();
    }

    private void throwIfDocumentClosed() {
        throw new UnsupportedOperationException();
    }
}
