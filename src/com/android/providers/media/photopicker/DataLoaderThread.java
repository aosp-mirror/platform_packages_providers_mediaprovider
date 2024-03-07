/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media.photopicker;

import android.os.Handler;
import android.os.HandlerThread;

import com.android.modules.utils.HandlerExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Thread for asynchronous event processing. This thread is configured as
 * {@link android.os.Process#THREAD_PRIORITY_FOREGROUND}, which means more CPU
 * resources will be dedicated to it, and it will be treated like "a user
 * interface that the user is interacting with."
 * <p>
 * This thread is best suited for UI related tasks that the user is actively waiting for.
 * (like data loading on grid, banner initialization etc.)
 *
 */
public class DataLoaderThread extends HandlerThread {
    private static DataLoaderThread sInstance;
    private static Handler sHandler;
    private static HandlerExecutor sHandlerExecutor;

    // Token for cancelling tasks in handler's queue. Can be used with Handler#postDelayed.
    public static Object TOKEN = new Object();

    public DataLoaderThread() {
        super("DataLoaderThread", android.os.Process.THREAD_PRIORITY_FOREGROUND);
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new DataLoaderThread();
            sInstance.start();
            sHandler = new Handler(sInstance.getLooper());
            sHandlerExecutor = new HandlerExecutor(sHandler);
        }
    }

    /**
     * Return singleton instance of DataLoaderThread.
     */
    public static DataLoaderThread get() {
        synchronized (DataLoaderThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    /**
     * Return singleton handler of DataLoaderThread.
     */
    public static Handler getHandler() {
        synchronized (DataLoaderThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }

    /**
     * Return singleton executor of DataLoaderThread.
     */
    public static Executor getExecutor() {
        synchronized (DataLoaderThread.class) {
            ensureThreadLocked();
            return sHandlerExecutor;
        }
    }

    /**
     * Wait for thread to be idle.
     */
    public static void waitForIdle() {
        final CountDownLatch latch = new CountDownLatch(1);
        getExecutor().execute(() -> {
            latch.countDown();
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
