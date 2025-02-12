/* //device/content/providers/media/src/com/android/providers/media/MediaScannerReceiver.java
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.providers.media;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.stableuris.job.StableUriIdleMaintenanceService;

public class MediaReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            PickerSyncController.getInstanceOrThrow().onBootComplete();
            // Register our idle maintenance service
            IdleService.scheduleIdlePass(context);
            StableUriIdleMaintenanceService.scheduleIdlePass(context);
        } else {
            // All other operations are heavier-weight, so redirect them through
            // service to ensure they have breathing room to finish
            intent.setComponent(new ComponentName(context, MediaService.class));
            MediaService.enqueueWork(context, intent);
        }
    }
}
