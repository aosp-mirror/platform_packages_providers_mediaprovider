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

import static com.android.providers.media.scan.MediaScannerTest.stage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.R;
import com.android.providers.media.stableuris.dao.BackupIdRow;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class StableUriIdleMaintenanceServiceTest {
    private static final String TAG = "StableUriIdleMaintenanceServiceTest";

    private static final String INTERNAL_BACKUP_NAME = "leveldb-internal";

    private static final String EXTERNAL_BACKUP_NAME = "leveldb-external_primary";

    private static final String OWNERSHIP_BACKUP_NAME = "leveldb-ownership";

    private boolean mInitialDeviceConfigValueForInternal = false;

    private boolean mInitialDeviceConfigValueForExternal = false;

    @Before
    public void setUp() throws IOException {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.LOG_COMPAT_CHANGE,
                        android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        android.Manifest.permission.READ_DEVICE_CONFIG,
                        android.Manifest.permission.WRITE_DEVICE_CONFIG,
                        Manifest.permission.WRITE_MEDIA_STORAGE);
        // Read existing value of the flag
        mInitialDeviceConfigValueForInternal = Boolean.parseBoolean(
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                        ConfigStore.ConfigStoreImpl.KEY_STABILISE_VOLUME_INTERNAL));
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                ConfigStore.ConfigStoreImpl.KEY_STABILISE_VOLUME_INTERNAL, Boolean.TRUE.toString(),
                false);
        mInitialDeviceConfigValueForExternal = Boolean.parseBoolean(
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                        ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_EXTERNAL));
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_EXTERNAL, Boolean.TRUE.toString(),
                false);
    }

    @After
    public void tearDown() throws IOException {
        // Restore previous value of the flag
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                ConfigStore.ConfigStoreImpl.KEY_STABILISE_VOLUME_INTERNAL,
                String.valueOf(mInitialDeviceConfigValueForInternal), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_EXTERNAL,
                String.valueOf(mInitialDeviceConfigValueForExternal), false);
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testDataMigrationForInternalVolume() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();
        Set<String> internalFilePaths = new HashSet<>();
        Map<String, Long> pathToIdMap = new HashMap<>();
        MediaStore.waitForIdle(resolver);
        try (Cursor c = resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_INTERNAL),
                new String[]{MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns._ID},
                null, null)) {
            assertNotNull(c);
            while (c.moveToNext()) {
                String path = c.getString(0);
                internalFilePaths.add(path);
                pathToIdMap.put(path, c.getLong(1));
            }
        }
        assertFalse(internalFilePaths.isEmpty());

        MediaStore.waitForIdle(resolver);
        // Creates backup
        MediaStore.runIdleMaintenanceForStableUris(resolver);

        verifyLevelDbPresence(resolver, INTERNAL_BACKUP_NAME);
        // Verify that all internal files are backed up
        for (String path : internalFilePaths) {
            BackupIdRow backupIdRow = BackupIdRow.deserialize(MediaStore.readBackup(resolver,
                    MediaStore.VOLUME_EXTERNAL_PRIMARY, path));
            assertNotNull(backupIdRow);
            assertEquals(pathToIdMap.get(path).longValue(), backupIdRow.getId());
            assertEquals(UserHandle.myUserId(), backupIdRow.getUserId());
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31, codeName = "S")
    public void testDataMigrationForExternalVolume() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();
        Set<String> newFilePaths = new HashSet<String>();
        Map<String, Long> pathToIdMap = new HashMap<>();
        MediaStore.waitForIdle(resolver);

        try {
            for (int i = 0; i < 10; i++) {
                final File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                final String displayName = "test" + System.nanoTime() + ".jpg";
                final File image = new File(dir, displayName);
                stage(R.raw.test_image, image);
                newFilePaths.add(image.getAbsolutePath());
                Uri uri = MediaStore.scanFile(resolver, image);
                long id = ContentUris.parseId(uri);
                pathToIdMap.put(image.getAbsolutePath(), id);
            }

            assertFalse(newFilePaths.isEmpty());
            MediaStore.waitForIdle(resolver);
            // Creates backup
            MediaStore.runIdleMaintenanceForStableUris(resolver);

            verifyLevelDbPresence(resolver, EXTERNAL_BACKUP_NAME);
            verifyLevelDbPresence(resolver, OWNERSHIP_BACKUP_NAME);
            // Verify that all internal files are backed up
            for (String filePath : newFilePaths) {
                BackupIdRow backupIdRow = BackupIdRow.deserialize(
                        MediaStore.readBackup(resolver, MediaStore.VOLUME_EXTERNAL_PRIMARY,
                                filePath));
                assertNotNull(backupIdRow);
                assertEquals(pathToIdMap.get(filePath).longValue(), backupIdRow.getId());
                assertEquals(context.getPackageName(),
                        MediaStore.getOwnerPackageName(resolver, backupIdRow.getOwnerPackageId()));
                assertEquals(UserHandle.myUserId(), backupIdRow.getUserId());
            }
        } finally {
            for (String path : newFilePaths) {
                new File(path).delete();
            }
        }
    }

    private void verifyLevelDbPresence(ContentResolver resolver, String backupName) {
        List<String> backedUpFiles = Arrays.asList(MediaStore.getBackupFiles(resolver));
        assertTrue(backedUpFiles.contains(backupName));
    }
}
