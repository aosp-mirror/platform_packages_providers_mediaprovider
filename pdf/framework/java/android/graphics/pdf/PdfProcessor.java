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

import static android.graphics.pdf.PdfLinearizationTypes.PDF_DOCUMENT_TYPE_LINEARIZED;
import static android.graphics.pdf.PdfLinearizationTypes.PDF_DOCUMENT_TYPE_NON_LINEARIZED;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.content.PdfPageImageContent;
import android.graphics.pdf.content.PdfPageLinkContent;
import android.graphics.pdf.content.PdfPageTextContent;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.models.BitmapParcel;
import android.graphics.pdf.models.PageMatchBounds;
import android.graphics.pdf.models.jni.LoadPdfResult;
import android.graphics.pdf.models.selection.PageSelection;
import android.graphics.pdf.models.selection.SelectionBoundary;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Represents a PDF document processing class.
 *
 * @hide
 */
public class PdfProcessor {

    private static String TAG = "PdfProcessor";

    private PdfDocumentProxy mPdfDocument;

    public PdfProcessor() {
        PdfDocumentProxy.loadLibPdf();
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
    @FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
    public void create(ParcelFileDescriptor fileDescriptor, @Nullable LoadParams params)
            throws IOException {
        Preconditions.checkNotNull(fileDescriptor, "Input FD cannot be null");
        ensurePdfDestroyed();
        try {
            Os.lseek(fileDescriptor.getFileDescriptor(), 0, OsConstants.SEEK_SET);
        } catch (ErrnoException ee) {
            throw new IllegalArgumentException("File descriptor not seekable");
        }

        String password = (params != null) ? params.getPassword() : null;
        LoadPdfResult result = PdfDocumentProxy.createFromFd(fileDescriptor.detachFd(),
                password);
        switch (result.status) {
            case NEED_MORE_DATA, PDF_ERROR, FILE_ERROR -> throw new IOException(
                    "Unable to load the document!");
            case REQUIRES_PASSWORD -> throw new SecurityException(
                    "Password required to access document");
            case LOADED -> this.mPdfDocument = result.pdfDocument;
            default -> throw new RuntimeException("Unexpected error has occurred!");
        }
    }

    /** Returns the number of pages in the PDF document */
    public int getNumPages() {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        return mPdfDocument.getNumPages();
    }

    /**
     * Returns the {@link List} of {@link PdfPageTextContent} for the page number specified. In case
     * of the multiple column textual content, the order is not guaranteed and the text is returned
     * as it is seen by the processing library.
     *
     * @param pageNum page number of the document
     * @return list of the textual content encountered on the page.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
    public List<PdfPageTextContent> getPageTextContents(int pageNum) {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        PdfPageTextContent content = new PdfPageTextContent(mPdfDocument.getPageText(pageNum));
        return List.of(content);
    }

    /**
     * Returns the alternate text for each image encountered on the specified page as a
     * {@link List} of {@link PdfPageImageContent}. The primary use case of this method is for
     * accessibility.
     *
     * @param pageNum page number of the document
     * @return list of the alt text for each image on the page.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
    public List<PdfPageImageContent> getPageImageContents(int pageNum) {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        // Use {@link java.util.Stream#collect()} instead of {@link java.util.Stream#toList)} as
        // it is available from Android SDK 34.
        return mPdfDocument.getPageAltText(pageNum).stream().map(
                PdfPageImageContent::new).collect(Collectors.toList());
    }

    /**
     * Returns the width of the given page of the PDF document. It is not guaranteed that all the
     * pages of the document will have the same dimensions
     */
    public int getPageWidth(int pageNum) {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        return mPdfDocument.getPageWidth(pageNum);
    }

    /**
     * Returns the height of the given page of the PDF document. It is not guaranteed that all the
     * pages of the document will have the same dimensions
     */
    public int getPageHeight(int pageNum) {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        return mPdfDocument.getPageHeight(pageNum);
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
    @FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
    public void renderPage(int pageNum, Bitmap bitmap,
            Rect destClip,
            Matrix transform,
            RenderParams params) {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        Preconditions.checkNotNull(bitmap, "Destination bitmap cannot be null");
        Preconditions.checkNotNull(params, "RenderParams cannot be null");

        int renderMode = params.getRenderMode();
        Preconditions.checkArgument(renderMode == PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        || renderMode == PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                "Unsupported render mode");

        try (BitmapParcel bitmapParcel = new BitmapParcel(bitmap)) {
            ParcelFileDescriptor parcelFileDescriptor = bitmapParcel.openOutputFd();
            if (parcelFileDescriptor != null) {
                if (destClip == null) {
                    // If {@code destClip} is not specified then the x-axis and y-axis position
                    // of the zoomed in/out wouldn't be required and the {@code Bitmap}
                    // dimensions
                    // will be page dimensions.
                    mPdfDocument.renderPageFd(pageNum, bitmap.getWidth(),
                            bitmap.getHeight(), params.areAnnotationsDisabled(),
                            parcelFileDescriptor.detachFd());
                } else {
                    // TODO(b/324894900): Cache the dimensions of the page so that we can avoid
                    //  extra JNI call.
                    int pageWidth = getPageWidth(pageNum);
                    int pageHeight = getPageHeight(pageNum);

                    Preconditions.checkArgument(clipInBitmap(destClip, bitmap),
                            "destClip not in bounds");

                    mPdfDocument.renderTileFd(pageNum, pageWidth, pageHeight, destClip.left,
                            destClip.top, bitmap.getWidth(), bitmap.getHeight(),
                            params.areAnnotationsDisabled(), parcelFileDescriptor.detachFd());
                }
            }
        } catch (InterruptedException | TimeoutException e) {
            Log.e(TAG, e.getMessage(), e);
        }
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
    @FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
    public List<PageMatchBounds> searchPageText(int pageNum, String query) {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        Preconditions.checkNotNull(query, "Search query cannot be null");
        return mPdfDocument.searchPageText(pageNum, query).unflattenToList();
    }

    /**
     * Return a PageSelection which represents the selected content that spans between the
     * two boundaries, both of which can be either exactly defined with text indexes, or
     * approximately defined with points on the page.The resulting Selection will also be
     * exactly defined with both indexes and points.If the start and stop boundary are both
     * the same point, selects the word at that point.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
    public PageSelection selectPageText(int pageNum,
            SelectionBoundary start, SelectionBoundary stop, boolean isRtl) {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        Preconditions.checkNotNull(start, "Start selection boundary cannot be null");
        Preconditions.checkNotNull(stop, "Stop selection boundary cannot be null");
        android.graphics.pdf.models.jni.PageSelection legacyPageSelection =
                mPdfDocument.selectPageText(
                        pageNum,
                        android.graphics.pdf.models.jni.SelectionBoundary.convert(start, isRtl),
                        android.graphics.pdf.models.jni.SelectionBoundary.convert(stop, isRtl));
        if (legacyPageSelection != null) {
            return legacyPageSelection.convert(isRtl);
        }
        return null;
    }

    /** Get the bounds and URLs of all the links on the given page. */
    @FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
    public List<PdfPageLinkContent> getPageLinkContents(int pageNum) {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        return mPdfDocument.getPageLinks(pageNum).unflattenToList();
    }

    /** Releases object in memory related to a page when that page is no longer visible. */
    public void releasePage(int pageNum) {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        mPdfDocument.releasePage(pageNum);
    }

    /**
     * Returns the linearization flag on the PDF document.
     */
    @PdfLinearizationTypes.PdfLinearizationType
    public int getDocumentLinearizationType() {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
        return mPdfDocument.isPdfLinearized()
                ? PDF_DOCUMENT_TYPE_LINEARIZED
                : PDF_DOCUMENT_TYPE_NON_LINEARIZED;
    }

    /** Ensures that any previous {@link PdfDocumentProxy} instance is closed. */
    public void ensurePdfDestroyed() {
        if (mPdfDocument != null) {
            try {
                mPdfDocument.destroy();
            } catch (Throwable t) {
                Log.e(this.getClass().getSimpleName(), "Error closing PdfDocumentProxy", t);
            }
        }
        mPdfDocument = null;
    }

    /**
     * Saves the current state of the loaded PDF document to the given writable
     * ParcelFileDescriptor.
     */
    public void write(ParcelFileDescriptor destination, boolean removePasswordProtection) {
        Preconditions.checkNotNull(mPdfDocument, "PdfDocumentProxy cannot be null");
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
        mPdfDocument.cloneWithoutSecurity(destination);
    }

    /**
     * Saves the current document to the given {@link ParcelFileDescriptor}.
     *
     * @param destination where the currently open PDF should be written.
     */
    private void saveAs(ParcelFileDescriptor destination) {
        mPdfDocument.saveAs(destination);
    }

    private boolean clipInBitmap(@Nullable Rect clip, Bitmap destination) {
        if (clip == null) {
            return true;
        }
        return clip.left >= 0 && clip.top >= 0
                && clip.right <= destination.getWidth()
                && clip.bottom <= destination.getHeight();
    }
}
