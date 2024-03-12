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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.R;

/**
 * Helper functions for Picker sync notifications.
 */
public class PickerSyncNotificationHelper {
    private static final String TAG = "SyncNotifHelper";
    @VisibleForTesting
    static final String NOTIFICATION_CHANNEL_ID = "PhotoPickerSyncChannel";
    @VisibleForTesting
    static final int NOTIFICATION_ID = 0;
    private static final int NOTIFICATION_TIMEOUT_MILLIS = 1000;


    /**
     * Created notification channel for Picker Sync notifications.
     * Recreating an existing notification channel with its original values performs no operation,
     * so it's safe to call this code when starting an app.
     */
    public static void createNotificationChannel(@NonNull Context context) {
        final String contentTitle = context.getResources()
                .getString(R.string.picker_sync_notification_channel);

        final NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, contentTitle, NotificationManager.IMPORTANCE_LOW);
        channel.enableLights(false);
        channel.enableVibration(false);

        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Return Foreground info. This object contains a Notification and notification id that should
     * be displayed in the context of a foreground service.
     * This method should not be invoked by WorkManager in Android S+ devices.
     */
    @NonNull
    public static ForegroundInfo getForegroundInfo(@NonNull Context context) {
        if (SdkLevel.isAtLeastS()) {
            Log.w(TAG, "Picker Sync notifications should not be displayed in S+ devices.");
        }
        return new ForegroundInfo(NOTIFICATION_ID, getNotification(context));
    }

    /**
     * Create a notification to display when Picker sync is happening.
     */
    private static Notification getNotification(@NonNull Context context) {
        final Resources resources = context.getResources();
        final String contentTitle = resources.getString(R.string.picker_sync_notification_title);
        final String contentText = resources.getString(R.string.picker_sync_notification_text);

        return new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.picker_app_icon)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setSilent(true)
                .setTimeoutAfter(NOTIFICATION_TIMEOUT_MILLIS)
                .build();
    }
}
