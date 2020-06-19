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

package com.android.providers.media.client;

import static com.android.providers.media.client.LegacyProviderMigrationTest.executeShellCommand;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Verify media ownership delegation behaviors for an app holding
 * {@code UPDATE_DEVICE_STATS} permission.
 */
@RunWith(AndroidJUnit4.class)
public class DelegatorTest {
    private static final String TAG = "DelegatorTest";

    /**
     * To confirm behaviors, we need to pick an app installed on all devices
     * which has no permissions, and the best candidate is the "Easter Egg" app.
     */
    private static final String PERMISSIONLESS_APP = "com.android.egg";

    private ContentResolver mResolver;

    private Uri mExternalAudio = MediaStore.Audio.Media
            .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

    @Before
    public void setUp() throws Exception {
        mResolver = InstrumentationRegistry.getTargetContext().getContentResolver();
    }

    /**
     * Delegation allows us to push ownership of items we own to someone else.
     */
    @Test
    public void testPushAllowed() throws Exception {
        final Uri uri = createAudio();

        assertEquals(InstrumentationRegistry.getTargetContext().getPackageName(), getOwner(uri));

        final ContentValues values = new ContentValues();
        values.put(MediaColumns.OWNER_PACKAGE_NAME, PERMISSIONLESS_APP);
        mResolver.update(uri, values, null);

        assertEquals(PERMISSIONLESS_APP, getOwner(uri));
    }

    /**
     * Delegation allows us to push orphaned items to someone else.
     */
    @Test
    public void testOrphanedAllowed() throws Exception {
        final Uri uri = createAudio();
        clearOwner(uri);

        assertEquals(null, getOwner(uri));

        final ContentValues values = new ContentValues();
        values.put(MediaColumns.OWNER_PACKAGE_NAME, PERMISSIONLESS_APP);
        mResolver.update(uri, values, null);

        assertEquals(PERMISSIONLESS_APP, getOwner(uri));
    }

    /**
     * However, attempting to steal items belonging to someone else is blocked.
     */
    @Test
    public void testPullBlocked() throws Exception {
        final Uri uri = createAudio();
        setOwner(uri, PERMISSIONLESS_APP);

        assertEquals(PERMISSIONLESS_APP, getOwner(uri));

        final ContentValues values = new ContentValues();
        values.put(MediaColumns.OWNER_PACKAGE_NAME,
                InstrumentationRegistry.getTargetContext().getPackageName());
        mResolver.update(uri, values, null);

        assertEquals(PERMISSIONLESS_APP, getOwner(uri));
    }

    private Uri createAudio() throws IOException {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "Song " + System.nanoTime());
        values.put(MediaColumns.MIME_TYPE, "audio/mpeg");

        final Uri uri = mResolver.insert(mExternalAudio, values);
        try (OutputStream out = mResolver.openOutputStream(uri)) {
        }
        return uri;
    }

    private String getOwner(Uri uri) throws Exception {
        try (Cursor cursor = mResolver.query(uri,
                new String[] { MediaColumns.OWNER_PACKAGE_NAME }, null, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        }
    }

    public static void setOwner(Uri uri, String packageName) throws Exception {
        executeShellCommand("content update"
                + " --user " + InstrumentationRegistry.getTargetContext().getUserId()
                + " --uri " + uri
                + " --bind owner_package_name:s:" + packageName,
                InstrumentationRegistry.getInstrumentation().getUiAutomation());
    }

    public static void clearOwner(Uri uri) throws Exception {
        executeShellCommand("content update"
                + " --user " + InstrumentationRegistry.getTargetContext().getUserId()
                + " --uri " + uri
                + " --bind owner_package_name:n:",
                InstrumentationRegistry.getInstrumentation().getUiAutomation());
    }
}
