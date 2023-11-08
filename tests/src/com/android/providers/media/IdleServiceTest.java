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

import static android.os.Environment.DIRECTORY_MOVIES;
import static android.os.Environment.DIRECTORY_PICTURES;
import static android.os.Environment.buildPath;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE;
import static android.provider.MediaStore.MediaColumns.DATE_EXPIRES;
import static android.provider.MediaStore.MediaColumns.DISPLAY_NAME;
import static android.provider.MediaStore.MediaColumns.GENERATION_MODIFIED;
import static android.provider.MediaStore.MediaColumns.RELATIVE_PATH;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.app.job.JobScheduler;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.NewUserRequest;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.library.RunOnlyOnPostsubmit;
import com.android.providers.media.scan.MediaScannerTest;
import com.android.providers.media.util.FileUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class IdleServiceTest {
    private static final String TAG = MediaProviderTest.TAG;
    private static final int IDLE_JOB_ID = -200;

    private File mDir;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.LOG_COMPAT_CHANGE,
                        android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        android.Manifest.permission.READ_DEVICE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        Manifest.permission.MANAGE_USERS,
                        Manifest.permission.WRITE_MEDIA_STORAGE,
                        android.Manifest.permission.DUMP);

        mDir = new File(context.getExternalMediaDirs()[0], "test_" + System.nanoTime());
        mDir.mkdirs();
        FileUtils.deleteContents(mDir);
    }

    @After
    public void tearDown() {
        FileUtils.deleteContents(mDir);
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testPruneThumbnails() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();
        // Previous tests (like DatabaseHelperTest) may have left stale
        // .database_uuid files, do an idle run first to clean them up.
        MediaStore.runIdleMaintenance(resolver);
        MediaStore.waitForIdle(resolver);

        final File dir = Environment.getExternalStorageDirectory();
        final File mediaDir = context.getExternalMediaDirs()[0];

        // Insert valid item into database
        final File file = MediaScannerTest.stage(R.raw.test_image,
                new File(mediaDir, System.nanoTime() + ".jpg"));
        final Uri uri = MediaStore.scanFile(resolver, file);
        final long id = ContentUris.parseId(uri);

        // Let things settle so our thumbnails don't get invalidated
        MediaStore.waitForIdle(resolver);

        // Touch some thumbnail files
        final File a = touch(buildPath(dir, DIRECTORY_PICTURES, ".thumbnails", "1234567.jpg"));
        final File b = touch(buildPath(dir, DIRECTORY_MOVIES, ".thumbnails", "7654321.jpg"));
        final File c = touch(buildPath(dir, DIRECTORY_PICTURES, ".thumbnails", id + ".jpg"));
        final File d = touch(buildPath(dir, DIRECTORY_PICTURES, ".thumbnails", "random.bin"));
        final File e = touch(buildPath(dir, DIRECTORY_PICTURES, ".thumbnails", ".nomedia"));

        try {
            // Idle maintenance pass should clean up unknown files
            MediaStore.runIdleMaintenance(resolver);
            assertFalse(exists(a));
            assertFalse(exists(b));
            assertTrue(exists(c));
            assertFalse(exists(d));
            assertTrue(exists(e));

            // And change the UUID, which emulates ejecting and mounting a different
            // storage device; all thumbnails should then be invalidated
            final File uuidFile = buildPath(dir, Environment.DIRECTORY_PICTURES,
                    ".thumbnails", ".database_uuid");
            delete(uuidFile);
            touch(uuidFile);

            // Idle maintenance pass should clean up all files except .nomedia file
            MediaStore.runIdleMaintenance(resolver);
            assertFalse("File a should have been deleted", exists(a));
            assertFalse("File b should have been deleted", exists(b));
            assertFalse("File c should have been deleted", exists(c));
            assertFalse("File d should have been deleted", exists(d));
            assertTrue("File e should have existed", exists(e));
            delete(uuidFile);
        } finally {
            a.delete();
            b.delete();
            c.delete();
            d.delete();
            e.delete();
        }
    }

    /**
     * b/199469244. If there are expired items with the same name in the same folder, we need to
     * extend the expiration time of them successfully.
     */
    @Test
    public void testExtendTrashedItemsHaveSameName() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();
        final String displayName = String.valueOf(System.nanoTime());
        final long dateExpires1 =
                (System.currentTimeMillis() - 10 * DateUtils.DAY_IN_MILLIS) / 1000;
        final Uri uri1 = createExpiredTrashedItem(resolver, dateExpires1, displayName);
        final long dateExpires2 =
                (System.currentTimeMillis() - 11 * DateUtils.DAY_IN_MILLIS) / 1000;
        final Uri uri2 = createExpiredTrashedItem(resolver, dateExpires2, displayName);
        final long dateExpires3 =
                (System.currentTimeMillis() - 12 * DateUtils.DAY_IN_MILLIS) / 1000;
        final Uri uri3 = createExpiredTrashedItem(resolver, dateExpires3, displayName);

        MediaStore.runIdleMaintenance(resolver);

        assertExpiredItemIsExtended(resolver, uri1, dateExpires1);
        assertExpiredItemIsExtended(resolver, uri2, dateExpires2);
        assertExpiredItemIsExtended(resolver, uri3, dateExpires3);
    }

    @Test
    public void testExtendTrashedItemExpiresOverOneWeek() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();
        final long dateExpires = (System.currentTimeMillis() - 10 * DateUtils.DAY_IN_MILLIS) / 1000;
        final Uri uri = createExpiredTrashedItem(resolver, dateExpires);

        MediaStore.runIdleMaintenance(resolver);

        assertExpiredItemIsExtended(resolver, uri, dateExpires);
    }

    @Test
    public void testDeleteExpiredTrashedItem() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final ContentResolver resolver = context.getContentResolver();
        // Create the expired item and scan the file to add it into database
        final long dateExpires = (System.currentTimeMillis() - 3 * DateUtils.DAY_IN_MILLIS) / 1000;
        final Uri uri = createExpiredTrashedItem(resolver, dateExpires);

        MediaStore.runIdleMaintenance(resolver);

        final String[] projection = new String[]{DATE_EXPIRES};
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = resolver.query(uri, projection, queryArgs,
                null /* cancellationSignal */)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testDetectSpecialFormat() throws Exception {
        // Require isolated resolver to query hidden column _special_format
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Create file such that it is not scanned
        final File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        final String fileName = TAG + System.nanoTime() + ".jpg";

        ContentValues values = new ContentValues();
        values.put(DISPLAY_NAME, fileName);
        values.put(RELATIVE_PATH, dir.getName());

        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        final Uri uri = resolver.insert(filesUri, values);
        assertThat(uri).isNotNull();

        final File file = new File(dir, fileName);
        file.createNewFile();

        try {
            final String[] projection = new String[]{_SPECIAL_FORMAT, GENERATION_MODIFIED};
            final int initialGenerationModified;
            try (Cursor cr = resolver.query(uri, projection, null, null, null)) {
                assertThat(cr.getCount()).isEqualTo(1);
                assertThat(cr.moveToFirst()).isNotNull();
                assertThat(cr.isNull(0)).isTrue();
                initialGenerationModified = cr.getInt(1);
            }

            // We are not calling runIdleMaintenance as it also scans volumes.
            try (ContentProviderClient cpc = resolver
                    .acquireContentProviderClient(MediaStore.AUTHORITY)) {
                ((MediaProvider) cpc.getLocalContentProvider())
                        .detectSpecialFormat(new CancellationSignal());
            }

            try (Cursor cr = resolver.query(uri, projection, null, null, null)) {
                assertThat(cr.getCount()).isEqualTo(1);
                assertThat(cr.moveToFirst()).isNotNull();
                assertThat(cr.getInt(0)).isEqualTo(_SPECIAL_FORMAT_NONE);
                // Make sure that updating special format column doesn't update GENERATION_MODIFIED
                assertThat(cr.getInt(1)).isEqualTo(initialGenerationModified);
            }
        } finally {
            file.delete();
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

            IdleService.scheduleIdlePass(context);
            assertNotNull(scheduler.getPendingJob(IDLE_JOB_ID));
        } finally {
            cancelJob();
        }
    }

    private void cancelJob() {
        final JobScheduler scheduler = InstrumentationRegistry.getTargetContext()
                .getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(IDLE_JOB_ID) != null) {
            scheduler.cancel(IDLE_JOB_ID);
        }
    }

    /**
     * Idle maintenance run on non-demo devices should not remove xattr data stored for different
     * users on /data/media/0. This is not done due to b/305658663.
     */
    @Test
    @RunOnlyOnPostsubmit
    @LargeTest
    @SdkSuppress(minSdkVersion = 33, codeName = "T")
    public void test_idle_maintenance_nonDemoDevice() throws IOException {
        assumeTrue(UserManager.supportsMultipleUsers());
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        Manifest.permission.CREATE_USERS,
                        Manifest.permission.MANAGE_USERS,
                        Manifest.permission.WRITE_MEDIA_STORAGE,
                        android.Manifest.permission.DUMP);
        SystemClock.sleep(3000);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getContext();
        UserManager userManager = context.getSystemService(UserManager.class);
        Integer secondaryUser = -1;
        boolean secondaryUserPresent = false;

        try {
            secondaryUser = createNewUser(userManager);
            secondaryUserPresent = true;
            startUser(secondaryUser);
            SystemClock.sleep(3000);

            // Verify presence of recovery data
            String[] recoveryData = MediaStore.getRecoveryData(context.getContentResolver());
            assertThat(getUserIdsForUsersFromRecoveryData(recoveryData)).containsAtLeastElementsIn(
                    Arrays.asList(UserHandle.SYSTEM.getIdentifier(), secondaryUser));

            executeShellCommand(
                    "content call --uri content://media/external/file --method "
                            + "run_idle_maintenance --user "
                            + UserHandle.SYSTEM.getIdentifier());
            executeShellCommand(
                    "content call --uri content://media/external/file --method "
                            + "run_idle_maintenance --user " + secondaryUser);

            // Verify presence of recovery data even after running idle maintenance
            recoveryData = MediaStore.getRecoveryData(context.getContentResolver());
            assertThat(getUserIdsForUsersFromRecoveryData(recoveryData)).containsAtLeastElementsIn(
                    Arrays.asList(UserHandle.SYSTEM.getIdentifier(), secondaryUser));

            // Remove secondary user
            removeUser(secondaryUser);
            secondaryUserPresent = false;
            SystemClock.sleep(3000);

            // Run idle maintenance for user 0
            executeShellCommand(
                    "content call --uri content://media/external/file --method "
                            + "run_idle_maintenance --user "
                            + UserHandle.SYSTEM.getIdentifier());

            // Verify presence of recovery data
            recoveryData = MediaStore.getRecoveryData(context.getContentResolver());
            assertThat(getUserIdsForUsersFromRecoveryData(recoveryData)).containsAtLeastElementsIn(
                    Arrays.asList(UserHandle.SYSTEM.getIdentifier(), secondaryUser));
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (secondaryUserPresent) {
                removeUser(secondaryUser);
            }
            MediaStore.removeRecoveryData(context.getContentResolver());
        }
    }

    private Set<Integer> getUserIdsForUsersFromRecoveryData(String[] recoveryData) {
        Set<Integer> userIdSet = new HashSet<>();
        for (String data : recoveryData) {
            if (data.startsWith("user.extdbnextrowid")) {
                userIdSet.add(Integer.valueOf(data.substring("user.extdbnextrowid".length())));
            } else if (data.startsWith("user.extdbsessionid")) {
                userIdSet.add(Integer.valueOf(data.substring("user.extdbsessionid".length())));
            }
        }

        return userIdSet;
    }


    private int createNewUser(UserManager userManager) {
        final NewUserRequest newUserRequest = new NewUserRequest.Builder().setName(
                "test_user" + System.currentTimeMillis()).setUserType(
                UserManager.USER_TYPE_FULL_SECONDARY).build();
        final UserHandle newUser = userManager.createUser(newUserRequest).getUser();
        if (newUser == null) {
            fail("Error while creating a new user");
        }
        return newUser.getIdentifier();
    }

    private void startUser(int userId) throws IOException {
        Log.i(TAG, "Starting user " + userId);
        String output = executeShellCommand("am start-user -w " + userId);
        if (output.startsWith("Error")) {
            fail(String.format("Failed to start user %d: %s", userId, output));
        }
    }

    private void removeUser(int userId) throws IOException {
        final String output = executeShellCommand("cmd package remove-user " + userId);
        if (output.startsWith("Error")) {
            fail("Error removing the user #" + userId + ": " + output);
        }
    }

    private void assertExpiredItemIsExtended(ContentResolver resolver, Uri uri,
            long lastExpiredDate) {
        final String[] projection = new String[]{DATE_EXPIRES};
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = resolver.query(uri, projection, queryArgs,
                null /* cancellationSignal */)) {
            assertThat(cursor.getCount()).isEqualTo(1);
            cursor.moveToFirst();
            final long dateExpiresAfter = cursor.getLong(0);
            assertThat(dateExpiresAfter).isGreaterThan(lastExpiredDate);
            assertTrue(timeDifferenceInSeconds(
                    (System.currentTimeMillis() + FileUtils.DEFAULT_DURATION_EXTENDED) / 1000,
                    dateExpiresAfter) <= 10);
        }
    }

    private long timeDifferenceInSeconds(long timeAfter, long timeBefore) {
        return timeAfter - timeBefore;
    }

    private Uri createExpiredTrashedItem(ContentResolver resolver, long dateExpires)
            throws Exception {
        return createExpiredTrashedItem(resolver, dateExpires,
                /* displayName */ String.valueOf(System.nanoTime()));
    }

    private Uri createExpiredTrashedItem(ContentResolver resolver, long dateExpires,
            String displayName) throws Exception {
        // Create the expired item and scan the file to add it into database
        final String expiredFileName = String.format(Locale.US, ".%s-%d-%s",
                FileUtils.PREFIX_TRASHED, dateExpires, displayName + ".jpg");
        final File file = MediaScannerTest.stage(R.raw.test_image, new File(mDir, expiredFileName));
        final Uri uri = MediaStore.scanFile(resolver, file);

        MediaStore.waitForIdle(resolver);

        final String[] projection = new String[]{DATE_EXPIRES};
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = resolver.query(uri, projection, queryArgs,
                null /* cancellationSignal */)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }
        return uri;
    }

    public static File delete(File file) throws IOException {
        executeShellCommand("rm -rf " + file.getAbsolutePath());
        assertFalse(exists(file));
        return file;
    }

    public static File touch(File file) throws IOException {
        executeShellCommand("mkdir -p " + file.getParentFile().getAbsolutePath());
        executeShellCommand("touch " + file.getAbsolutePath());
        assertTrue(exists(file));
        return file;
    }

    public static boolean exists(File file) throws IOException {
        final String path = file.getAbsolutePath();
        return executeShellCommand("ls -la " + path).contains(path);
    }

    private static String executeShellCommand(String command) throws IOException {
        Log.v(TAG, "$ " + command);
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        BufferedReader br = null;
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor());) {
            br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String str = null;
            StringBuilder out = new StringBuilder();
            while ((str = br.readLine()) != null) {
                Log.v(TAG, "> " + str);
                out.append(str);
            }
            return out.toString();
        } finally {
            if (br != null) {
                br.close();
            }
        }
    }
}
