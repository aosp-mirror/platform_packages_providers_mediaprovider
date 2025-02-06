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

package com.android.providers.media;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.provider.IMPCancellationSignal;
import android.provider.IOpenAssetFileCallback;
import android.provider.IOpenFileCallback;
import android.provider.MediaStore;
import android.provider.OpenAssetFileRequest;
import android.provider.OpenFileRequest;
import android.provider.ParcelableException;
import android.util.Log;

import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.StringUtils;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility class used to open picker files asynchronously.
 * It manages a {@link ThreadPoolExecutor} that is being used to schedule
 * pending open file requests.
 */
public class AsyncPickerFileOpener {
    private static final String TAG = "AsyncPickerFileOpener";
    private static final int THREAD_POOL_SIZE = 8;

    private static Executor sExecutor;

    private final MediaProvider mMediaProvider;
    private final PickerUriResolver mPickerUriResolver;

    public AsyncPickerFileOpener(@NonNull MediaProvider mediaProvider,
            @NonNull PickerUriResolver pickerUriResolver) {
        mMediaProvider = mediaProvider;
        mPickerUriResolver = pickerUriResolver;
    }

    /**
     * Schedules a new open file request to open the requested file asynchronously.
     * It validates that the request is valid and the requester has access before enqueueing
     * the request in the thread pool
     */
    public void scheduleOpenFileAsync(@NonNull OpenFileRequest request,
            @NonNull LocalCallingIdentity callingIdentity) {
        Log.i(TAG, "Async open file request created for " + request.getUri());

        mPickerUriResolver.checkPermissionForRequireOriginalQueryParam(request.getUri(),
                callingIdentity);
        mPickerUriResolver.checkUriPermission(request.getUri(), callingIdentity.pid,
                callingIdentity.uid);

        ensureExecutor();
        sExecutor.execute(() -> openFileAsync(request, callingIdentity));
    }

    private void openFileAsync(@NonNull OpenFileRequest request,
            @NonNull LocalCallingIdentity callingIdentity) {
        final IMPCancellationSignal iCancellationSignal = request.getCancellationSignal();
        final CancellationSignal cancellationSignal = iCancellationSignal != null
                ? ((MPCancellationSignal) iCancellationSignal).mCancellationSignal
                // explicitly create cancellation signal to help in case of caller death
                : new CancellationSignal();

        final IOpenFileCallback callback = request.getCallback();
        try {
            // cancel the operation in case the requester has died
            callback.asBinder().linkToDeath(cancellationSignal::cancel, 0);
        } catch (RemoteException e) {
            Log.d(TAG, "Caller with uid " + callingIdentity.uid + " that requested opening "
                    + request.getUri() + " has died already");
            return;
        }

        final int tid = Process.myTid();
        mMediaProvider.addToPendingOpenMap(tid, callingIdentity.uid);

        ParcelFileDescriptor pfd = null;
        try {
            cancellationSignal.throwIfCanceled();
            pfd = mPickerUriResolver.openFile(
                    request.getUri(), "r", cancellationSignal, callingIdentity);
            callback.onSuccess(pfd);
        } catch (RemoteException ignore) {
            // ignore remote Exception as it means that the requester has died
        } catch (Exception e) {
            try {
                Log.e(TAG, "Open file operation failed. Failed to open " + request.getUri(), e);
                callback.onFailure(new ParcelableException(e));
            } catch (RemoteException ignore) {
                // ignore remote exception as it means the requester has died
            }
        }  finally {
            if (pfd != null) {
                // Closing the file descriptor on this side as Binder will dup it
                FileUtils.closeQuietly(pfd);
            }
            mMediaProvider.removeFromPendingOpenMap(tid);
        }
    }

    /**
     * Schedules a new open asset file request to open the requested file asynchronously.
     * It validates that the request is valid and the requester has access before enqueueing
     * the request in the thread pool
     */
    public void scheduleOpenAssetFileAsync(@NonNull OpenAssetFileRequest request,
            @NonNull LocalCallingIdentity callingIdentity) {
        Log.i(TAG, "Async open asset file request created for " + request.getUri());

        mPickerUriResolver.checkPermissionForRequireOriginalQueryParam(request.getUri(),
                callingIdentity);
        mPickerUriResolver.checkUriPermission(request.getUri(), callingIdentity.pid,
                callingIdentity.uid);

        ensureExecutor();
        sExecutor.execute(() -> openAssetFileAsync(request, callingIdentity));
    }

    private void openAssetFileAsync(@NonNull OpenAssetFileRequest request,
            @NonNull LocalCallingIdentity callingIdentity) {
        final IMPCancellationSignal iCancellationSignal = request.getCancellationSignal();
        final CancellationSignal cancellationSignal = iCancellationSignal != null
                ? ((MPCancellationSignal) iCancellationSignal).mCancellationSignal
                // explicitly create cancellation signal to help in case of caller death
                : new CancellationSignal();

        final IOpenAssetFileCallback callback = request.getCallback();
        try {
            // cancel the operation in case the requester has died
            callback.asBinder().linkToDeath(cancellationSignal::cancel, 0);
        } catch (RemoteException e) {
            Log.d(TAG, "Caller with uid " + request.getUri() + " that requested opening "
                    + request.getUri() + " has died already");
            return;
        }

        final Bundle opts = request.getOpts();
        final boolean wantsThumb = (opts != null) && opts.containsKey(ContentResolver.EXTRA_SIZE)
                && StringUtils.startsWithIgnoreCase(request.getMimeType(), "image/");

        if (opts != null) {
            opts.remove(MediaStore.EXTRA_MODE);
        }

        final int tid = Process.myTid();
        mMediaProvider.addToPendingOpenMap(tid, callingIdentity.uid);
        AssetFileDescriptor afd = null;
        try {
            cancellationSignal.throwIfCanceled();
            afd = mPickerUriResolver.openTypedAssetFile(
                    request.getUri(), request.getMimeType(), opts, cancellationSignal,
                    callingIdentity, wantsThumb);
            callback.onSuccess(afd);
        } catch (RemoteException ignore) {
            // ignore remote Exception as it means that the requester has died
        } catch (Exception e) {
            Log.e(TAG, "Open file operation failed. Failed to open " + request.getUri(), e);
            try {
                callback.onFailure(new ParcelableException(e));
            } catch (RemoteException ignore) {
                // ignore remote Exception as it means that the requester has died
            }
        }  finally {
            if (afd != null) {
                // Closing the file descriptor on this side as Binder will dup it
                FileUtils.closeQuietly(afd);
            }
            mMediaProvider.removeFromPendingOpenMap(tid);
        }
    }

    private static void ensureExecutor() {
        synchronized (AsyncPickerFileOpener.class) {
            if (sExecutor == null) {
                sExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE, new ThreadFactory() {
                    final AtomicInteger mCount = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(
                                r, "AsyncPickerFileOpener#" + mCount.getAndIncrement());
                    }
                });
            }
        }
    }
}
