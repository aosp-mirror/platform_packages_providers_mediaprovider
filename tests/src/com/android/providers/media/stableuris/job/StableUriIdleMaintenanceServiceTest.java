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

import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.createNewPublicVolume;
import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.deletePublicVolumes;
import static com.android.providers.media.util.FileUtils.getVolumePath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.job.JobScheduler;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.stableuris.dao.BackupIdRow;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 31, codeName = "S")
public class StableUriIdleMaintenanceServiceTest {
    private static final String TAG = "StableUriIdleMaintenanceServiceTest";

    private static final String INTERNAL_BACKUP_NAME = "leveldb-internal";

    private static final String EXTERNAL_BACKUP_NAME = "leveldb-external_primary";

    private static final String OWNERSHIP_BACKUP_NAME = "leveldb-ownership";

    private static final String PUBLIC_VOLUME_BACKUP_NAME = "leveldb-";

    private static boolean sInitialDeviceConfigValueForInternal = false;

    private static boolean sInitialDeviceConfigValueForExternal = false;

    private static boolean sInitialDeviceConfigValueForPublic = false;

    private static final int IDLE_JOB_ID = -500;

    @BeforeClass
    public static void setUpClass() throws Exception {
        adoptShellPermission();

        // Read existing value of the flag
        sInitialDeviceConfigValueForInternal = Boolean.parseBoolean(
                DeviceConfig.getProperty(ConfigStore.NAMESPACE_MEDIAPROVIDER,
                        ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_INTERNAL));
        DeviceConfig.setProperty(ConfigStore.NAMESPACE_MEDIAPROVIDER,
                ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_INTERNAL, Boolean.TRUE.toString(),
                false);
        sInitialDeviceConfigValueForExternal = Boolean.parseBoolean(
                DeviceConfig.getProperty(ConfigStore.NAMESPACE_MEDIAPROVIDER,
                        ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_EXTERNAL));
        DeviceConfig.setProperty(ConfigStore.NAMESPACE_MEDIAPROVIDER,
                ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_EXTERNAL, Boolean.TRUE.toString(),
                false);
        sInitialDeviceConfigValueForPublic = Boolean.parseBoolean(
                DeviceConfig.getProperty(ConfigStore.NAMESPACE_MEDIAPROVIDER,
                        ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_PUBLIC));
        DeviceConfig.setProperty(ConfigStore.NAMESPACE_MEDIAPROVIDER,
                ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_PUBLIC, Boolean.TRUE.toString(),
                false);
    }

    @AfterClass
    public static void tearDownClass() throws Exception {

        // Restore previous value of the flag
        DeviceConfig.setProperty(ConfigStore.NAMESPACE_MEDIAPROVIDER,
                ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_INTERNAL,
                String.valueOf(sInitialDeviceConfigValueForInternal), false);
        DeviceConfig.setProperty(ConfigStore.NAMESPACE_MEDIAPROVIDER,
                ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_EXTERNAL,
                String.valueOf(sInitialDeviceConfigValueForExternal), false);
        DeviceConfig.setProperty(ConfigStore.NAMESPACE_MEDIAPROVIDER,
                ConfigStore.ConfigStoreImpl.KEY_STABILIZE_VOLUME_PUBLIC,
                String.valueOf(sInitialDeviceConfigValueForPublic), false);
        SystemClock.sleep(3000);
        dropShellPermission();
    }

    @Test
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
    public void testDataMigrationForExternalVolume() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();
        Set<String> newFilePaths = new HashSet<String>();
        Map<String, Long> pathToIdMap = new HashMap<>();
        MediaStore.waitForIdle(resolver);

        try {
            for (int i = 0; i < 10; i++) {
                final File dir =
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS);
                final File file = new File(dir, System.nanoTime() + ".png");

                // Write 1 byte because 0 byte files are not valid in the db
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(1);
                }

