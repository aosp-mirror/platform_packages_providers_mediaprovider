/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.provider.MediaStore;

import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScanner;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MetricsTest {

    /**
     * The best we can do for coverage is make sure we don't explode?
     */
    @Test
    public void testSimple() throws Exception {
        final String volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY;
        final String packageName = "com.example";

        Metrics.logScan(volumeName, MediaScanner.REASON_UNKNOWN, 42, 42, 42, 42, 42);
        Metrics.logDeletion(volumeName, 42, packageName, 42);
        Metrics.logPermissionGranted(volumeName, 42, packageName, 42);
        Metrics.logPermissionDenied(volumeName, 42, packageName, 42);
        Metrics.logSchemaChange(volumeName, 42, 42, 42, 42);
        Metrics.logIdleMaintenance(volumeName, 42, 42, 42, 42);
    }
}
