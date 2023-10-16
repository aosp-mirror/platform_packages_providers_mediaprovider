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

package com.android.providers.media.stableuris.job;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.ConfigStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class StableUriIdleMaintenanceServiceTest {
    private static final String TAG = "StableUriIdleMaintenanceServiceTest";

    private static final String INTERNAL_BACKUP_NAME = "leveldb-internal";

    private boolean mInitialDeviceConfigValue = false;

    @Before
    public void setUp() throws IOException {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.LOG_COMPAT_CHANGE,
                        android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        android.Manifest.permission.READ_DEVICE_CONFIG,
                        android.Manifest.permission.WRITE_DEVICE_CONFIG,
                        Manifest.permission.WRITE_MEDIA_STORAGE);
        // Read existing value of the flag
        mInitialDeviceConfigValue = Boolean.parseBoolean(
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                        ConfigStore.ConfigStoreImpl.KEY_STABILISE_VOLUME_INTERNAL));
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                ConfigStore.ConfigStoreImpl.KEY_STABILISE_VOLUME_INTERNAL, Boolean.TRUE.toString(),
                false);
    }

    @After
    public void tearDown() throws IOException {
        // Restore previous value of the flag
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                ConfigStore.ConfigStoreImpl.KEY_STABILISE_VOLUME_INTERNAL,
                String.valueOf(mInitialDeviceConfigValue), false);
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testDataMigrationForInternalVolume() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();
        Set<String> internalFiles = new HashSet<>();
        MediaStore.waitForIdle(resolver);
        try (Cursor c = resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_INTERNAL),
                new String[]{MediaStore.Files.FileColumns.DATA}, null, null)) {
            assertNotNull(c);
            while (c.moveToNext()) {
                String path = c.getString(0);
                internalFiles.add(path);
            }
        }
        assertFalse(internalFiles.isEmpty());
        // Delete any existing backup to confirm that backup created is by idle maintenance job
        MediaStore.deleteBackedUpFilePaths(resolver, MediaStore.VOLUME_INTERNAL);

        MediaStore.waitForIdle(resolver);
        // Creates backup
        MediaStore.runIdleMaintenanceForStableUris(resolver);

        List<String> backedUpFiles = Arrays.asList(MediaStore.getBackupFiles(resolver));
        assertTrue(backedUpFiles.contains(INTERNAL_BACKUP_NAME));
        // Read all backed up paths
        List<String> backedUpPaths = Arrays.asList(
                MediaStore.readBackedUpFilePaths(resolver, MediaStore.VOLUME_INTERNAL));
        Log.i(TAG, "BackedUpPaths count:" + backedUpPaths.size());
        // Verify that all internal files are backed up
        for (String path : internalFiles) {
            assertTrue(backedUpPaths.contains(path));
        }
    }
}
