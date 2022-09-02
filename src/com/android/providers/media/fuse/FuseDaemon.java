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

import com.android.internal.annotations.GuardedBy;
import com.android.providers.media.FdAccessResult;
import com.android.providers.media.MediaProvider;
import com.android.providers.media.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Starts a FUSE session to handle FUSE messages from the kernel.
 */
public final class FuseDaemon extends Thread {
    public static final String TAG = "FuseDaemonThread";
    private static final int POLL_INTERVAL_MS = 1000;
    private static final int POLL_COUNT = 5;

    private final Object mLock = new Object();
    private final MediaProvider mMediaProvider;
    private final int mFuseDeviceFd;
    private final String mPath;
    private final boolean mUncachedMode;
    private final String[] mSupportedTranscodingRelativePaths;
    private final String[] mSupportedUncachedRelativePaths;
    private final ExternalStorageServiceImpl mService;
    @GuardedBy("mLock")
    private long mPtr;

    public FuseDaemon(@NonNull MediaProvider mediaProvider,
            @NonNull ExternalStorageServiceImpl service, @NonNull ParcelFileDescriptor fd,
            @NonNull String sessionId, @NonNull String path, boolean uncachedMode,
            String[] supportedTranscodingRelativePaths, String[] supportedUncachedRelativePaths) {
        mMediaProvider = Objects.requireNonNull(mediaProvider);
        mService = Objects.requireNonNull(service);
        setName(Objects.requireNonNull(sessionId));
        mFuseDeviceFd = Objects.requireNonNull(fd).detachFd();
        mPath = Objects.requireNonNull(path);
        mUncachedMode = uncachedMode;
        mSupportedTranscodingRelativePaths
                = Objects.requireNonNull(supportedTranscodingRelativePaths);
        mSupportedUncachedRelativePaths
                = Objects.requireNonNull(supportedUncachedRelativePaths);
    }

    /** Starts a FUSE session. Does not return until the lower filesystem is unmounted. */
    @Override
    public void run() {
        final long ptr;
        synchronized (mLock) {
            mPtr = native_new(mMediaProvider);
            if (mPtr == 0) {
                throw new IllegalStateException("Unable to create native FUSE daemon");
            }
            ptr = mPtr;
        }

        Log.i(TAG, "Starting thread for " + getName() + " ...");
        native_start(ptr, mFuseDeviceFd, mPath, mUncachedMode,
                mSupportedTranscodingRelativePaths,
                mSupportedUncachedRelativePaths); // Blocks
        Log.i(TAG, "Exiting thread for " + getName() + " ...");

        synchronized (mLock) {
            native_delete(mPtr);
            mPtr = 0;
        }
        mService.onExitSession(getName());
        Log.i(TAG, "Exited thread for " + getName());
    }

    @Override
    public synchronized void start() {
        super.start();

        // Wait for native_start
        waitForStart();

        // Initialize device id
        initializeDeviceId();
    }

    private void initializeDeviceId() {
        synchronized (mLock) {
            if (mPtr == 0) {
                Log.e(TAG, "initializeDeviceId failed, FUSE daemon unavailable");
                return;
            }
            String path = FileUtils.toFuseFile(new File(mPath)).getAbsolutePath();
            native_initialize_device_id(mPtr, path);
        }
    }

    private void waitForStart() {
        int count = POLL_COUNT;
        while (count-- > 0) {
            synchronized (mLock) {
                if (mPtr != 0 && native_is_started(mPtr)) {
                    return;
                }
            }
            try {
                Log.v(TAG, "Waiting " + POLL_INTERVAL_MS + "ms for FUSE start. Count " + count);
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "Interrupted while starting FUSE", e);
            }
        }
        throw new IllegalStateException("Failed to start FUSE");
    }

    /** Waits for any running FUSE sessions to return. */
    public void waitForExit() {
        int waitMs = POLL_COUNT * POLL_INTERVAL_MS;
        Log.i(TAG, "Waiting " + waitMs + "ms for FUSE " + getName() + " to exit...");

        try {
            join(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while terminating FUSE " + getName());
        }

        if (isAlive()) {
            throw new IllegalStateException("Failed to exit FUSE " + getName() + " successfully");
        }

        Log.i(TAG, "Exited FUSE " + getName() + " successfully");
    }

    /**
     * Checks if file with {@code path} should be opened via FUSE to avoid cache inconcistencies.
     * May place a F_RDLCK or F_WRLCK with fcntl(2) depending on {@code readLock}
     *
     * @return {@code true} if the file should be opened via FUSE, {@code false} otherwise
     */
    public boolean shouldOpenWithFuse(String path, boolean readLock, int fd) {
        synchronized (mLock) {
            if (mPtr == 0) {
                Log.i(TAG, "shouldOpenWithFuse failed, FUSE daemon unavailable");
                return false;
            }
            return native_should_open_with_fuse(mPtr, path, readLock, fd);
        }
    }

    /**
     * Checks if the FuseDaemon uses the FUSE passthrough feature.
     *
     * @return {@code true} if the FuseDaemon uses FUSE passthrough, {@code false} otherwise
     */
    public boolean usesFusePassthrough() {
        synchronized (mLock) {
            if (mPtr == 0) {
                Log.i(TAG, "usesFusePassthrough failed, FUSE daemon unavailable");
                return false;
            }
            return native_uses_fuse_passthrough(mPtr);
        }
    }

    /**
     * Invalidates FUSE VFS dentry cache for {@code path}
     */
    public void invalidateFuseDentryCache(String path) {
        synchronized (mLock) {
            if (mPtr == 0) {
                Log.i(TAG, "invalidateFuseDentryCache failed, FUSE daemon unavailable");
                return;
            }
            native_invalidate_fuse_dentry_cache(mPtr, path);
        }
    }

    public FdAccessResult checkFdAccess(ParcelFileDescriptor fileDescriptor, int uid)
            throws IOException {
        synchronized (mLock) {
            if (mPtr == 0) {
                throw new IOException("FUSE daemon unavailable");
            }
            return native_check_fd_access(mPtr, fileDescriptor.getFd(), uid);
        }
    }

    private native long native_new(MediaProvider mediaProvider);

    // Takes ownership of the passed in file descriptor!
    private native void native_start(long daemon, int deviceFd, String path,
            boolean uncachedMode, String[] supportedTranscodingRelativePaths,
            String[] supportedUncachedRelativePaths);

    private native void native_delete(long daemon);
    private native boolean native_should_open_with_fuse(long daemon, String path, boolean readLock,
            int fd);
    private native boolean native_uses_fuse_passthrough(long daemon);
    private native void native_invalidate_fuse_dentry_cache(long daemon, String path);
    private native boolean native_is_started(long daemon);
    private native FdAccessResult native_check_fd_access(long daemon, int fd, int uid);
    private native void native_initialize_device_id(long daemon, String path);
    public static native boolean native_is_fuse_thread();
}
