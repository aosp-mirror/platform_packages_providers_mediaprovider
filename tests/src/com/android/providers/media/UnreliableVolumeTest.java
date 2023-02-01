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

package com.android.providers.media;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import androidx.test.InstrumentationRegistry;

import com.android.providers.media.photopicker.data.UnreliableVolumeDatabaseHelper;
import com.android.providers.media.scan.MediaScannerTest;
import com.android.providers.media.util.UserCache;
import com.google.common.io.ByteStreams;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class UnreliableVolumeTest {

    private static String sVolumePath;
    private static String sVolumeName;
    private static VolumeCache sVolumeCache;
    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(2);
    private static final long POLLING_SLEEP_MILLIS = 100;
    private static final String TAG = "UnreliableVolumeTest";

    static final String UNRELIABLE_VOLUME_TABLE = "media";

    private static final long SIZE_BYTES = 7000;
    private static final String DISPLAY_NAME = "first day of school";
    private static final long DATE_MODIFIED = 1623852851911L;
    private static final String MIME_TYPE = "image/jpg";
    private static String _DATA = "/foo/bar/internship.jpeg";

    private static Context sIsolatedContext;

    @BeforeClass
    public static void setUp() throws Exception {
        createRemovableVolume();
        final Context context = getContext();
        UserCache mUserCache = new UserCache(context);
        sIsolatedContext = new MediaScannerTest.IsolatedContext(context, TAG,
                /*asFuseThread*/ false);
        sVolumeCache = new VolumeCache(context, mUserCache);
        sVolumeCache.update();
        sVolumeName = getCurrentPublicVolumeString();
        sVolumePath = "/mnt/media_rw/" + sVolumeName;
    }

    @AfterClass
    public static void tearDown() throws Exception {
        executeShellCommand("sm set-virtual-disk false");
        pollForCondition(() -> !isPublicVolumeMounted(), "Timed out while waiting for"
                + " the public volume to disappear");
    }

    @Test
    @Ignore("Enable after b/197816987 is fixed")
    public void testUnreliableVolumeSimple() throws Exception {
        assertEquals(sVolumeName, sVolumeCache.getUnreliableVolumePath().get(0).getName());
        assertEquals(sVolumePath, sVolumeCache.getUnreliableVolumePath().get(0).getPath());
    }

    @Test
    @Ignore("Enable after b/197816987 is fixed")
    public void testDBisCreated() throws Exception {
        String[] projection = new String[] {
                UnreliableVolumeDatabaseHelper.MediaColumns.SIZE_BYTES,
                UnreliableVolumeDatabaseHelper.MediaColumns.DISPLAY_NAME,
                UnreliableVolumeDatabaseHelper.MediaColumns.DATE_MODIFIED,
                UnreliableVolumeDatabaseHelper.MediaColumns.MIME_TYPE,
                UnreliableVolumeDatabaseHelper.MediaColumns._DATA
        };

        try (UnreliableVolumeDatabaseHelper helper =
                     new UnreliableVolumeDatabaseHelper(sIsolatedContext)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues values = generateAndGetContentValues();
            assertThat(db.insert(UNRELIABLE_VOLUME_TABLE, null, values)).isNotEqualTo(-1);

            try (Cursor cr = db.query(UNRELIABLE_VOLUME_TABLE, projection, null, null, null,
                    null, null)) {
                assertThat(cr.getCount()).isEqualTo(1);
                while (cr.moveToNext()) {
                    assertThat(cr.getLong(0)).isEqualTo(SIZE_BYTES);
                    assertThat(cr.getString(1)).isEqualTo(DISPLAY_NAME);
                    assertThat(cr.getLong(2)).isEqualTo(DATE_MODIFIED);
                    assertThat(cr.getString(3)).isEqualTo(MIME_TYPE);
                    assertThat(cr.getString(4)).isEqualTo(_DATA);
                }
            }
        }
    }

    private static ContentValues generateAndGetContentValues() {
        ContentValues values = new ContentValues();
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns.SIZE_BYTES, SIZE_BYTES);
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns.DISPLAY_NAME, DISPLAY_NAME);
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns.DATE_MODIFIED, DATE_MODIFIED);
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns.MIME_TYPE, MIME_TYPE);
        values.put(UnreliableVolumeDatabaseHelper.MediaColumns._DATA, _DATA);
        return values;
    }

    /**
     * Executes a shell command.
     */
    public static String executeShellCommand(String pattern, Object...args) throws IOException {
        String command = String.format(pattern, args);
        int attempt = 0;
        while (attempt++ < 5) {
            try {
                return executeShellCommandInternal(command);
            } catch (InterruptedIOException e) {
                Log.v(TAG, "Trouble executing " + command + "; trying again", e);
            }
        }
        throw new IOException("Failed to execute " + command);
    }

    private static String executeShellCommandInternal(String cmd) throws IOException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (FileInputStream output = new FileInputStream(
                uiAutomation.executeShellCommand(cmd).getFileDescriptor())) {
            return new String(ByteStreams.toByteArray(output));
        }
    }

    private static void pollForCondition(Supplier<Boolean> condition, String errorMessage)
            throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        throw new TimeoutException(errorMessage);
    }

    private static boolean isPublicVolumeMounted() {
        try {
            final String publicVolume = executeShellCommand("sm list-volumes public").trim();
            return publicVolume != null && publicVolume.contains("mounted");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean partitionDisk() {
        try {
            final String listDisks = executeShellCommand("sm list-disks").trim();
            if (listDisks.length() > 0) {
                executeShellCommand("sm partition " + listDisks + " public");
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getCurrentPublicVolumeString() {
        final String[] allPublicVolumeDetails;
        try {
            allPublicVolumeDetails = executeShellCommand("sm list-volumes public")
                    .trim().split("\n");
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute shell command", e);
            return null;
        }
        for (String volDetails : allPublicVolumeDetails) {
            if (volDetails.startsWith("public")) {
                final String[] publicVolumeDetails = volDetails.trim().split(" ");
                String res = publicVolumeDetails[publicVolumeDetails.length - 1];
                if ("null".equals(res)) {
                    continue;
                }
                return res;
            }
        }
        return null;
    }

    private static boolean isExternalStorageStateMounted() {
        final File target = Environment.getExternalStorageDirectory();
        try {
            return (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(target))
                    && Os.statvfs(target.getAbsolutePath()).f_blocks > 0);
        } catch (ErrnoException ignored) {
        }
        return false;
    }

    /**
     * Creates a new OTG-USB volume
     */
    public static void createRemovableVolume() throws Exception {
        executeShellCommand("sm set-force-adoptable off");
        executeShellCommand("sm set-virtual-disk true");
        pollForCondition(() -> partitionDisk(), "Timed out while waiting for"
                + " disk partitioning");
        // Poll twice to avoid using previous mount status
        pollForCondition(() -> isPublicVolumeMounted(), "Timed out while waiting for"
                + " the public volume to mount");
        pollForCondition(() -> isExternalStorageStateMounted(), "Timed out while"
                + " waiting for ExternalStorageState to be MEDIA_MOUNTED");
    }
}