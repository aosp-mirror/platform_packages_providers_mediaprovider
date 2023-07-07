/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.os.Process.THREAD_PRIORITY_FOREGROUND;
import static android.os.Process.setThreadPriority;

import static java.util.Objects.requireNonNull;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.tracing.Trace;

import com.android.providers.media.R;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Responsible for "preloading" selected media items including showing the appropriate UI
 * ({@link ProgressDialog}).
 *
 * @see #preload(Context, List)
 */
class SelectedMediaPreloader {
    private static final String TRACE_SECTION_NAME = "preload-selected-media";
    private static final String TAG = "SelectedMediaPreloader";
    private static final boolean DEBUG = false;

    @Nullable
    private static volatile Executor sExecutor;

    @NonNull
    private final List<Uri> mItems;
    private final int mCount;
    @NonNull
    private final AtomicInteger mFinishedCount = new AtomicInteger(0);
    @NonNull
    private final MutableLiveData<Integer> mFinishedCountLiveData = new MutableLiveData<>(0);
    @NonNull
    private final MutableLiveData<Boolean> mIsFinishedLiveData = new MutableLiveData<>(false);
    @NonNull
    private final ContentResolver mContentResolver;

    /**
     * Creates, start and eventually returns a new {@link SelectedMediaPreloader} instance.
     * Additionally, creates and shows an {@link AlertDialog} which displays the progress
     * (e.g. "X out of Y ready."), and is automatically dismissed when preloading is fully finished.
     * @return a new (and {@link #start(Executor)} "started") {@link SelectedMediaPreloader}.
     */
    @UiThread
    @NonNull
    static SelectedMediaPreloader preload(
            @NonNull Activity activity, @NonNull List<Uri> selectedMedia) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Must be called from the Main (UI) thread");
        }

        // Make a copy of the list.
        final List<Uri> items = new ArrayList<>(requireNonNull(selectedMedia));
        final int count = items.size();

        Log.d(TAG, "preload() " + count + " items");
        if (DEBUG) {
            Log.v(TAG, "  Items:");
            for (int i = 0; i < count; i++) {
                Log.v(TAG, "    (" + i + ") " + items.get(i));
            }
        }

        final var context = requireNonNull(activity).getApplicationContext();
        final var contentResolver = context.getContentResolver();
        final var preloader = new SelectedMediaPreloader(items, contentResolver);

        Trace.beginAsyncSection(TRACE_SECTION_NAME, /* cookie */ preloader.hashCode());

        final var dialog = createProgressDialog(activity, items);

        preloader.mIsFinishedLiveData.observeForever(new Observer<>() {
            @Override
            public void onChanged(Boolean isFinished) {
                if (isFinished) {
                    preloader.mIsFinishedLiveData.removeObserver(this);
                    dialog.dismiss();

                    Trace.endAsyncSection(TRACE_SECTION_NAME, /* cookie */ preloader.hashCode());
                }
            }
        });
        preloader.mFinishedCountLiveData.observeForever(new Observer<>() {
            @Override
            public void onChanged(Integer finishedCount) {
                if (finishedCount == count) {
                    preloader.mFinishedCountLiveData.removeObserver(this);
                }
                // "X of Y ready"
                final String message = context.getString(
                        R.string.preloading_progress_message, finishedCount, count);
                dialog.setMessage(message);
            }
        });

        ensureExecutor();
        preloader.start(sExecutor);

        return preloader;
    }

    /**
     * The constructor is intentionally {@code private}: clients should use static
     * {@link #preload(Context, List)} method.
     */
    private SelectedMediaPreloader(
            @NonNull List<Uri> items, @NonNull ContentResolver contentResolver) {
        mContentResolver = contentResolver;
        mItems = items;
        mCount = items.size();
    }

    @NonNull
    LiveData<Boolean> getIsFinishedLiveData() {
        return mIsFinishedLiveData;
    }

    /**
     * This method is intentionally {@code private}: clients should use static
     * {@link #preload(Context, List)} method.
     */
    @UiThread
    private void start(@NonNull Executor executor) {
        for (var item : mItems) {
            // Off-loading to an Executor (presumable backed up by a thread pool)
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    openFileDescriptor(item);

                    final int preloadedCount = mFinishedCount.incrementAndGet();
                    if (DEBUG) {
                        Log.d(TAG, "Preloaded " + preloadedCount + " (of " + mCount + ") items");
                    }
                    if (preloadedCount == mCount) {
                        // Don't need to "synchronize" here: mCount is our final value for
                        // preloadedCount, it won't be changing anymore.
                        mIsFinishedLiveData.postValue(true);
                    }

                    // In order to prevent race conditions where we may "post" a lower value after
                    // another has already posted a higher value let's "synchronize", and get
                    // the finished count from the AtomicInt once again.
                    synchronized (this) {
                        mFinishedCountLiveData.postValue(mFinishedCount.get());
                    }
                }
            });
        }
    }

    @Nullable
    private void openFileDescriptor(@NonNull Uri uri) {
        long start = 0;
        if (DEBUG) {
            Log.d(TAG, "openFileDescriptor() START, " + Thread.currentThread() + ", " + uri);
            start = System.currentTimeMillis();
        }

        Trace.beginSection("Preloader.openFd");
        try {
            mContentResolver.openAssetFileDescriptor(uri, "r");
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Could not open FileDescriptor for " + uri, e);
        } finally {
            Trace.endSection();

            if (DEBUG) {
                final long elapsed = System.currentTimeMillis() - start;
                Log.d(TAG, "openFileDescriptor() DONE, took " + humanReadableTimeDuration(elapsed)
                        + ", " + uri);
            }
        }
    }

    @NonNull
    private static AlertDialog createProgressDialog(
            @NonNull Activity activity, @NonNull List<Uri> selectedMedia) {
        return ProgressDialog.show(activity,
                /* tile */ "Preparing your selected media",
                /* message */ "0 of " + selectedMedia.size() + " ready.",
                /* indeterminate */ true);
    }

    private static void ensureExecutor() {
        if (sExecutor == null) {
            synchronized (SelectedMediaPreloader.class) {
                if (sExecutor == null) {
                    final ThreadFactory threadFactory = new ThreadFactory() {

                        final AtomicInteger mCount = new AtomicInteger(1);

                        @Override
                        public Thread newThread(Runnable r) {
                            final String threadName = "preloader#" + mCount.getAndIncrement();
                            if (DEBUG) {
                                Log.d(TAG, "newThread() " + threadName);
                            }

                            return new Thread(r, threadName) {
                                @Override
                                public void run() {
                                    // For now the preloading only starts when the user has made
                                    // the final selection, at which point we show a (not
                                    // dismissible) loading dialog, which, technically, makes the
                                    // preloading a "foreground" task.
                                    // Thus THREAD_PRIORITY_FOREGROUND.
                                    setThreadPriority(THREAD_PRIORITY_FOREGROUND);
                                    super.run();
                                }
                            };
                        }
                    };
                    sExecutor = Executors.newCachedThreadPool(threadFactory);
                }
            }
        }
    }

    @SuppressLint("DefaultLocale")
    @NonNull
    private static String humanReadableTimeDuration(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        return String.format("%.1f s", ms / 1000.0);
    }
}
