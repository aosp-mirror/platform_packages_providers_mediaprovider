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

import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.util.Preconditions;
import com.android.providers.media.MediaProvider;

/**
 * Starts a FUSE session to handle FUSE messages from the kernel.
 */
public final class FuseDaemon extends Thread {
    public static final String TAG = "FuseDaemonThread";

    private final MediaProvider mMediaProvider;
    private final int mFuseDeviceFd;
    private final String mPath;
    private final ExternalStorageServiceImpl mService;

    public FuseDaemon(@NonNull MediaProvider mediaProvider,
            @NonNull ExternalStorageServiceImpl service, @NonNull ParcelFileDescriptor fd,
            @NonNull String sessionId, @NonNull String path) {
        mMediaProvider = Preconditions.checkNotNull(mediaProvider);
        mService = Preconditions.checkNotNull(service);
        setName(Preconditions.checkNotNull(sessionId));
        mFuseDeviceFd = Preconditions.checkNotNull(fd).detachFd();
        mPath = Preconditions.checkNotNull(path);
    }

    /** Starts a FUSE session. Does not return until the lower filesystem is unmounted. */
    @Override
    public void run() {
        long ptr = native_new(mMediaProvider);
        if (ptr == 0) {
            return;
        }

        Log.i(TAG, "Starting thread for " + getName() + " ...");
        native_start(ptr, mFuseDeviceFd, mPath); // Blocks
        Log.i(TAG, "Exiting thread for " + getName() + " ...");

        // Cleanup
        if (ptr != 0) {
            native_delete(ptr);
        }
        mService.onExitSession(getName());
        Log.i(TAG, "Exited thread for " + getName());
    }

    /** Waits for any running FUSE sessions to return. */
    public void waitForExit() {
        Log.i(TAG, "Waiting 5s for thread " + getName() + " to exit...");

        try {
            join(5000);
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for thread " + getName()
                    + " to exit. Terminating process", e);
            System.exit(1);
        }

        if (isAlive()) {
            Log.i(TAG, "Failed to exit thread " + getName()
                    + " successfully. Terminating process");
            System.exit(1);
        }

        Log.i(TAG, "Exited thread " + getName() + " successfully");
    }

    private native long native_new(MediaProvider mediaProvider);
    private native void native_start(long daemon, int deviceFd, String path);
    private native void native_delete(long daemon);
}
