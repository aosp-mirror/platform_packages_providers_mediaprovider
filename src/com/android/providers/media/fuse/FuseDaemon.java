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

import android.annotation.NonNull;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.android.internal.util.Preconditions;
import com.android.providers.media.MediaProvider;

/**
 * Starts a FUSE session to handle FUSE messages from the kernel.
 */
public final class FuseDaemon implements Runnable {
    public static final String TAG = "FuseDaemon";

    private final Object mLock = new Object();
    private final int mFuseDeviceFd;
    private final String mUpperPath;
    private final String mLowerPath;
    private long mNativeFuseDaemon;

    public FuseDaemon(@NonNull ContentResolver resolver, @NonNull ParcelFileDescriptor fd,
            @NonNull String upperPath, @NonNull String lowerPath) {
        Preconditions.checkNotNull(fd);
        Preconditions.checkNotNull(upperPath);
        Preconditions.checkNotNull(lowerPath);

        mFuseDeviceFd = fd.detachFd();
        mUpperPath = upperPath;
        mLowerPath = lowerPath;
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
        // TODO(b/135341433): Ensure no overlap between lower and upper path
        native_start(mNativeFuseDaemon, mFuseDeviceFd, mUpperPath, mLowerPath);
        // TODO(b/135341433): Remove from mFuseDaemons in ExternalStorageServiceImpl and #stopSelf
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
    private native void native_start(long daemon, int deviceFd, String upperPath, String lowerPath);
    private native void native_stop(long daemon);
    private native void native_delete(long daemon);
}
