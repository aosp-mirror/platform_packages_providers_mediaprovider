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
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.tracing.Trace;

import com.android.providers.media.R;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Responsible for "preloading" selected media items including showing the appropriate UI
 * ({@link ProgressDialog}).
 *
 * @see #preload(Context, List)
 */
class SelectedMediaPreloader {
    private static final long TIMEOUT_IN_SECONDS = 4L;
    private static final String TRACE_SECTION_NAME = "preload-selected-media";
    private static final String TAG = "SelectedMediaPreloader";
    private static final boolean DEBUG = true;

    @Nullable
    private static volatile Executor sExecutor;

    @NonNull
    private final List<Uri> mItems;
    private final int mCount;
    private boolean mIsPreloadingCancelled = false;
    @NonNull
    private final AtomicInteger mFinishedCount = new AtomicInteger(0);
    @NonNull
    private final MutableLiveData<Integer> mFinishedCountLiveData = new MutableLiveData<>(0);
    @NonNull
    private final MutableLiveData<Boolean> mIsFinishedLiveData = new MutableLiveData<>(false);
    @NonNull
    private static final MutableLiveData<Boolean> mIsPreloadingCancelledLiveData =
            new MutableLiveData<>(false);
    @NonNull
    private final MutableLiveData<List<Integer>> mUnavailableMediaIndexes =
            new MutableLiveData<>(new ArrayList<>());
    @NonNull
    private final ContentResolver mContentResolver;
    private List<Integer> mSuccessfullyPreloadedMediaIndexes = new ArrayList<>();

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
        mIsPreloadingCancelledLiveData.setValue(false);

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

        final var dialog = createProgressDialog(activity, items, context);

        preloader.mIsFinishedLiveData.observeForever(new Observer<>() {
            @Override
            public void onChanged(Boolean isFinished) {
                if (isFinished) {
                    preloader.mIsFinishedLiveData.removeObserver(this);
                    Trace.endAsyncSection(TRACE_SECTION_NAME, /* cookie */ preloader.hashCode());
                }
            }
        });

        preloader.mFinishedCountLiveData.observeForever(new Observer<>() {
            @Override
            public void onChanged(Integer finishedCount) {
                if (finishedCount == count) {
                    preloader.mFinishedCountLiveData.removeObserver(this);
                    dialog.dismiss();
                }
                // "X of Y ready"
                final String message = context.getString(
                        R.string.preloading_progress_message, finishedCount, count);
                dialog.setMessage(message);
            }
        });

        mIsPreloadingCancelledLiveData.observeForever(new Observer<>() {
            @Override
            public void onChanged(Boolean isPreloadingCancelled) {
                if (isPreloadingCancelled) {
                    preloader.mIsPreloadingCancelled = true;
                    mIsPreloadingCancelledLiveData.removeObserver(this);
                    List<Integer> unsuccessfullyPreloadedMediaIndexes = new ArrayList<>();
                    for (int index = 0; index < preloader.mItems.size(); index++) {
                        if (!preloader.mSuccessfullyPreloadedMediaIndexes.contains(index)) {
                            unsuccessfullyPreloadedMediaIndexes.add(index);
                        }
                    }
                    // this extra "-1" element indicates that preloading has been cancelled by
                    // the user
                    unsuccessfullyPreloadedMediaIndexes.add(-1);
                    preloader.mUnavailableMediaIndexes.setValue(
                            unsuccessfullyPreloadedMediaIndexes);
                    preloader.mIsFinishedLiveData.setValue(false);
                    preloader.mFinishedCountLiveData.setValue(preloader.mItems.size());
                }
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

    @NonNull
    LiveData<List<Integer>> getUnavailableMediaIndexes() {
        return mUnavailableMediaIndexes;
    }

    /**
     * This method is intentionally {@code private}: clients should use static
     * {@link #preload(Context, List)} method.
     */
    @UiThread
    private void start(@NonNull Executor executor) {
        List<Integer> unavailableMediaIndexes = new ArrayList<>();
        for (int index = 0; index < mItems.size(); index++) {
            int currIndex = index;
            // Off-loading to an Executor (presumable backed up by a thread pool)
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    boolean isOpenedSuccessfully = false;
                    if (!mIsPreloadingCancelled) {
                        isOpenedSuccessfully = openFileDescriptor(mItems.get(currIndex));
                    }

                    if (!isOpenedSuccessfully) {
                        unavailableMediaIndexes.add(currIndex);
                    } else {
                        mSuccessfullyPreloadedMediaIndexes.add(currIndex);
                    }

                    final int preloadedCount = mFinishedCount.incrementAndGet();
                    if (DEBUG) {
                        Log.d(TAG, "Preloaded " + preloadedCount + " (of " + mCount + ") items");
                    }

                    if (preloadedCount == mCount && !mIsPreloadingCancelled) {
                        // Don't need to "synchronize" here: mCount is our final value for
                        // preloadedCount, it won't be changing anymore.
                        if (unavailableMediaIndexes.size() == 0) {
                            mIsFinishedLiveData.postValue(true);
                        } else {
                            mUnavailableMediaIndexes.postValue(unavailableMediaIndexes);
                            mIsFinishedLiveData.postValue(false);
                        }
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
    private Boolean openFileDescriptor(@NonNull Uri uri) {
        AtomicReference<Boolean> isOpenedSuccessfully = new AtomicReference<>(true);
        long start = 0;
        if (DEBUG) {
            Log.d(TAG, "openFileDescriptor() START, " + Thread.currentThread() + ", " + uri);
            start = System.currentTimeMillis();
        }

        Trace.beginSection("Preloader.openFd");

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                mContentResolver.openAssetFileDescriptor(uri, "r").close();
            } catch (FileNotFoundException e) {
                isOpenedSuccessfully.set(false);
                Log.w(TAG, "Could not open FileDescriptor for " + uri, e);
            } catch (IOException e) {
                Log.w(TAG, "Failed to preload media file ", e);
            }
        });

        try {
            future.get(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return isOpenedSuccessfully.get();
        } catch (InterruptedException | ExecutionException e) {
            Log.w(TAG, "Could not preload the media item ", e);
        } finally {
            Trace.endSection();

            if (DEBUG) {
                final long elapsed = System.currentTimeMillis() - start;
                Log.d(TAG, "openFileDescriptor() DONE, took " + humanReadableTimeDuration(elapsed)
                        + ", " + uri);
            }
        }

        return isOpenedSuccessfully.get();
    }

    @NonNull
    private static AlertDialog createProgressDialog(
            @NonNull Activity activity, @NonNull List<Uri> selectedMedia, Context context) {
        ProgressDialog dialog = new ProgressDialog(activity,
                R.style.SelectedMediaPreloaderDialogTheme);
        dialog.setTitle(/* title */ context.getString(R.string.preloading_dialog_title));
        dialog.setMessage(/* message */ context.getString(
                R.string.preloading_progress_message, 0, selectedMedia.size()));
        dialog.setIndeterminate(/* indeterminate */ true);
        dialog.setCancelable(false);

        dialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                context.getString(R.string.preloading_cancel_button), (dialog1, which) -> {
                mIsPreloadingCancelledLiveData.setValue(true);
            });
        dialog.create();

        Button cancelButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (cancelButton != null) {
            cancelButton.setTextAppearance(R.style.ProgressDialogCancelButtonStyle);
            cancelButton.setAllCaps(false);
        }

        dialog.show();

        return dialog;
    }

    private static void ensureExecutor() {
        if (sExecutor == null) {
            synchronized (SelectedMediaPreloader.class) {
                if (sExecutor == null) {
                    sExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {

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
                    });
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
