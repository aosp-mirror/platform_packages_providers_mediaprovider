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

package com.android.providers.media.photopicker.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ListenableWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * A worker that just enqueues itself endlessly to prevent WorkManager's RescheduleReceiver from
 * becoming disabled. The work must continue!
 */
public class EndlessWorker extends Worker {

    private static final String TAG = "EndlessWorker";

    public EndlessWorker(@NonNull Context context, @NonNull WorkerParameters workerParameters) {
        super(context, workerParameters);
    }

    @Override
    public ListenableWorker.Result doWork() {

        // Immediately enqueue another worker to continue the endless loop.
        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(EndlessWorker.class)
                        .setInitialDelay(365, TimeUnit.DAYS)
                        .build();
        WorkManager.getInstance().enqueue(request);
        Log.i(TAG, "successfully enqueued the next worker.");

        return ListenableWorker.Result.success();
    }
}
