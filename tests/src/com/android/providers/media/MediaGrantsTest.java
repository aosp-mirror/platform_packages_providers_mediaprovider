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

import static com.android.providers.media.util.FileCreationUtils.buildValidPickerUri;
import static com.android.providers.media.util.FileCreationUtils.insertFileInResolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class MediaGrantsTest {
    private Context mIsolatedContext;
    private Context mContext;
    private ContentResolver mIsolatedResolver;
    private DatabaseHelper mExternalDatabase;
    private MediaGrants mGrants;

    private static final String TEST_OWNER_PACKAGE_NAME = "com.android.test.package";
    private static final String TEST_OWNER_PACKAGE_NAME2 = "com.android.test.package2";
    private static final int TEST_USER_ID = UserHandle.myUserId();

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
        mExternalDatabase = ((IsolatedContext) mIsolatedContext).getExternalDatabase();
        mGrants = new MediaGrants(mExternalDatabase);
    }

    @Test
    public void testAddMediaGrants() throws Exception {

        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        List<Uri> uris = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));

        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris, TEST_USER_ID);

        assertGrantExistsForPackage(fileId1, TEST_OWNER_PACKAGE_NAME, TEST_USER_ID);
        assertGrantExistsForPackage(fileId2, TEST_OWNER_PACKAGE_NAME, TEST_USER_ID);
    }

    @Test
    public void testGetMediaGrantsForPackages() throws Exception {
        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        Long fileId3 = insertFileInResolver(mIsolatedResolver, "test_file3");
        List<Uri> uris1 = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));
        List<Uri> uris2 = List.of(buildValidPickerUri(fileId3));

        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris1, TEST_USER_ID);
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME2, uris2, TEST_USER_ID);


        List<Uri> fileUris = mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID);

        List<Long> expectedFileIdsList = List.of(fileId1, fileId2);

        assertEquals(fileUris.size(), expectedFileIdsList.size());
        for (Uri uri : fileUris) {
            assertTrue(expectedFileIdsList.contains(Long.valueOf(ContentUris.parseId(uri))));
        }

        List<Uri> fileUrisForTestPackage2 = mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME2}, TEST_USER_ID);

        List<Long> expectedFileIdsList2 = List.of(fileId3);

        assertEquals(fileUrisForTestPackage2.size(), expectedFileIdsList2.size());
        for (Uri uri : fileUrisForTestPackage2) {
            assertTrue(expectedFileIdsList2.contains(Long.valueOf(ContentUris.parseId(uri))));
        }

        List<Uri> fileUrisForTestPackage3 = mGrants.getMediaGrantsForPackages(
                new String[]{"non.existent.package"}, TEST_USER_ID);

        // assert no items are returned for an invalid package.
        assertEquals(/* expected= */fileUrisForTestPackage3.size(), /* actual= */0);
    }


    @Test
    public void testRemoveMediaGrantsForPackages() throws Exception {
        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        Long fileId3 = insertFileInResolver(mIsolatedResolver, "test_file3");
        List<Uri> uris1 = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));
        List<Uri> uris2 = List.of(buildValidPickerUri(fileId3));

        // Add grants for 2 different packages.
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris1, TEST_USER_ID);
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME2, uris2, TEST_USER_ID);


        // Verify the grants for the first package were inserted.
        List<Uri> fileUris = mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID);
        List<Long> expectedFileIdsList = List.of(fileId1, fileId2);
        assertEquals(fileUris.size(), expectedFileIdsList.size());
        for (Uri uri : fileUris) {
            assertTrue(expectedFileIdsList.contains(Long.valueOf(ContentUris.parseId(uri))));
        }

        // Remove one of the 2 grants for TEST_OWNER_PACKAGE_NAME and verify the other grants is
        // still present.
        mGrants.removeMediaGrantsForPackage(new String[]{TEST_OWNER_PACKAGE_NAME},
                List.of(buildValidPickerUri(fileId1)), TEST_USER_ID);
        List<Uri> fileUris3 = mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID);
        assertEquals(1, fileUris3.size());
        assertEquals(fileId2, Long.valueOf(ContentUris.parseId(fileUris3.get(0))));


        // Verify grants of other packages are unaffected.
        List<Uri> fileUrisForTestPackage2 = mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME2}, TEST_USER_ID);
        List<Long> expectedFileIdsList2 = List.of(fileId3);
        assertEquals(fileUrisForTestPackage2.size(), expectedFileIdsList2.size());
        for (Uri uri : fileUrisForTestPackage2) {
            assertTrue(expectedFileIdsList2.contains(Long.valueOf(ContentUris.parseId(uri))));
        }
    }
    @Test
    public void testAddDuplicateMediaGrants() throws Exception {

        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        List<Uri> uris = List.of(buildValidPickerUri(fileId1));
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris, TEST_USER_ID);
        assertGrantExistsForPackage(fileId1, TEST_OWNER_PACKAGE_NAME, TEST_USER_ID);

        // Add the same grant again to ensure no database insert failure.
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris, TEST_USER_ID);
        assertGrantExistsForPackage(fileId1, TEST_OWNER_PACKAGE_NAME, TEST_USER_ID);
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
                    mGrants.addMediaGrantsForPackage(
                            TEST_OWNER_PACKAGE_NAME, List.of(invalidUri), TEST_USER_ID);
                });
    }

    @Test
    public void removeAllMediaGrantsForPackage() throws Exception {

        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        List<Uri> uris = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris, TEST_USER_ID);

        assertGrantExistsForPackage(fileId1, TEST_OWNER_PACKAGE_NAME, TEST_USER_ID);
        assertGrantExistsForPackage(fileId2, TEST_OWNER_PACKAGE_NAME, TEST_USER_ID);

        int removed =
                mGrants.removeAllMediaGrantsForPackages(
                        new String[] {TEST_OWNER_PACKAGE_NAME}, "test", TEST_USER_ID);
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
    public void removeAllMediaGrantsForMultiplePackages() throws Exception {

        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        List<Uri> uris = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris, TEST_USER_ID);
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME2, uris, TEST_USER_ID);

        assertGrantExistsForPackage(fileId1, TEST_OWNER_PACKAGE_NAME, TEST_USER_ID);
        assertGrantExistsForPackage(fileId2, TEST_OWNER_PACKAGE_NAME, TEST_USER_ID);
        assertGrantExistsForPackage(fileId1, TEST_OWNER_PACKAGE_NAME2, TEST_USER_ID);
        assertGrantExistsForPackage(fileId2, TEST_OWNER_PACKAGE_NAME2, TEST_USER_ID);

        int removed =
                mGrants.removeAllMediaGrantsForPackages(
                        new String[] {TEST_OWNER_PACKAGE_NAME, TEST_OWNER_PACKAGE_NAME2},
                        "test",
                        TEST_USER_ID);
        assertEquals(4, removed);

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
                                                TEST_OWNER_PACKAGE_NAME2),
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
                    mGrants.removeAllMediaGrantsForPackages(new String[]{}, "test", TEST_USER_ID);
                });
    }

    @Test
    public void removeAllMediaGrants() throws Exception {

        final String secondPackageName = "com.android.test.another.package";
        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        List<Uri> uris = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris, TEST_USER_ID);
        mGrants.addMediaGrantsForPackage(secondPackageName, uris, TEST_USER_ID);

        assertGrantExistsForPackage(fileId1, TEST_OWNER_PACKAGE_NAME, TEST_USER_ID);
        assertGrantExistsForPackage(fileId2, TEST_OWNER_PACKAGE_NAME, TEST_USER_ID);
        assertGrantExistsForPackage(fileId1, secondPackageName, TEST_USER_ID);
        assertGrantExistsForPackage(fileId2, secondPackageName, TEST_USER_ID);

        int removed = mGrants.removeAllMediaGrants();
        assertEquals(4, removed);

        try (Cursor c =
                mExternalDatabase.runWithTransaction(
                        (db) ->
                                db.query(
                                        MediaGrants.MEDIA_GRANTS_TABLE,
                                        new String[] {
                                            MediaGrants.FILE_ID_COLUMN,
                                            MediaGrants.OWNER_PACKAGE_NAME_COLUMN
                                        },
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))) {
            assertEquals(0, c.getCount());
        }
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

        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        List<Uri> uris = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));
        // Use mIsolatedContext here to ensure we pass the security check.
        MediaStore.grantMediaReadForPackage(mIsolatedContext, Process.myUid(), uris);

        assertGrantExistsForPackage(fileId1, mContext.getPackageName(), TEST_USER_ID);
        assertGrantExistsForPackage(fileId2, mContext.getPackageName(), TEST_USER_ID);
    }

    /**
     * Assert a media grant exists in the given database.
     *
     * @param fileId        the corresponding files._id column value.
     * @param packageName   i.e. com.android.test.package
     * @param userId        the user id of the package.
     */
    private void assertGrantExistsForPackage(Long fileId, String packageName, int userId) {

        try (Cursor c =
                mExternalDatabase.runWithTransaction(
                        (db) ->
                                db.query(
                                        MediaGrants.MEDIA_GRANTS_TABLE,
                                        new String[] {
                                            MediaGrants.FILE_ID_COLUMN,
                                            MediaGrants.OWNER_PACKAGE_NAME_COLUMN,
                                            MediaGrants.PACKAGE_USER_ID_COLUMN
                                        },
                                        String.format(
                                                "%s = '%s' AND %s = %s AND %s = %s",
                                                MediaGrants.OWNER_PACKAGE_NAME_COLUMN,
                                                packageName,
                                                MediaGrants.FILE_ID_COLUMN,
                                                Long.toString(fileId),
                                                MediaGrants.PACKAGE_USER_ID_COLUMN,
                                                Integer.toString(userId)),
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
}
