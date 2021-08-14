/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.MediaStore;

import java.util.concurrent.TimeUnit;

public class IdleService extends JobService {
    private static final int IDLE_JOB_ID = -200;

    private CancellationSignal mSignal;

    @Override
    public boolean onStartJob(JobParameters params) {
        mSignal = new CancellationSignal();
        new Thread(() -> {
            try (ContentProviderClient cpc = getContentResolver()
                    .acquireContentProviderClient(MediaStore.AUTHORITY)) {
                ((MediaProvider) cpc.getLocalContentProvider()).onIdleMaintenance(mSignal);
            } catch (OperationCanceledException ignored) {
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        mSignal.cancel();
        try (ContentProviderClient cpc = getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            ((MediaProvider) cpc.getLocalContentProvider()).onIdleMaintenanceStopped();
        } catch (OperationCanceledException ignored) {
        }
        return false;
    }

    public static void scheduleIdlePass(Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(IDLE_JOB_ID) == null) {
            final JobInfo job = new JobInfo.Builder(IDLE_JOB_ID,
                    new ComponentName(context, IdleService.class))
                            .setPeriodic(TimeUnit.HOURS.toMillis(24))
                            .setRequiresCharging(true)
                            .setRequiresDeviceIdle(true)
                            .build();
            scheduler.schedule(job);
        }
    }
}
