/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import android.support.test.uiautomator.UiDevice;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TestUtils {
    public static final String QUERY_TYPE = "com.android.providers.media.util.QUERY_TYPE";
    public static final String RUN_INFINITE_ACTIVITY =
            "com.android.providers.media.util.RUN_INFINITE_ACTIVITY";

    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20);
    private static final long POLLING_SLEEP_MILLIS = 100;

    public static void adoptShellPermission(@NonNull String... perms) {
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(perms);
    }

    public static void dropShellPermission() {
        getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    public static int getPid(String packageName)
            throws IOException, InterruptedException, TimeoutException {
        UiDevice uiDevice = UiDevice.getInstance(getInstrumentation());
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            String pid = uiDevice.executeShellCommand("pidof " + packageName).trim();
            if (pid.length() > 0) {
                return new Integer(pid);
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }

        throw new TimeoutException("Timed out waiting for pid");
    }
}
