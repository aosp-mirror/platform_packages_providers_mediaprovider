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

import android.graphics.Rect;
import android.graphics.pdf.models.jni.LinkRects;
import android.graphics.pdf.models.jni.LoadPdfResult;
import android.graphics.pdf.models.jni.MatchRects;
import android.graphics.pdf.models.jni.PageSelection;
import android.graphics.pdf.models.jni.SelectionBoundary;
import android.graphics.pdf.utils.StrictModeUtils;
import android.os.ParcelFileDescriptor;

import java.util.List;

/**
 * This class accesses the PdfClient tools to manipulate and render a PDF document. One instance of
 * this class corresponds to one PDF document, loads it within PdfClient and keeps an internal
 * reference to the resulting object, to be re-used in subsequent calls.
 *
 * <p>This class is mostly a JNI gateway to PdfClient.
 *
 * @hide
 */
public class PdfDocumentProxy {
    private static final String TAG = "PdfDocument";

    private static final String LIB_NAME = "pdfclient";

    /** Internal reference to a native pointer to a Document object. */
    private final long mPdfDocPtr;

    private final int mNumPages;

    /** Constructs a PdfDocument. Do not call directly from java, use {@link #createFromFd}. */
    protected PdfDocumentProxy(long pdfDocPtr, int numPages) {
        this.mPdfDocPtr = pdfDocPtr;
        this.mNumPages = numPages;
    }

    /**
     * Tries to load a PdfDocument from native file descriptor.
     *
     * @return a LoadPdfResult of status LOADED containing the PdfDocument,
     * or, an empty LoadPdfResult of a different status indicating failure.
     */
    public static native LoadPdfResult createFromFd(int fd, String password);

    /**
     * Loads the PdfClient binary library used to render PDF documents. The library will only be
     * loaded once so subsequent calls after the first will have no effect. This may be used to
     * preload the library before use.
     */
    public static void loadLibPdf() {
        // TODO(b/324549320): Cleanup if bypassing is not required
        StrictModeUtils.bypass(() -> System.loadLibrary(LIB_NAME));
    }

    public long getPdfDocPtr() {
        return mPdfDocPtr;
    }

    public int getNumPages() {
        return mNumPages;
    }

    /** Destroys the PDF document and release resources held by PdfClient. */
    public native void destroy();

    /**
     * Tries to save this PdfDocument to the given native file descriptor, which must be open for
     * write or append.
     *
     * @return true on success
     */
    public native boolean saveToFd(int fd);

    /**
     * Saves the current state of this {@link PdfDocument} to the given, writable, file descriptor.
     * The given file descriptor is closed by this function.
     *
     * @param destination the file descriptor to write to
     * @return true on success
     */
    public boolean saveAs(ParcelFileDescriptor destination) {
        return saveToFd(destination.detachFd());
    }

    /**
     * Returns the width of the given page of the PDF. This is measured in points, but we
     * zoom-to-fit, so it doesn't matter.
     */
    public native int getPageWidth(int pageNum);

    /**
     * Returns the height of the given page of the PDF. This is measured in points, but we
     * zoom-to-fit, so it doesn't matter.
     */
    public native int getPageHeight(int pageNum);

    /**
     * Renders the given page at the given size and sends the bitmap bytes on the destination file
     * descriptor.
     *
     * <p>The given file descriptor is detached and closed by this function.
     *
     * @return true if the page was rendered into the output bitmap
     */
    public boolean renderPageFd(
            int pageNum, int width, int height, boolean hideTextAnnots, int fileDescriptor) {
        return renderPageFd(pageNum, width, height, hideTextAnnots, /* retainPage = */ false,
                fileDescriptor);
    }

    private native boolean renderPageFd(
            int pageNum, int width, int height, boolean hideTextAnnots, boolean retainPage, int fd);

    /**
     * Renders one tile of the given page and writes the output bitmap bytes to {@code destination}.
     *
     * <p>The {@code pageWidth} and {@code pageHeight} values define how large the page is to be
     * rendered before extracting the tile located at (left, top, tileSize).
     *
     * <p>The whole page is not actually rendered.
     *
     * <p>The given file descriptor is detached and closed by this function.
     *
     * @param pageNum        the page number of the page to be rendered
     * @param pageWidth      the width of the page to be (partially) rendered
     * @param pageHeight     the height of the page to be (partially) rendered
     * @param left           the x-axis position on the page of the tile (i.e. the bitmap left edge)
     * @param top            the y-axis position on the page of the tile (i.e. the bitmap top edge)
     * @param destination    the parcelFileDescriptor object to be filled (declares its own
     *                       dimensions)
     * @param hideTextAnnots whether to hide text and highlight annotations
     * @return true if the tile was rendered into the destination file descriptor
     */
    public boolean renderTileFd(
            int pageNum,
            int pageWidth,
            int pageHeight,
            int left,
            int top,
            int tileWidth,
            int tileHeight,
            boolean hideTextAnnots,
            int destination) {
        return renderTileFd(
                pageNum,
                pageWidth,
                pageHeight,
                left,
                top,
                tileWidth,
                tileHeight,
                hideTextAnnots,
                /* retainPage = */ false,
                destination);
    }

    private native boolean renderTileFd(
            int pageNum,
            int pageWidth,
            int pageHeight,
            int left,
            int top,
            int tileWidth,
            int tileHeight,
            boolean hideTextAnnots,
            boolean retainPage,
            int fd);

    /**
     * Clones the currently loaded document using the provided file descriptor.
     * <p>You are required to detach the file descriptor as the native code will close it.
     *
     * @param destination native fd pointer
     * @return true if the cloning was successful
     */
    private native boolean cloneWithoutSecurity(int destination);

    /**
     * Clones the currently loaded document using the provided file descriptor.
     * <p>You are required to detach the file descriptor as the native code will close it.
     *
     * @param destination {@link ParcelFileDescriptor} to which the document needs to be written to.
     * @return true if the cloning was successful
     */
    public boolean cloneWithoutSecurity(ParcelFileDescriptor destination) {
        return cloneWithoutSecurity(destination.detachFd());
    }

    /**
     * Gets the text of the entire page as a string, in the order the text is
     * found in the PDF stream.
     */
    public native String getPageText(int pageNum);

    /**
     * Gets all pieces of alt-text found for the page, in the order the alt-text is found in the
     * PDF stream.
     */
    public native List<String> getPageAltText(int pageNum);

    /**
     * Searches for the given string on the page and returns the bounds of all of the matches.
     * The number of matches is {@link MatchRects#size()}.
     */
    public native MatchRects searchPageText(int pageNum, String query);

    /**
     * Get the text selection that spans between the two boundaries (inclusive of start and
     * exclusive of stop), both of which can be either exactly defined with text indexes, or
     * approximately defined with points on the page. The resulting selection will also be exactly
     * defined with both indexes and points. If the start and stop boundary are both the same point,
     * selects the word at that point.
     */
    public native PageSelection selectPageText(int pageNum, SelectionBoundary start,
            SelectionBoundary stop);

    /** Get the bounds and URLs of all the links on the given page. */
    public native LinkRects getPageLinks(int pageNum);

    /** Cleans up objects in memory related to a page after it is no longer visible. */
    public native void releasePage(int pageNum);

    /** Returns true if the PDF is linearized. (May give false negatives for <1KB PDFs). */
    public native boolean isPdfLinearized();

    /**
     * Executes an interactive click on the page at the given point ({@code x}, {@code y}).
     *
     * @return rectangular areas of the page bitmap that have been invalidated by this action
     */
    public native List<Rect> clickOnPage(int pageNum, int x, int y);
}