                Uri uri = MediaStore.scanFile(resolver, file);
                long id = ContentUris.parseId(uri);
                newFilePaths.add(file.getAbsolutePath());
                pathToIdMap.put(file.getAbsolutePath(), id);
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
                Log.i(TAG, "BackupIdRow is " + backupIdRow);
                assertNotNull(backupIdRow);
                assertEquals(pathToIdMap.get(filePath).longValue(), backupIdRow.getId());
                assertEquals(UserHandle.myUserId(), backupIdRow.getUserId());
                assertEquals(context.getPackageName(),
                        MediaStore.getOwnerPackageName(resolver, backupIdRow.getOwnerPackageId()));
            }
        } finally {
            for (String path : newFilePaths) {
                new File(path).delete();
            }
        }
    }

    @Test
    @Ignore
    public void testDataMigrationForPublicVolume() throws Exception {
        createNewPublicVolume();
        try {
            final Context context = InstrumentationRegistry.getTargetContext();
            final ContentResolver resolver = context.getContentResolver();
            final Set<String> volNames = MediaStore.getExternalVolumeNames(context);

            for (String volName : volNames) {
                if (!MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(volName)
                        && !MediaStore.VOLUME_INTERNAL.equalsIgnoreCase(volName)) {
                    // public volume
                    Set<String> newFilePaths = new HashSet<String>();
                    Map<String, Long> pathToIdMap = new HashMap<>();
                    MediaStore.waitForIdle(resolver);

                    try {
                        for (int i = 0; i < 10; i++) {
                            File volPath = getVolumePath(context, volName);
                            final File dir = new File(volPath.getAbsoluteFile() + "/Download");
                            final File file = new File(dir, System.nanoTime() + ".png");

                            // Write 1 byte because 0 byte files are not valid in the db
                            try (FileOutputStream fos = new FileOutputStream(file)) {
                                fos.write(1);
                            }

                            Uri uri = MediaStore.scanFile(resolver, file);
                            long id = ContentUris.parseId(uri);
                            newFilePaths.add(file.getAbsolutePath());
                            pathToIdMap.put(file.getAbsolutePath(), id);
                        }

                        assertFalse(newFilePaths.isEmpty());
                        MediaStore.waitForIdle(resolver);
                        // Creates backup
                        MediaStore.runIdleMaintenanceForStableUris(resolver);

                        verifyLevelDbPresence(resolver, PUBLIC_VOLUME_BACKUP_NAME + volName);
                        verifyLevelDbPresence(resolver, OWNERSHIP_BACKUP_NAME);
                        // Verify that all internal files are backed up
                        for (String filePath : newFilePaths) {
                            BackupIdRow backupIdRow = BackupIdRow.deserialize(
                                    MediaStore.readBackup(resolver, volName, filePath));
                            assertNotNull(backupIdRow);
                            assertEquals(pathToIdMap.get(filePath).longValue(),
                                    backupIdRow.getId());
                            assertEquals(UserHandle.myUserId(), backupIdRow.getUserId());
                            assertEquals(context.getPackageName(),
                                    MediaStore.getOwnerPackageName(resolver,
                                            backupIdRow.getOwnerPackageId()));
                        }
                    } finally {
                        for (String path : newFilePaths) {
                            new File(path).delete();
                        }
                    }
                }
            }
        } finally {
            deletePublicVolumes();
        }
    }

    @Test
    public void testJobScheduling() {
        try {
            final Context context = InstrumentationRegistry.getTargetContext();
            final JobScheduler scheduler = InstrumentationRegistry.getTargetContext()
                    .getSystemService(JobScheduler.class);
            cancelJob();
            assertNull(scheduler.getPendingJob(IDLE_JOB_ID));

            StableUriIdleMaintenanceService.scheduleIdlePass(context);
            assertNotNull(scheduler.getPendingJob(IDLE_JOB_ID));
        } finally {
            cancelJob();
        }
    }

    private void verifyLevelDbPresence(ContentResolver resolver, String backupName) {
        List<String> backedUpFiles = Arrays.asList(MediaStore.getBackupFiles(resolver));
        assertTrue(backedUpFiles.contains(backupName));
    }

    private static void adoptShellPermission() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                        Manifest.permission.READ_DEVICE_CONFIG,
                        Manifest.permission.WRITE_DEVICE_CONFIG,
                        Manifest.permission.WRITE_MEDIA_STORAGE,
                        android.Manifest.permission.LOG_COMPAT_CHANGE,
                        android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        android.Manifest.permission.DUMP);
        SystemClock.sleep(3000);
    }

    private static void dropShellPermission() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    private void cancelJob() {
        final JobScheduler scheduler = InstrumentationRegistry.getTargetContext()
                .getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(IDLE_JOB_ID) != null) {
            scheduler.cancel(IDLE_JOB_ID);
        }
    }
}
