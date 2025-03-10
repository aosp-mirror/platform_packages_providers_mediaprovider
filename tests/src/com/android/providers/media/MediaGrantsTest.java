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

import static com.android.providers.media.MediaGrants.FILE_ID_COLUMN;
import static com.android.providers.media.MediaGrants.PACKAGE_USER_ID_COLUMN;
import static com.android.providers.media.MediaGrants.PER_PACKAGE_GRANTS_LIMIT_CONST;
import static com.android.providers.media.photopicker.data.ItemsProvider.getItemsUri;
import static com.android.providers.media.util.FileCreationUtils.buildValidPickerUri;
import static com.android.providers.media.util.FileCreationUtils.insertFileInResolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.model.UserId;

import junit.framework.AssertionFailedError;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
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

    private static final String PNG_MIME_TYPE = "image/png";

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
    public void testAddMediaGrantsCountExceedingLimit() throws Exception {
        mGrants.setGrantsLimit(5);
        String fileIdPlaceHolder = "test_file";
        int numberOfInputFiles = 5;
        List<Long> ids = new ArrayList<>();
        List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < numberOfInputFiles; i++) {
            Long file_id = insertFileInResolver(mIsolatedResolver, fileIdPlaceHolder + i);
            ids.add(file_id);
            uris.add(buildValidPickerUri(file_id));
        }

        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris, TEST_USER_ID);
        // 5 items were added assert that all of them are present as grants.
        for (int i = 0; i < ids.size(); i++) {
            assertGrantExistsForPackage(ids.get(i), TEST_OWNER_PACKAGE_NAME,
                    TEST_USER_ID);
        }

        // now add one more item and verify that the first item that was added is no longer in the
        // database.
        Long file_id = insertFileInResolver(mIsolatedResolver, fileIdPlaceHolder + 6);
        ids.add(file_id);
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, List.of(
                buildValidPickerUri(file_id)), TEST_USER_ID);

        // new item was added, assert that the first item is not in the list anymore.
        try {
            assertGrantExistsForPackage(ids.get(0), TEST_OWNER_PACKAGE_NAME,
                    TEST_USER_ID);
            throw new AssertionFailedError("The assertion should have failed");
        } catch (AssertionError ignored) {
            // ignore this is the expected result.
        }

        // assert grant should exist for file id 1 and above.
        for (int i = 1; i < ids.size(); i++) {
            assertGrantExistsForPackage(ids.get(i), TEST_OWNER_PACKAGE_NAME,
                    TEST_USER_ID);
        }
        mGrants.setGrantsLimit(PER_PACKAGE_GRANTS_LIMIT_CONST);
    }

    @Test
    public void testAddMediaGrantsCountExceedingLimitForDifferentPackages() throws Exception {
        mGrants.setGrantsLimit(5);
        String fileIdPlaceHolder = "test_file";
        int numberOfInputFiles = 6;
        List<Long> ids = new ArrayList<>();
        List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < numberOfInputFiles; i++) {
            Long file_id = insertFileInResolver(mIsolatedResolver, fileIdPlaceHolder + i);
            ids.add(file_id);
            uris.add(buildValidPickerUri(file_id));
        }

        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris, TEST_USER_ID);

        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME2, uris, TEST_USER_ID);

        // verify different grants are present for different packages.

        // 5 items were added assert that all of them are present as grants.
        for (int i = 1; i < ids.size(); i++) {
            assertGrantExistsForPackage(ids.get(i), TEST_OWNER_PACKAGE_NAME,
                    TEST_USER_ID);
        }

        // 5 items were added assert that all of them are present as grants.
        for (int i = 1; i < ids.size(); i++) {
            assertGrantExistsForPackage(ids.get(i), TEST_OWNER_PACKAGE_NAME2,
                    TEST_USER_ID);
        }
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

        String[] mimeTypes = {PNG_MIME_TYPE};
        String[] volumes = {MediaStore.VOLUME_EXTERNAL_PRIMARY};

        List<Uri> fileUris = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID,  mimeTypes, volumes));

        List<Long> expectedFileIdsList = List.of(fileId1, fileId2);

        assertEquals(fileUris.size(), expectedFileIdsList.size());
        for (Uri uri : fileUris) {
            assertTrue(expectedFileIdsList.contains(Long.valueOf(ContentUris.parseId(uri))));
        }

        List<Uri> fileUrisForTestPackage2 = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME2}, TEST_USER_ID,  mimeTypes, volumes));

        List<Long> expectedFileIdsList2 = List.of(fileId3);

        assertEquals(fileUrisForTestPackage2.size(), expectedFileIdsList2.size());
        for (Uri uri : fileUrisForTestPackage2) {
            assertTrue(expectedFileIdsList2.contains(Long.valueOf(ContentUris.parseId(uri))));
        }

        List<Uri> fileUrisForTestPackage3 = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{"non.existent.package"}, TEST_USER_ID,  mimeTypes, volumes));

        // assert no items are returned for an invalid package.
        assertEquals(/* expected= */fileUrisForTestPackage3.size(), /* actual= */0);
    }

    @Test
    public void test_GetMediaGrantsForPackages_excludesIsTrashed() throws Exception {
        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        List<Uri> uris1 = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));

        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris1, TEST_USER_ID);

        String[] mimeTypes = {PNG_MIME_TYPE};
        String[] volumes = {MediaStore.VOLUME_EXTERNAL_PRIMARY};
        // Mark one of the files as trashed.
        updateFileValues(fileId1, MediaStore.Files.FileColumns.IS_TRASHED, "1");

        List<Uri> fileUris = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID,  mimeTypes, volumes));

        // Now the 1st file with fileId1 should not be part of the returned grants.
        List<Long> expectedFileIdsList = List.of(fileId2);

        assertEquals(fileUris.size(), expectedFileIdsList.size());
        for (Uri uri : fileUris) {
            assertTrue(expectedFileIdsList.contains(Long.valueOf(ContentUris.parseId(uri))));
        }
    }

    @Test
    public void test_GetMediaGrantsForPackages_excludesIsPending() throws Exception {
        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        List<Uri> uris1 = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));

        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris1, TEST_USER_ID);

        String[] mimeTypes = {PNG_MIME_TYPE};
        String[] volumes = {MediaStore.VOLUME_EXTERNAL_PRIMARY};
        // Mark one of the files as pending.
        updateFileValues(fileId1, MediaStore.Files.FileColumns.IS_PENDING, "1");

        List<Uri> fileUris = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID,  mimeTypes, volumes));

        // Now the 1st file with fileId1 should not be part of the returned grants.
        List<Long> expectedFileIdsList = List.of(fileId2);

        assertEquals(fileUris.size(), expectedFileIdsList.size());
        for (Uri uri : fileUris) {
            assertTrue(expectedFileIdsList.contains(Long.valueOf(ContentUris.parseId(uri))));
        }
    }

    @Test
    public void test_GetMediaGrantsForPackages_testMimeTypeFilter() throws Exception {
        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        List<Uri> uris1 = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));

        Long fileId3 = insertFileInResolver(mIsolatedResolver, "test_file3", "mp4");
        List<Uri> uris2 = List.of(buildValidPickerUri(fileId3));

        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris1, TEST_USER_ID);
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris2, TEST_USER_ID);

        String[] volumes = {MediaStore.VOLUME_EXTERNAL_PRIMARY};

        // Test image only, should return 2 items.
        String[] mimeTypes = {PNG_MIME_TYPE};

        List<Uri> fileUris = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID, mimeTypes, volumes));

        List<Long> expectedFileIdsList = List.of(fileId1, fileId2);
        assertEquals(fileUris.size(), expectedFileIdsList.size());
        for (Uri uri : fileUris) {
            assertTrue(expectedFileIdsList.contains(Long.valueOf(ContentUris.parseId(uri))));
        }

        // Test video only, should return 1 item.
        String[] mimeTypes2 = {"video/mp4"};

        List<Uri> fileUris2 = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID, mimeTypes2, volumes));
        List<Long> expectedFileIdsList2 = List.of(fileId3);
        assertEquals(fileUris2.size(), expectedFileIdsList2.size());
        for (Uri uri : fileUris2) {
            assertTrue(expectedFileIdsList2.contains(Long.valueOf(ContentUris.parseId(uri))));
        }


        // Test jpeg mimeType, since no items with this mimeType is granted, empty list should be
        // returned.
        String[] mimeTypes3 = {"image/jpeg"};
        List<Uri> fileUris3 = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID, mimeTypes3, volumes));
        assertTrue(fileUris3.isEmpty());
    }

    @Test
    public void test_GetMediaGrantsForPackages_volume() throws Exception {
        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        List<Uri> uris1 = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));

        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, uris1, TEST_USER_ID);

        String[] volumes = {"test_volume"};
        String[] mimeTypes = {PNG_MIME_TYPE};

        List<Uri> fileUris = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID,  mimeTypes, volumes));

        assertTrue(fileUris.isEmpty());
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

        String[] mimeTypes = {PNG_MIME_TYPE};
        String[] volumes = {MediaStore.VOLUME_EXTERNAL_PRIMARY};

        // Verify the grants for the first package were inserted.
        List<Uri> fileUris = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID,
                mimeTypes, volumes));
        List<Long> expectedFileIdsList = List.of(fileId1, fileId2);
        assertEquals(fileUris.size(), expectedFileIdsList.size());
        for (Uri uri : fileUris) {
            assertTrue(expectedFileIdsList.contains(Long.valueOf(ContentUris.parseId(uri))));
        }

        // Remove one of the 2 grants for TEST_OWNER_PACKAGE_NAME and verify the other grants is
        // still present.
        mGrants.removeMediaGrantsForPackage(new String[]{TEST_OWNER_PACKAGE_NAME},
                List.of(buildValidPickerUri(fileId1)), TEST_USER_ID);
        List<Uri> fileUris3 = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID,  mimeTypes, volumes));
        assertEquals(1, fileUris3.size());
        assertEquals(fileId2, Long.valueOf(ContentUris.parseId(fileUris3.get(0))));


        // Verify grants of other packages are unaffected.
        List<Uri> fileUrisForTestPackage2 = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME2}, TEST_USER_ID,  mimeTypes, volumes));
        List<Long> expectedFileIdsList2 = List.of(fileId3);
        assertEquals(fileUrisForTestPackage2.size(), expectedFileIdsList2.size());
        for (Uri uri : fileUrisForTestPackage2) {
            assertTrue(expectedFileIdsList2.contains(Long.valueOf(ContentUris.parseId(uri))));
        }
    }

    @Test
    public void testRemoveMediaGrantsForPackagesLargerDataSet() throws Exception {
        List<Uri> inputFiles = new ArrayList<>();
        for (int itr = 1; itr < 110; itr++) {
            inputFiles.add(buildValidPickerUri(
                    insertFileInResolver(mIsolatedResolver, "test_file" + itr)));
        }
        mGrants.addMediaGrantsForPackage(TEST_OWNER_PACKAGE_NAME, inputFiles, TEST_USER_ID);

        String[] mimeTypes = {PNG_MIME_TYPE};
        String[] volumes = {MediaStore.VOLUME_EXTERNAL_PRIMARY};

        // The query used inside remove grants is batched by 50 ids, hence having a test like this
        // would help ensure the batching worked perfectly.
        mGrants.removeMediaGrantsForPackage(new String[]{TEST_OWNER_PACKAGE_NAME},
                inputFiles.subList(0, 101), TEST_USER_ID);
        List<Uri> fileUris3 = convertToListOfUri(mGrants.getMediaGrantsForPackages(
                new String[]{TEST_OWNER_PACKAGE_NAME}, TEST_USER_ID, mimeTypes, volumes));
        assertEquals(8, fileUris3.size());
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

    @Test
    public void test_generationGrantedExistsAndIsIncreasing_success() throws Exception {
        Long fileId1 = insertFileInResolver(mIsolatedResolver, "test_file1");
        Long fileId2 = insertFileInResolver(mIsolatedResolver, "test_file2");
        Long fileId3 = insertFileInResolver(mIsolatedResolver, "test_file3");

        List<Uri> uris = List.of(buildValidPickerUri(fileId1), buildValidPickerUri(fileId2));
        MediaStore.grantMediaReadForPackage(mIsolatedContext, Process.myUid(), uris);
        // adding grants separately for fileId3 so that it has a different generation from fileId1
        // and fileId2.
        List<Uri> uris2 = List.of(buildValidPickerUri(fileId3));
        MediaStore.grantMediaReadForPackage(mIsolatedContext, Process.myUid(), uris2);

        assertGrantExistsForPackage(
                fileId1,
                mContext.getPackageName(),
                TEST_USER_ID);
        assertGrantExistsForPackage(
                fileId2,
                mContext.getPackageName(),
                TEST_USER_ID);
        assertGrantExistsForPackage(
                fileId3,
                mContext.getPackageName(),
                TEST_USER_ID);

        long gen1 = getGenerationForMediaGrant(fileId1,
                mContext.getPackageName(),
                TEST_USER_ID);
        long gen2 = getGenerationForMediaGrant(fileId2,
                mContext.getPackageName(),
                TEST_USER_ID);
        long gen3 = getGenerationForMediaGrant(fileId3,
                mContext.getPackageName(),
                TEST_USER_ID);
        // verify generation for items granted in the same session are equal.
        assertEquals(gen2, gen1);
        // verify generation are increasing.
        assertTrue(gen1 < gen3);
    }

    /**
     * Assert a media grant exists in the given database.
     *
     * @param fileId        the corresponding files._id column value.
     * @param packageName   i.e. com.android.test.package
     * @param userId        the user id of the package.
     */
    private void assertGrantExistsForPackage(Long fileId, String packageName,
            int userId) {
        try (Cursor c = getMediaGrantRow(fileId, packageName, userId)) {
            assertNotNull(c);
            assertEquals(1, c.getCount());
            Long fileIdValue;
            String ownerValue;
            assertTrue(c.moveToFirst());
            fileIdValue = c.getLong(c.getColumnIndex(MediaGrants.FILE_ID_COLUMN));
            ownerValue = c.getString(c.getColumnIndex(MediaGrants.OWNER_PACKAGE_NAME_COLUMN));
            long generationGranted = c.getLong(
                    c.getColumnIndex(MediaGrants.GENERATION_GRANTED));
            assertEquals(fileIdValue, fileId);
            assertEquals(packageName, ownerValue);
            assertTrue(generationGranted > 0);
        }
    }

    private long getGenerationForMediaGrant(Long fileId, String packageName,
            int userId) {

        long generationGranted = -1;
        try (Cursor c = getMediaGrantRow(fileId, packageName, userId)) {
            assertNotNull(c);
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            generationGranted = c.getLong(
                    c.getColumnIndex(MediaGrants.GENERATION_GRANTED));
            assertTrue(generationGranted >= 0);

        }
        return generationGranted;
    }

    private Cursor getMediaGrantRow(Long fileId, String packageName,
            int userId) {
        return mExternalDatabase.runWithTransaction(
                (db) ->
                        db.query(
                                MediaGrants.MEDIA_GRANTS_TABLE,
                                new String[]{
                                        MediaGrants.FILE_ID_COLUMN,
                                        MediaGrants.OWNER_PACKAGE_NAME_COLUMN,
                                        MediaGrants.PACKAGE_USER_ID_COLUMN,
                                        MediaGrants.GENERATION_GRANTED
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
                                null));
    }

    private List<Uri> convertToListOfUri(Cursor c) {
        List<Uri> filesUriList = new ArrayList<>(0);
        while (c.moveToNext()) {
            final Integer file_id = c.getInt(c.getColumnIndexOrThrow(FILE_ID_COLUMN));
            final Integer userId = c.getInt(
                    c.getColumnIndexOrThrow(PACKAGE_USER_ID_COLUMN));
            // transforming ids to Item uris to use as a key in selection based features.
            filesUriList.add(getItemsUri(String.valueOf(file_id),
                    PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY,
                    UserId.of(UserHandle.of(userId))));
        }
        return filesUriList;
    }

    /**
     * Modify column value for the fileId passed in the parameters with the modifiedValue.
     */
    private void updateFileValues(Long fileId, String columnToBeModified, String modifiedValue) {
        int numberOfUpdatedRows = mExternalDatabase.runWithTransaction(
                (db) -> {
                    ContentValues updatedRowValue = new ContentValues();
                    updatedRowValue.put(columnToBeModified, modifiedValue);
                    return db.update(MediaStore.Files.TABLE,
                            updatedRowValue,
                            String.format(
                                    "%s = '%s'",
                                    MediaStore.Files.FileColumns._ID,
                                    Long.toString(fileId)),
                            null);
                });
        assertEquals(/* expected */ 1, numberOfUpdatedRows);
    }
}
