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

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Utility to share {@link Bitmap}s across processes using a {@link android.os.Parcelable}
 * reference
 * that can fit safely in an Intent.
 *
 * <p>A {@link BitmapParcel} wraps a {@link Bitmap} instance and exposes an output file descriptor
 * that can be used to fill in the bytes of the wrapped bitmap from any process.
 *
 * <p>Uses a piped file descriptor, and natively reads and copies bytes from the source end into
 * the
 * {@link Bitmap}'s byte buffer. Runs on a new Thread.
 *
 * <p>Note: Only one-way transfers are implemented (write into a bitmap from any source).
 * <p>This class takes ownership and is responsible for releasing the threads and the resources when
 * closed.</p>
 *
 * @hide
 */
public class BitmapParcel implements AutoCloseable {

    public static final String TAG = "BitmapParcel";

    private static final String RECEIVING_THREAD_NAME = "BitmapParcel-native.receiveAsync";

    private static final int THREAD_TIMEOUT_SECONDS = 5;

    private final Bitmap mBitmap;

    private CountDownLatch mReadThreadLatch;

    /**
     * Creates a {@link BitmapParcel} that allows writing bytes into the given {@link Bitmap}.
     *
     * @param bitmap the destination bitmap: its contents will be replaced by what is sent on the
     *               fd.
     */
    public BitmapParcel(Bitmap bitmap) {
        this.mBitmap = bitmap;
    }

    /**
     * Reads bytes from the given file descriptor and fill in the Bitmap instance.
     *
     * @param bitmap   A bitmap whose pixels to populate.
     * @param sourceFd The source file descriptor. Will be closed after transfer.
     */
    private static native boolean readIntoBitmap(Bitmap bitmap, int sourceFd);

    /** Opens a file descriptor that will write into the wrapped bitmap. */
    @Nullable
    public ParcelFileDescriptor openOutputFd() {
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Log.e(TAG, "Error occurred while creating pipe from fd", e);
            return null;
        }
        ParcelFileDescriptor source = pipe[0];
        ParcelFileDescriptor sink = pipe[1];
        receiveAsync(source);
        return sink;
    }

    /** Receives the bitmap's bytes from a file descriptor. Runs on a new thread. */
    private void receiveAsync(final ParcelFileDescriptor source) {
        mReadThreadLatch = new CountDownLatch(1);
        new Thread(
                () -> {
                    readIntoBitmap(mBitmap, source.detachFd());
                    mReadThreadLatch.countDown();
                },
                RECEIVING_THREAD_NAME)
                .start();
    }

    /**
     * Terminates any running copy and close all resources. In case this method fails, the caller
     * will need to handle retries, no-op, etc.
     *
     * @throws InterruptedException If the operation is interrupted.
     * @throws TimeoutException     If the operation times out.
     */
    @Override
    public void close() throws InterruptedException, TimeoutException {
        if (mReadThreadLatch != null) {
            boolean timedOut = false;
            timedOut = !mReadThreadLatch.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (timedOut) {
                throw new TimeoutException(String.format("Reading thread took more than %d seconds",
                        THREAD_TIMEOUT_SECONDS));
            }
        }
    }
}
