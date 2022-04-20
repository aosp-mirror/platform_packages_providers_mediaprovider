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

package com.android.providers.media.util;

import static com.android.providers.media.util.Logging.TAG;

import android.os.Binder;
import android.provider.DeviceConfig;
import android.util.Log;
import com.android.modules.utils.build.SdkLevel;

public class DeviceConfigUtils {

    public static String getStringDeviceConfig(String key, String defaultValue) {
        if (!canReadDeviceConfig(key, defaultValue)) {
            return defaultValue;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getString(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT, key,
                    defaultValue);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static <T> boolean canReadDeviceConfig(String key, T defaultValue) {
        if (SdkLevel.isAtLeastS()) {
            return true;
        }

        Log.w(TAG, "Cannot read device config before Android S. Returning defaultValue: "
                + defaultValue + " for key: " + key);
        return false;
    }
}
