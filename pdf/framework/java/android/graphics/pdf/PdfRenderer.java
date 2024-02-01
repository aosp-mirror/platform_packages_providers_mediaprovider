/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.models.FormEditRecord;
import android.graphics.pdf.models.FormWidgetInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.CloseGuard;
import android.util.Log;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * This class enables rendering a PDF document. This class is not thread safe.
 * </p>
 * <p>
 * If you want to render a PDF, you create a renderer and for every page you want
 * to render, you open the page, render it, and close the page. After you are done
 * with rendering, you close the renderer. After the renderer is closed it should not
 * be used anymore. Note that the pages are rendered one by one, i.e. you can have
 * only a single page opened at any given time.
 * </p>
 * <p>
 * A typical use of the APIs to render a PDF looks like this:
 * </p>
 * <pre>
 * // create a new renderer
 * PdfRenderer renderer = new PdfRenderer(getSeekableFileDescriptor());
 *
 * // let us just render all pages
 * final int pageCount = renderer.getPageCount();
 * for (int i = 0; i < pageCount; i++) {
 *     Page page = renderer.openPage(i);
 *
 *     // say we render for showing on the screen
 *     page.render(mBitmap, null, null, Page.RENDER_MODE_FOR_DISPLAY);
 *
 *     // do stuff with the bitmap
 *
 *     // close the page
 *     page.close();
 * }
 *
 * // close the renderer
 * renderer.close();
 * </pre>
 *
 * <h3>Print preview and print output</h3>
 * <p>
 * If you are using this class to rasterize a PDF for printing or show a print
 * preview, it is recommended that you respect the following contract in order
 * to provide a consistent user experience when seeing a preview and printing,
 * i.e. the user sees a preview that is the same as the printout.
 * </p>
 * <ul>
 * <li>
 * Respect the property whether the document would like to be scaled for printing
 * as per {@link #shouldScaleForPrinting()}.
 * </li>
 * <li>
 * When scaling a document for printing the aspect ratio should be preserved.
 * </li>
 * <li>
 * Do not inset the content with any margins from the {@link android.print.PrintAttributes}
 * as the application is responsible to render it such that the margins are respected.
 * </li>
 * <li>
 * If document page size is greater than the printed media size the content should
 * be anchored to the upper left corner of the page for left-to-right locales and
 * top right corner for right-to-left locales.
 * </li>
 * </ul>
 *
 * @see #close()
 */
@SuppressLint("UnflaggedApi")
public final class PdfRenderer implements AutoCloseable {
    /** Represents a PDF without form fields */
    @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
    public static final int PDF_FORM_TYPE_NONE = 0;
    /** Represents a PDF with form fields specified using the AcroForm spec */
    @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
    public static final int PDF_FORM_TYPE_ACRO_FORM = 1;
    /** Represents a PDF with form fields specified using the entire XFA spec */
    @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
    public static final int PDF_FORM_TYPE_XFA_FULL = 2;
    /** Represents a PDF with form fields specified using the XFAF subset of the XFA spec */
    @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
    public static final int PDF_FORM_TYPE_XFA_FOREGROUND = 3;

    /**
     * Any call the native pdfium code has to be single threaded as the library does not support
     * parallel use.
     */
    static final Object sPdfiumLock = new Object();

    private static final String TAG = PdfRenderer.class.getSimpleName();
    private final CloseGuard mCloseGuard = new CloseGuard();

    private final int mPageCount;

    private ParcelFileDescriptor mInput;

    private PdfProcessor mPdfProcessor;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private Page mCurrentPage;

    /**
     * Creates a new instance.
     *
     * <p><strong>Note:</strong> The provided file descriptor must be <strong>seekable</strong>,
     * i.e. its data being randomly accessed, e.g. pointing to a file.
     *
     * <p><strong>Note:</strong> This class takes ownership of the passed in file descriptor and is
     * responsible for closing it when the renderer is closed.
     *
     * <p>If the file is from an untrusted source it is recommended to run the renderer in a
     * separate, isolated process with minimal permissions to limit the impact of security exploits.
     *
     * <p>This loads the PDF into the PDF parser, which can be a long-running operation. It's
     * recommended to invoke this on a worker thread.
     *
     * @param fileDescriptor Seekable file descriptor to read from.
     * @throws java.io.IOException If an error occurs while reading the file.
     * @throws java.lang.SecurityException If the file requires a password or the security scheme is
     *     not supported.
     */
    @SuppressLint("UnflaggedApi")
    public PdfRenderer(@NonNull ParcelFileDescriptor fileDescriptor) throws IOException {
        if (fileDescriptor == null) {
            throw new NullPointerException("input cannot be null");
        }
        mInput = fileDescriptor;

        synchronized (sPdfiumLock) {
            try {
                mPdfProcessor = new PdfProcessor();
                mPdfProcessor.create(mInput, null);
                mPageCount = mPdfProcessor.getNumPages();
            } catch (Throwable t) {
                doClose();
                throw t;
            }

        }

        mCloseGuard.open("close");
    }

    private static native long nativeCreate(int fd, long size);

    private static native void nativeClose(long documentPtr);

    private static native int nativeGetPageCount(long documentPtr);

    private static native boolean nativeScaleForPrinting(long documentPtr);

    private static native void nativeRenderPage(long documentPtr, long pagePtr, Bitmap bitmap,
            int clipLeft, int clipTop, int clipRight, int clipBottom, float[] transform,
            int renderMode);

    private static native long nativeOpenPageAndGetSize(long documentPtr, int pageIndex,
            Point outSize);

    private static native void nativeClosePage(long pagePtr);

    /**
     * Closes this renderer. You should not use this instance
     * after this method is called.
     */
    @SuppressLint("UnflaggedApi")
    public void close() {
        throwIfClosed();
        throwIfPageOpened();
        doClose();
    }

    /**
     * Gets the number of pages in the document.
     *
     * @return The page count.
     */
    @SuppressLint("UnflaggedApi")
    public int getPageCount() {
        throwIfClosed();
        return mPageCount;
    }

    /**
     * Gets whether the document prefers to be scaled for printing.
     * You should take this info account if the document is rendered
     * for printing and the target media size differs from the page
     * size.
     *
     * @return If to scale the document.
     */
    @SuppressLint("UnflaggedApi")
    public boolean shouldScaleForPrinting() {
        throwIfClosed();

        synchronized (sPdfiumLock) {
            return mPdfProcessor.scaleForPrinting();
        }
    }

    /**
     * Opens a page for rendering.
     *
     * @param index The page index, starting from 0.
     * @return A page that can be rendered.
     * @throws IllegalStateException is a page is already opened, or if this renderer is already
     *     closed
     * @throws IllegalArgumentException if the page is not in the document
     */
    @SuppressLint("UnflaggedApi")
    @NonNull
    public Page openPage(int index) {
        throwIfClosed();
        throwIfPageOpened();
        throwIfPageNotInDocument(index);
        mCurrentPage = new Page(index);
        return mCurrentPage;
    }

    /**
     * Returns the form type of the loaded PDF
     *
     * @throws IllegalStateException if the renderer is closed
     * @throws IllegalArgumentException if an unexpected PDF form type is returned
     */
    @PdfFormType
    @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
    public int getPdfFormType() {
        throwIfClosed();
        synchronized (sPdfiumLock) {
            int pdfFormType = mPdfProcessor.getPdfFormType();
            if (pdfFormType == PDF_FORM_TYPE_ACRO_FORM) {
                return PDF_FORM_TYPE_ACRO_FORM;
            } else if (pdfFormType == PDF_FORM_TYPE_XFA_FULL) {
                return PDF_FORM_TYPE_XFA_FULL;
            } else if (pdfFormType == PDF_FORM_TYPE_XFA_FOREGROUND) {
                return PDF_FORM_TYPE_XFA_FOREGROUND;
            } else {
                return PDF_FORM_TYPE_NONE;
            }
        }
    }

    @Override
    @SuppressLint("GenericException")
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            doClose();
        } finally {
            super.finalize();
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void doClose() {
        if (mCurrentPage != null) {
            mCurrentPage.close();
            mCurrentPage = null;
        }

        synchronized (sPdfiumLock) {
            mPdfProcessor.ensurePdfDestroyed();
        }

        if (mInput != null) {
            closeQuietly(mInput);
            mInput = null;
        }
        mPdfProcessor = null;
        mCloseGuard.close();
    }

    private void closeQuietly(@NonNull AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (RuntimeException rethrown) {
            throw rethrown;
        } catch (Exception ignored) {
            Log.w(TAG, "close operation failed.");
        }
    }

    private void throwIfClosed() {
        if (mPdfProcessor == null) {
            throw new IllegalStateException("Already closed");
        }
    }

    private void throwIfPageOpened() {
        if (mCurrentPage != null) {
            throw new IllegalStateException("Current page not closed");
        }
    }

    private void throwIfPageNotInDocument(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= mPageCount) {
            throw new IllegalArgumentException("Invalid page index");
        }
    }

    /** @hide */
    @IntDef({
        PDF_FORM_TYPE_NONE,
        PDF_FORM_TYPE_ACRO_FORM,
        PDF_FORM_TYPE_XFA_FULL,
        PDF_FORM_TYPE_XFA_FOREGROUND
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PdfFormType {}

    /** @hide */
    @IntDef({
            Page.RENDER_MODE_FOR_DISPLAY,
            Page.RENDER_MODE_FOR_PRINT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RenderMode {
    }

    /**
     * This class represents a PDF document page for rendering.
     */
    @SuppressLint("UnflaggedApi")
    public final class Page implements AutoCloseable {

        /**
         * Mode to render the content for display on a screen.
         */
        @SuppressLint("UnflaggedApi")
        public static final int RENDER_MODE_FOR_DISPLAY = 1;
        /**
         * Mode to render the content for printing.
         */
        @SuppressLint("UnflaggedApi")
        public static final int RENDER_MODE_FOR_PRINT = 2;
        private final CloseGuard mCloseGuard = new CloseGuard();
        private final int mIndex;
        private final int mWidth;
        private final int mHeight;


        private Page(int index) {
            mIndex = index;
            synchronized (sPdfiumLock) {
                mPdfProcessor.retainPage(mIndex);
                mWidth = mPdfProcessor.getPageWidth(mIndex);
                mHeight = mPdfProcessor.getPageHeight(mIndex);
            }

            mCloseGuard.open("close");
        }

        /**
         * Gets the page index.
         *
         * @return The index.
         */
        @SuppressLint("UnflaggedApi")
        public int getIndex() {
            return mIndex;
        }

        /**
         * Gets the page width in points (1/72").
         *
         * @return The width in points.
         */
        @SuppressLint("UnflaggedApi")
        public int getWidth() {
            return mWidth;
        }

        /**
         * Gets the page height in points (1/72").
         *
         * @return The height in points.
         */
        @SuppressLint("UnflaggedApi")
        public int getHeight() {
            return mHeight;
        }

        /**
         * Renders a page to a bitmap.
         * <p>
         * You may optionally specify a rectangular clip in the bitmap bounds. No rendering
         * outside the clip will be performed, hence it is your responsibility to initialize
         * the bitmap outside the clip.
         * </p>
         * <p>
         * You may optionally specify a matrix to transform the content from page coordinates
         * which are in points (1/72") to bitmap coordinates which are in pixels. If this
         * matrix is not provided this method will apply a transformation that will fit the
         * whole page to the destination clip if provided or the destination bitmap if no
         * clip is provided.
         * </p>
         * <p>
         * The clip and transformation are useful for implementing tile rendering where the
         * destination bitmap contains a portion of the image, for example when zooming.
         * Another useful application is for printing where the size of the bitmap holding
         * the page is too large and a client can render the page in stripes.
         * </p>
         * <p>
         * <strong>Note: </strong> The destination bitmap format must be
         * {@link Config#ARGB_8888 ARGB}.
         * </p>
         * <p>
         * <strong>Note: </strong> The optional transformation matrix must be affine as per
         * {@link android.graphics.Matrix#isAffine() Matrix.isAffine()}. Hence, you can specify
         * rotation, scaling, translation but not a perspective transformation.
         * </p>
         *
         * @param destination Destination bitmap to which to render.
         * @param destClip    Optional clip in the bitmap bounds.
         * @param transform   Optional transformation to apply when rendering.
         * @param renderMode  The render mode.
         * @see #RENDER_MODE_FOR_DISPLAY
         * @see #RENDER_MODE_FOR_PRINT
         */
        @SuppressLint("UnflaggedApi")
        public void render(@NonNull Bitmap destination, @Nullable Rect destClip,
                @Nullable Matrix transform, @RenderMode int renderMode) {
            synchronized (sPdfiumLock) {
                mPdfProcessor.renderPage(
                        mIndex,
                        destination,
                        destClip,
                        transform,
                        new RenderParams.Builder(renderMode).build());
            }
        }

        /**
         * Returns information about all form widgets on the page, or an empty list if there are no
         * form widgets on the page.
         *
         * @throws IllegalStateException if the renderer or page is closed
         */
        @androidx.annotation.NonNull
        @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
        public List<FormWidgetInfo> getFormWidgetInfos() {
            return getFormWidgetInfos(new HashSet<>());
        }

        /**
         * Returns information about all form widgets on the page, or an empty list if there are no
         * form widgets on the page.
         *
         * @param types the types of form widgets to return
         * @throws IllegalStateException if the renderer or page is closed
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
        public List<FormWidgetInfo> getFormWidgetInfos(
                @NonNull @FormWidgetInfo.WidgetType Set<Integer> types) {
            throwIfDocumentOrPageClosed();
            synchronized (sPdfiumLock) {
                return mPdfProcessor.getFormWidgetInfos(mIndex, types);
            }
        }

        /**
         * Returns information about the widget with {@code widgetIndex}.
         *
         * @param widgetIndex the index of the widget within the page's "Annot" array in the PDF
         *     document, available on results of previous calls to {@link #getFormWidgetInfos(Set)}
         *     or {@link #getFormWidgetInfoAtPosition(int, int)} via {@link
         *     FormWidgetInfo#getWidgetIndex()}.
         * @throws IllegalArgumentException if there is no form widget at the provided index.
         * @throws IllegalStateException if the renderer or page is closed
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
        public FormWidgetInfo getFormWidgetInfoAtIndex(int widgetIndex) {
            throwIfDocumentOrPageClosed();
            synchronized (sPdfiumLock) {
                return mPdfProcessor.getFormWidgetInfoAtIndex(mIndex, widgetIndex);
            }
        }

        /**
         * Returns information about the widget at the given point.
         *
         * @param x the x position of the widget on the page, in points
         * @param y the y position of the widget on the page, in points
         * @throws IllegalArgumentException if there is no form widget at the provided position.
         * @throws IllegalStateException if the renderer or page is closed
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
        public FormWidgetInfo getFormWidgetInfoAtPosition(int x, int y) {
            throwIfDocumentOrPageClosed();
            synchronized (sPdfiumLock) {
                return mPdfProcessor.getFormWidgetInfoAtPosition(mIndex, x, y);
            }
        }

        /**
         * Applies a {@link FormEditRecord} to the PDF.
         *
         * <p>Apps must call {@link #render(Bitmap, Rect, Matrix, RenderParams)} to render new
         * bitmaps for the corresponding areas of the page.
         *
         * <p>For click type {@link FormEditRecord}s, performs a click on {@link
         * FormEditRecord#getClickPoint()}
         *
         * <p>For set text type {@link FormEditRecord}s, sets the text value of the form widget.
         *
         * <p>For set indices type {@link FormEditRecord}s, sets the {@link
         * FormEditRecord#getSelectedIndices()} as selected and all others as unselected for the
         * form widget indicated by the record.
         *
         * @param editRecord the {@link FormEditRecord} to be applied
         * @return Rectangular areas of the page bitmap that have been invalidated by this action.
         * @throws IllegalArgumentException if the provided {@link FormEditRecord} is not applicable
         *     to the widget indicated by the index (e.g. a set indices type record contains an
         *     index that corresponds to push button widget, or if the index does not correspond to
         *     a form widget on the page).
         * @throws IllegalStateException If the document is already closed.
         * @throws IllegalStateException If the page is already closed.
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
        public List<Rect> applyEdit(@NonNull FormEditRecord editRecord) {
            throwIfDocumentOrPageClosed();
            synchronized (sPdfiumLock) {
                return mPdfProcessor.applyEdit(mIndex, editRecord);
            }
        }

        /**
         * Applies the {@link FormEditRecord}s to the page, in order.
         *
         * <p><strong>Note: </strong>Re-rendering the page via {@link #render(Bitmap, Rect, Matrix,
         * RenderParams)} is required after calling this method. Applying edits to form widgets will
         * change the appearance of the page.
         *
         * <p>If any record cannot be applied, it will be returned and no further records will be
         * applied. Records already applied will not be reverted. To restore the page to its state
         * before any records were applied, re-load the page via {@link #close()} and {@link
         * #openPage(int)}.
         *
         * @param formEditRecords the {@link FormEditRecord}s to be applied
         * @return the records that could not be applied, or an empty list if all were applied
         * @throws IllegalStateException If the document is already closed.
         * @throws IllegalStateException If the page is already closed.
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_ENABLE_FORM_FILLING)
        public List<FormEditRecord> applyEdits(@NonNull List<FormEditRecord> formEditRecords) {
            throwIfDocumentOrPageClosed();
            synchronized (sPdfiumLock) {
                return mPdfProcessor.applyEdits(mIndex, formEditRecords);
            }
        }

        /**
         * Closes this page.
         *
         * @see android.graphics.pdf.PdfRenderer#openPage(int)
         */
        @SuppressLint("UnflaggedApi")
        @Override
        public void close() {
            throwIfDocumentOrPageClosed();
            doClose();
        }

        @Override
        @SuppressLint("GenericException")
        protected void finalize() throws Throwable {
            try {
                if (mCloseGuard != null) {
                    mCloseGuard.warnIfOpen();
                }

                doClose();
            } finally {
                super.finalize();
            }
        }

        private void doClose() {
            synchronized (sPdfiumLock) {
                mPdfProcessor.releasePage(mIndex);
            }

            mCloseGuard.close();
            mCurrentPage = null;
        }

        private void throwIfDocumentOrPageClosed() {
            PdfRenderer.this.throwIfClosed();
            throwIfClosed();
        }

        private void throwIfClosed() {
            if (mCurrentPage == null) {
                throw new IllegalStateException("Already closed");
            }
        }
    }
}
