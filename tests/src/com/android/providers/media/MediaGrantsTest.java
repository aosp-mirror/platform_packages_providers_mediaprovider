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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public class MediaGrantsTest {
    static final String TAG = "MediaGrantsTest";

    private Context mIsolatedContext;
    private Context mContext;
    private ContentResolver mIsolatedResolver;
    private DatabaseHelper mExternalDatabase;
    private MediaGrants mGrants;

    private static final String TEST_OWNER_PACKAGE_NAME = "com.android.test.package";

    @BeforeClass
    public static void setUpClass() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                        Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.READ_DEVICE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        Manifest.permission.WRITE_MEDIA_STORAGE,
                        Manifest.permission.MANAGE_EXTERNAL_STORAGE);
    }

    @Before
    /** Clean up and old files / force a clean slate before each test case. */
    public void setUp() {
        if (mIsolatedResolver != null) {
            // This is necessary, we wait for all unfinished tasks to finish before we create a
            // new IsolatedContext.
            MediaStore.waitForIdle(mIsolatedResolver);
        }

        mContext = InstrumentationRegistry.getTargetContext();
        mIsolatedContext = new IsolatedContext(mContext, "modern", /*asFuseThread*/ false);
        mIsolatedResolver = mIsolatedContext.getContentResolver();
        mExternalDatabase = getExternalDatabase();
        mGrants = new MediaGrants(mExternalDatabase);
    }

    @Test
    public void testAddMediaGrants() throws Exception {

        Long fileId1 = insertFileInResolver("test_file1");
        Long fileId2 = insertFileInResolver("test_file2");
        List<Uri> uris = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));

        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris);

        assertGrantExistsForPackage(fileId1, TEST_OWNER_PACKAGE_NAME);
        assertGrantExistsForPackage(fileId2, TEST_OWNER_PACKAGE_NAME);
    }

    @Test
    public void testAddMediaGrantsRequiresPickerUri() throws Exception {

        Uri invalidUri =
                Uri.EMPTY
                        .buildUpon()
                        .scheme("content")
                        .encodedAuthority("some_authority")
                        .appendPath("path")
                        .appendPath("20180713")
                        .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, List.of(invalidUri));
                });
    }

    @Test
    public void removeAllMediaGrantsForPackage() throws Exception {

        Long fileId1 = insertFileInResolver("test_file1");
        Long fileId2 = insertFileInResolver("test_file2");
        List<Uri> uris = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris);

        assertGrantExistsForPackage(fileId1, TEST_OWNER_PACKAGE_NAME);
        assertGrantExistsForPackage(fileId2, TEST_OWNER_PACKAGE_NAME);

        int removed = mGrants.removeAllMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME);
        assertEquals(2, removed);

        try (Cursor c =
                mExternalDatabase.runWithTransaction(
                        (db) ->
                                db.query(
                                        MediaGrants.MEDIA_GRANTS_TABLE,
                                        new String[] {
                                            MediaGrants.FILE_ID_COLUMN,
                                            MediaGrants.OWNER_PACKAGE_NAME_COLUMN
                                        },
                                        String.format(
                                                "%s = '%s'",
                                                MediaGrants.OWNER_PACKAGE_NAME_COLUMN,
                                                TEST_OWNER_PACKAGE_NAME),
                                        null,
                                        null,
                                        null,
                                        null))) {
            assertEquals(0, c.getCount());
        }
    }

    @Test
    public void removeAllMediaGrantsForPackageRequiresNonEmpty() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    mGrants.removeAllMediaGrantsForPackage("");
                });
    }

    @Test
    public void addMediaGrantsIsPrivileged() throws Exception {
        assertThrows(
                SecurityException.class,
                () -> {
                    MediaStore.grantMediaReadForPackage(mContext, 1234, List.of());
                });
    }

    @Test
    public void mediaProviderUidCanAddMediaGrants() throws Exception {

        Long fileId1 = insertFileInResolver("test_file1");
        Long fileId2 = insertFileInResolver("test_file2");
        List<Uri> uris = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));
        // Use mIsolatedContext here to ensure we pass the security check.
        MediaStore.grantMediaReadForPackage(mIsolatedContext, Process.myUid(), uris);

        assertGrantExistsForPackage(fileId1, mContext.getPackageName());
        assertGrantExistsForPackage(fileId2, mContext.getPackageName());
    }

    /** Assert a media grant exists in the given database for the given file and package. */
    private void assertGrantExistsForPackage(Long fileId, String packageName) {

        try (Cursor c =
                mExternalDatabase.runWithTransaction(
                        (db) ->
                                db.query(
                                        MediaGrants.MEDIA_GRANTS_TABLE,
                                        new String[] {
                                            MediaGrants.FILE_ID_COLUMN,
                                            MediaGrants.OWNER_PACKAGE_NAME_COLUMN
                                        },
                                        String.format(
                                                "%s = '%s' AND %s = %s",
                                                MediaGrants.OWNER_PACKAGE_NAME_COLUMN,
                                                packageName,
                                                MediaGrants.FILE_ID_COLUMN,
                                                Long.toString(fileId)),
                                        null,
                                        null,
                                        null,
                                        null))) {
            assertEquals(1, c.getCount());
            Long fileIdValue;
            String ownerValue;

            assertTrue(c.moveToFirst());
            fileIdValue = c.getLong(c.getColumnIndex(MediaGrants.FILE_ID_COLUMN));
            ownerValue = c.getString(c.getColumnIndex(MediaGrants.OWNER_PACKAGE_NAME_COLUMN));
            assertEquals(fileIdValue, fileId);
            assertEquals(packageName, ownerValue);
        }
    }

    /**
     * Helper method to insert a test image/png into {@link mIsolatedResolver}
     *
     * @param name file name
     * @return {@link Long} the files table {@link MediaStore.MediaColumns.ID}
     */
    private Long insertFileInResolver(String name) throws IOException, FileNotFoundException {
        final File dir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        final File file = new File(dir, name + System.nanoTime() + ".png");

        // Write 1 byte because 0 byte files are not valid in the db
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(1);
        }

        Uri uri = MediaStore.scanFile(mIsolatedResolver, file);
        return ContentUris.parseId(uri);
    }

    /**
     * Assembles a valid picker content URI that resembels a content:// uri that would be returned
     * from photopicker.
     *
     * @param id The files table id
     * @return {@link Uri}
     */
    private Uri buildValidPickerUri(Long id) {

        return initializeUriBuilder(MediaStore.AUTHORITY)
                .appendPath("picker")
                .appendPath(Integer.toString(UserHandle.myUserId()))
                .appendPath(PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY)
                .appendPath(MediaStore.AUTHORITY)
                .appendPath(Long.toString(id))
                .build();
    }

    /**
     * @return {@link DatabaseHelper} The external database helper used by the test {@link
     *     IsolatedContext}
     */
    private DatabaseHelper getExternalDatabase() throws IllegalStateException {
        try (ContentProviderClient cpc =
                mIsolatedContext
                        .getContentResolver()
                        .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            MediaProvider mp = (MediaProvider) cpc.getLocalContentProvider();
            Optional<DatabaseHelper> helper =
                    mp.getDatabaseHelper(DatabaseHelper.EXTERNAL_DATABASE_NAME);
            if (helper.isPresent()) {
                return helper.get();
            } else {
                throw new IllegalStateException("Failed to acquire Database helper");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to acquire MediaProvider", e);
        }
    }

    /**
     * @param authority The authority to encode in the Uri builder.
     * @return {@link Uri.Builder} for a content:// uri for the passed authority.
     */
    private static Uri.Builder initializeUriBuilder(String authority) {
        final Uri.Builder builder = Uri.EMPTY.buildUpon();
        builder.scheme("content");
        builder.encodedAuthority(authority);

        return builder;
    }
}
