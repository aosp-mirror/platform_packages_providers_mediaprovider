/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media.fuse;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import com.android.internal.util.Preconditions;
import com.android.providers.media.MediaProvider;

/**
 * Starts a FUSE session to handle FUSE messages from the kernel.
 */
public final class FuseDaemon implements Runnable {
    public static final String TAG = "FuseDaemon";

    private final Object mLock = new Object();
    private final ExternalStorageServiceImpl mService;
    private final String mSessionId;
    private final int mFuseDeviceFd;
    private final String mPath;
    private long mNativeFuseDaemon;

    public FuseDaemon(@NonNull ContentResolver resolver, @NonNull String sessionId,
            @NonNull ExternalStorageServiceImpl service, @NonNull ParcelFileDescriptor fd,
            @NonNull String path) {
        Preconditions.checkNotNull(resolver);
        mService = Preconditions.checkNotNull(service);
        mSessionId = Preconditions.checkNotNull(sessionId);;
        mFuseDeviceFd = Preconditions.checkNotNull(fd).detachFd();
        mPath = Preconditions.checkNotNull(path);

        try (ContentProviderClient cpc =
                resolver.acquireContentProviderClient(MediaStore.AUTHORITY)) {
            mNativeFuseDaemon = native_new((MediaProvider) cpc.getLocalContentProvider());
        } catch (OperationCanceledException e) {
            throw new IllegalStateException("Failed to acquire content provider", e);
        }
    }

    /** Starts a FUSE session. Does not return until {@link #stop} is called. */
    @Override
    public void run() {
        native_start(mNativeFuseDaemon, mFuseDeviceFd, mPath);
        mService.onEndSession(mSessionId);
    }

    /** Stops any running FUSE sessions, causing {@link #run} to return. */
    public void stop() {
        native_stop(mNativeFuseDaemon);
    }

    // TODO(b/135341433): Don't use finalizer. Consider Cleaner and PhantomReference
    @Override
    public void finalize() throws Throwable {
        synchronized (mLock) {
            if (mNativeFuseDaemon != 0) {
                native_delete(mNativeFuseDaemon);
                mNativeFuseDaemon = 0;
            }
        }
    }

    private native long native_new(MediaProvider mediaProvider);
    private native void native_start(long daemon, int deviceFd, String path);
    private native void native_stop(long daemon);
    private native void native_delete(long daemon);
}
