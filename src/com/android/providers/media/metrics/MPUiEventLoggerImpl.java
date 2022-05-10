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

package com.android.providers.media.metrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEventLogger;
import com.android.providers.media.MediaProviderStatsLog;

public class MPUiEventLoggerImpl implements UiEventLogger {

    @Override
    public void log(@NonNull UiEventEnum event) {
        log(event, 0, null);
    }

    @Override
    public void log(@NonNull UiEventEnum event, @Nullable InstanceId instance) {
        logWithInstanceId(event, 0, null, instance);
    }

    @Override
    public void log(@NonNull UiEventEnum event, int uid, @Nullable String packageName) {
        final int eventID = event.getId();
        if (eventID > 0) {
            MediaProviderStatsLog.write(MediaProviderStatsLog.UI_EVENT_REPORTED,
                    /* event_id = 1 */ eventID,
                    /* uid = 2 */ uid,
                    /* package_name = 3 */ packageName,
                    /* instance_id = 4 */ 0);
        }
    }

    @Override
    public void logWithInstanceId(@NonNull UiEventEnum event, int uid, @Nullable String packageName,
            @Nullable InstanceId instance) {
        final int eventID = event.getId();
        if ((eventID > 0)  && (instance != null)) {
            MediaProviderStatsLog.write(MediaProviderStatsLog.UI_EVENT_REPORTED,
                    /* event_id = 1 */ eventID,
                    /* uid = 2 */ uid,
                    /* package_name = 3 */ packageName,
                    /* instance_id = 4 */ instance.getId());
        } else {
            log(event, uid, packageName);
        }
    }

    @Override
    public void logWithPosition(@NonNull UiEventEnum event, int uid, @Nullable String packageName,
            int position) {
        final int eventID = event.getId();
        if (eventID > 0) {
            MediaProviderStatsLog.write(MediaProviderStatsLog.RANKING_SELECTED,
                    /* event_id = 1 */ eventID,
                    /* package_name = 2 */ packageName,
                    /* instance_id = 3 */ 0,
                    /* position_picked = 4 */ position,
                    /* is_pinned = 5 */ false);
        }
    }

    @Override
    public void logWithInstanceIdAndPosition(@NonNull UiEventEnum event, int uid,
            @Nullable String packageName, @Nullable InstanceId instance, int position) {
        final int eventID = event.getId();
        if ((eventID > 0)  && (instance != null)) {
            MediaProviderStatsLog.write(MediaProviderStatsLog.RANKING_SELECTED,
                    /* event_id = 1 */ eventID,
                    /* package_name = 2 */ packageName,
                    /* instance_id = 3 */ instance.getId(),
                    /* position_picked = 4 */ position,
                    /* is_pinned = 5 */ false);
        } else {
            logWithPosition(event, uid, packageName, position);
        }
    }
}
