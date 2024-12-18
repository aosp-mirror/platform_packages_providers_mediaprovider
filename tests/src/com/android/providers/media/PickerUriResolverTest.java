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

import static android.content.Intent.ACTION_GET_CONTENT;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.provider.CloudMediaProviderContract.MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION;
import static android.provider.MediaStore.ACTION_PICK_IMAGES;
import static android.provider.MediaStore.EXTRA_CALLING_PACKAGE_UID;

import static androidx.test.InstrumentationRegistry.getContext;
import static androidx.test.InstrumentationRegistry.getTargetContext;

import static com.android.providers.media.photopicker.PickerDataLayer.QUERY_SHOULD_SCREEN_SELECTION_URIS;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.provider.CloudMediaProviderContract;
import android.provider.Column;
import android.provider.ExportedSince;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.photopicker.PhotoPickerProvider;
import com.android.providers.media.photopicker.PickerDataLayer;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PickerUriResolverTest {
    private static final String TAG = PickerUriResolverTest.class.getSimpleName();
    private static final File TEST_FILE = new File(Environment.getExternalStorageDirectory(),
            Environment.DIRECTORY_DOWNLOADS + "/" + TAG + System.currentTimeMillis() + ".jpeg");
    // UserId for which context and content resolver are set up such that TEST_FILE
    // file exists in this user's content resolver.
    private static final int TEST_USER = 20;

    private static Context sCurrentContext;
    private static IsolatedContext sOtherUserContext;
    private static TestPickerUriResolver sTestPickerUriResolver;
    private static Uri sTestPickerUri;
    private static String TEST_ID;

    private static Uri sMediaStoreUriInOtherContext;

    private static class TestPickerUriResolver extends PickerUriResolver {
        TestPickerUriResolver(Context context) {
            super(context, new PickerDbFacade(getTargetContext(), new PickerSyncLockManager()),
                    new ProjectionHelper(Column.class, ExportedSince.class),
                    new LocalUriMatcher(MediaStore.AUTHORITY));
        }

        @Override
        Cursor queryPickerUri(Uri uri, String[] projection) {
            if (!uri.getLastPathSegment().equals(TEST_ID)) {
                return super.queryPickerUri(uri, projection);
            }

            final String[] p = new String[] {
                CloudMediaProviderContract.MediaColumns.ID,
                CloudMediaProviderContract.MediaColumns.MIME_TYPE
            };

            final MatrixCursor c = new MatrixCursor(p);
            c.addRow(new String[] { TEST_ID, "image/jpeg"});
            return c;
        }

        @Override
        File getPickerFileFromUri(Uri uri) {
            if (!uri.getLastPathSegment().equals(TEST_ID)) {
                return super.getPickerFileFromUri(uri);
            }

            return TEST_FILE;
        }
    }

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        // this test uses isolated context which requires these permissions to be granted
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.LOG_COMPAT_CHANGE,
                        android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS);
        sCurrentContext = mock(Context.class);
        when(sCurrentContext.getUser()).thenReturn(UserHandle.of(UserHandle.myUserId()));
        PackageManager packageManager = mock(PackageManager.class);
        when(sCurrentContext.getPackageManager()).thenReturn(packageManager);
        when(packageManager.getPackagesForUid(anyInt())).thenReturn(
                new String[]{getContext().getPackageName()});

        sOtherUserContext = createOtherUserContext(TEST_USER);
        sTestPickerUriResolver = new TestPickerUriResolver(sCurrentContext);

        sMediaStoreUriInOtherContext = createTestFileInContext(sOtherUserContext);
        TEST_ID = sMediaStoreUriInOtherContext.getLastPathSegment();
    }

    @Before
    public void setUp() {
        when(sCurrentContext
                .checkPermission(eq(MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION), anyInt(), anyInt()))
                .thenReturn(PERMISSION_DENIED);
    }


    @AfterClass
    public static void tearDown() {
        TEST_FILE.delete();
    }

    @Test
    public void wrapProviderUriValid() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        final String providerSuffix = "authority/media/media_id";

        final Uri providerUriUserImplicit = Uri.parse("content://" + providerSuffix);

        final Uri providerUriUser0 = Uri.parse("content://0@" + providerSuffix);
        final Uri mediaUriUser0 = Uri.parse("content://media/picker/0/" + providerSuffix);
        final Uri mediaUriUser0PickerGetContent = Uri.parse(
                "content://media/picker_get_content/0/" + providerSuffix);

        final Uri providerUriUser10 = Uri.parse("content://10@" + providerSuffix);
        final Uri mediaUriUser10 = Uri.parse("content://media/picker/10/" + providerSuffix);

        assertThat(PickerUriResolver.wrapProviderUri(providerUriUserImplicit,
                ACTION_PICK_IMAGES, 0))
                .isEqualTo(mediaUriUser0);
        assertThat(PickerUriResolver.wrapProviderUri(providerUriUserImplicit,
                ACTION_GET_CONTENT, 0))
                .isEqualTo(mediaUriUser0PickerGetContent);
        assertThat(
                PickerUriResolver.wrapProviderUri(providerUriUser0, ACTION_PICK_IMAGES,
                        0)).isEqualTo(mediaUriUser0);
        assertThat(PickerUriResolver.unwrapProviderUri(mediaUriUser0)).isEqualTo(providerUriUser0);

        assertThat(PickerUriResolver.wrapProviderUri(providerUriUserImplicit,
                ACTION_PICK_IMAGES, 10))
                .isEqualTo(mediaUriUser10);
        assertThat(
                PickerUriResolver.wrapProviderUri(providerUriUser10, ACTION_PICK_IMAGES,
                        10))
                .isEqualTo(mediaUriUser10);
        assertThat(PickerUriResolver.unwrapProviderUri(mediaUriUser10))
                .isEqualTo(providerUriUser10);
    }

    @Test
    public void wrapProviderUriInvalid() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        final String providerSuffixLong = "authority/media/media_id/another_media_id";
        final String providerSuffixShort = "authority/media";

        final Uri providerUriUserLong = Uri.parse("content://0@" + providerSuffixLong);
        final Uri mediaUriUserLong = Uri.parse("content://media/picker/0/" + providerSuffixLong);

        final Uri providerUriUserShort = Uri.parse("content://0@" + providerSuffixShort);
        final Uri mediaUriUserShort = Uri.parse("content://media/picker/0/" + providerSuffixShort);

        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.wrapProviderUri(providerUriUserLong, ACTION_PICK_IMAGES,
                        0));
        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.unwrapProviderUri(mediaUriUserLong));

        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.unwrapProviderUri(mediaUriUserShort));
        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.wrapProviderUri(providerUriUserShort, ACTION_PICK_IMAGES,
                        0));
    }

    @Test
    public void testGetAlbumUri() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        final String authority = "foo";
        final Uri uri = Uri.parse("content://foo/album");
        assertThat(PickerUriResolver.getAlbumUri(authority)).isEqualTo(uri);
    }

    @Test
    public void testGetMediaUri() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        final String authority = "foo";
        final Uri uri = Uri.parse("content://foo/media");
        assertThat(PickerUriResolver.getMediaUri(authority)).isEqualTo(uri);
    }

    @Test
    public void testGetDeletedMediaUri() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        final String authority = "foo";
        final Uri uri = Uri.parse("content://foo/deleted_media");
        assertThat(PickerUriResolver.getDeletedMediaUri(authority)).isEqualTo(uri);
    }

    @Test
    public void testCreateSurfaceControllerUri() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        final String authority = "foo";
        final Uri uri = Uri.parse("content://foo/surface_controller");
        assertThat(PickerUriResolver.createSurfaceControllerUri(authority)).isEqualTo(uri);
    }

    @Test
    public void testOpenFile_mode_w() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        updateReadUriPermission(sTestPickerUri, /* grant */ true);
        try {
            sTestPickerUriResolver.openFile(sTestPickerUri, "w", /* signal */ null,
                    LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                            0));
            fail("Write is not supported for Picker Uris. uri: " + sTestPickerUri);
        } catch (SecurityException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("PhotoPicker Uris can only be accessed to"
                    + " read. Uri: " + sTestPickerUri);
        }
    }

    @Test
    public void testProcessUrisForSelection_withoutPermissionOrAuthorityChecks() {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        Bundle queryArgs = new Bundle();
        ArrayList<String> inputUris = new ArrayList<>();
        inputUris.add(sTestPickerUri.toString());
        queryArgs.putStringArrayList(PickerDataLayer.QUERY_ID_SELECTION, inputUris);

        sTestPickerUriResolver.processUrisForSelection(queryArgs,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY,  /* cloudProvider */
                null, /* isLocalOnly */true);

        List<String> filteredList = queryArgs.getStringArrayList(
                PickerDataLayer.QUERY_LOCAL_ID_SELECTION);
        assertThat(filteredList).isNotNull();
        assertThat(filteredList.size()).isEqualTo(1);
        assertThat(filteredList.get(0)).isEqualTo(sTestPickerUri.getLastPathSegment());

        // clear data for local ids and cloud ids from queryArgs
        queryArgs.remove(PickerDataLayer.QUERY_LOCAL_ID_SELECTION);
        queryArgs.remove(PickerDataLayer.QUERY_CLOUD_ID_SELECTION);

        // no items should be added to result if isLocalOnly is set to false.
        // This is the default case.
        sTestPickerUriResolver.processUrisForSelection(queryArgs,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY,  /* cloudProvider */
                null, /* isLocalOnly */false);

        List<String> filteredList2 = queryArgs.getStringArrayList(
                PickerDataLayer.QUERY_LOCAL_ID_SELECTION);
        assertThat(filteredList2).isNull();
    }

    @Test
    public void testProcessUrisForSelection_permissionChecks() {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);

        // the test uid can be any id but not the current id.
        int testUid = Process.myUid() + 1;

        // creating query args to mimic the case of preselection in photo picker
        Bundle queryArgs = new Bundle();
        ArrayList<String> inputUris = new ArrayList<>();
        inputUris.add(sTestPickerUri.toString());
        queryArgs.putStringArrayList(PickerDataLayer.QUERY_ID_SELECTION, inputUris);
        queryArgs.putBoolean(QUERY_SHOULD_SCREEN_SELECTION_URIS, true);
        queryArgs.putInt(EXTRA_CALLING_PACKAGE_UID, testUid);

        // Case 1: should filter out uris if the incoming uid does not have the permission.

        // ensure the permission check fails
        updateReadUriPermissionForUid(sTestPickerUri, /* grant */ false, Process.myUid() + 1);

        // Process the uris
        sTestPickerUriResolver.processUrisForSelection(queryArgs,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY,  /* cloudProvider */
                null, /* isLocalOnly */ true);

        // verify that the local ids did not get populated
        List<String> filteredList = queryArgs.getStringArrayList(
                PickerDataLayer.QUERY_LOCAL_ID_SELECTION);
        assertThat(filteredList).isNull();

        // Case 2: check local ids are populated when permission is present

        // grant read permission
        updateReadUriPermissionForUid(sTestPickerUri, /* grant */ true, Process.myUid() + 1);

        sTestPickerUriResolver.processUrisForSelection(queryArgs,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY, /* cloudProvider */
                null, /* isLocalOnly */true);

        // verify that the local ids is populated with the input uri
        List<String> filteredList2 = queryArgs.getStringArrayList(
                PickerDataLayer.QUERY_LOCAL_ID_SELECTION);
        assertThat(filteredList2).isNotNull();
        assertThat(filteredList2.size()).isEqualTo(1);
        assertThat(filteredList2.get(0)).isEqualTo(sTestPickerUri.getLastPathSegment());
    }

    @Test
    public void testProcessUrisForSelection_cloudAuthorityChecks() {
        long testCloudId = 1234567890;
        String testCloudAuthority = "com.test.cloud.authority";
        Uri testCloudUri = getPickerUriForIdWithCustomAuthority(testCloudId,
                TEST_USER, ACTION_PICK_IMAGES, testCloudAuthority);

        // the test uid can be any id but not the current id.
        int testUid = Process.myUid() + 1;

        // creating query args to mimic the case of preselection in photo picker
        Bundle queryArgs = new Bundle();
        ArrayList<String> inputUris = new ArrayList<>();
        inputUris.add(testCloudUri.toString());
        queryArgs.putStringArrayList(PickerDataLayer.QUERY_ID_SELECTION, inputUris);
        queryArgs.putBoolean(QUERY_SHOULD_SCREEN_SELECTION_URIS, true);
        queryArgs.putInt(EXTRA_CALLING_PACKAGE_UID, testUid);

        // grant read permission
        updateReadUriPermissionForUid(testCloudUri, /* grant */ true, testUid);

        // verify no items are added to result when null cloud authority is passed.
        sTestPickerUriResolver.processUrisForSelection(queryArgs,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY, /* cloudProvider */
                null, /* isLocalOnly */ false);

        // verify that the cloud ids did not get populated
        List<String> filteredList = queryArgs.getStringArrayList(
                PickerDataLayer.QUERY_CLOUD_ID_SELECTION);
        assertThat(filteredList).isNull();

        // verify no items are added to result when incorrect cloud authority is passed.
        String inCorrectAuthority = "com.incorrect.authority";
        sTestPickerUriResolver.processUrisForSelection(queryArgs,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY, /* isLocalOnly */
                inCorrectAuthority, /* isLocalOnly */ false);

        // verify that the local ids did not get populated
        List<String> filteredList2 = queryArgs.getStringArrayList(
                PickerDataLayer.QUERY_CLOUD_ID_SELECTION);
        assertThat(filteredList2).isNull();


        // verify correct items are set when correct authority is passed
        sTestPickerUriResolver.processUrisForSelection(queryArgs,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY, /* cloudProvider */
                testCloudAuthority, /* isLocalOnly */ false);

        // verify that the cloud ids is populated with the input uri
        List<String> filteredList3 = queryArgs.getStringArrayList(
                PickerDataLayer.QUERY_CLOUD_ID_SELECTION);
        assertThat(filteredList3).isNotNull();
        assertThat(filteredList3.size()).isEqualTo(1);
        assertThat(filteredList3.get(0)).isEqualTo(testCloudUri.getLastPathSegment());
    }

    @Test
    public void testOpenFile_mode_rw() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        updateReadUriPermission(sTestPickerUri, /* grant */ true);
        try {
            sTestPickerUriResolver.openFile(sTestPickerUri, "rw", /* signal */ null,
                    LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                            0));
            fail("Read-Write is not supported for Picker Uris. uri: " + sTestPickerUri);
        } catch (SecurityException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("PhotoPicker Uris can only be accessed to"
                    + " read. Uri: " + sTestPickerUri);
        }
    }

    @Test
    public void testOpenFile_mode_invalid() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        updateReadUriPermission(sTestPickerUri, /* grant */ true);
        try {
            sTestPickerUriResolver.openFile(sTestPickerUri, "foo", /* signal */ null,
                    LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                            0));
            fail("Invalid mode should not be supported for openFile. uri: " + sTestPickerUri);
        } catch (IllegalArgumentException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("Bad mode: foo");
        }
    }

    @Test
    public void testPickerUriResolver_permissionDenied() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        updateReadUriPermission(sTestPickerUri, /* grant */ false);

        testOpenFile_permissionDenied(sTestPickerUri);
        testOpenTypedAssetFile_permissionDenied(sTestPickerUri);
        testQuery_permissionDenied(sTestPickerUri);
        testGetType_permissionDenied(sTestPickerUri);
    }

    @Test
    public void testPermissionGrantedOnOtherUserUri() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        // This test requires the uri to be valid in 2 different users, but the permission is
        // granted in one user only.
        final int otherUserId = 50;
        final Context otherUserContext = createOtherUserContext(otherUserId);
        final Uri mediaStoreUserInAnotherValidUser = createTestFileInContext(otherUserContext);
        final Uri grantedUri = getPickerUriForId(ContentUris.parseId(
                mediaStoreUserInAnotherValidUser), otherUserId, ACTION_PICK_IMAGES);
        updateReadUriPermission(grantedUri, /* grant */ true);

        final Uri deniedUri = sTestPickerUri;
        updateReadUriPermission(deniedUri, /* grant */ false);

        testOpenFile_permissionDenied(deniedUri);
        testOpenTypedAssetFile_permissionDenied(deniedUri);
        testQuery_permissionDenied(deniedUri);
        testGetType_permissionDenied(deniedUri);
    }

    @Test
    public void testPickerUriResolver_userInvalid() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        final int invalidUserId = 40;

        final Uri inValidUserPickerUri = getPickerUriForId(/* id */ 1, invalidUserId,
                ACTION_PICK_IMAGES);
        updateReadUriPermission(inValidUserPickerUri, /* grant */ true);

        // This method is called on current context when pickerUriResolver wants to get the content
        // resolver for another user.
        // NameNotFoundException is thrown when such a user does not exist.
        when(sCurrentContext.createPackageContextAsUser("android", /* flags= */ 0,
                UserHandle.of(invalidUserId))).thenThrow(
                        new PackageManager.NameNotFoundException());

        testOpenFileInvalidUser(inValidUserPickerUri);
        testOpenTypedAssetFileInvalidUser(inValidUserPickerUri);
        testQueryInvalidUser(inValidUserPickerUri);
        testGetTypeInvalidUser(inValidUserPickerUri);
    }

    @Test
    public void testPickerUriResolver_userValid() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        updateReadUriPermission(sTestPickerUri, /* grant */ true);

        assertThat(PickerUriResolver.getUserId(sTestPickerUri)).isEqualTo(TEST_USER);
        testOpenFile(sTestPickerUri);
        testOpenTypedAssetFile(sTestPickerUri);
        testQuery(sTestPickerUri);
        testGetType(sTestPickerUri, "image/jpeg");
    }

    @Test
    public void testPickerUriResolver_photoPicker() throws Exception {
        when(sCurrentContext
                .checkPermission(eq(MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION), anyInt(), anyInt()))
                .thenReturn(PERMISSION_GRANTED);
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);

        assertThat(PickerUriResolver.getUserId(sTestPickerUri)).isEqualTo(TEST_USER);
        testOpenFile(sTestPickerUri);
        testOpenTypedAssetFile(sTestPickerUri);
        testQuery(sTestPickerUri);
        testGetType(sTestPickerUri, "image/jpeg");
    }

    @Test
    public void testPickerUriResolver_thumbnailRequest() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        updateReadUriPermission(sTestPickerUri, /* grant */ true);
        assertThat(PickerUriResolver.getUserId(sTestPickerUri)).isEqualTo(TEST_USER);

        try {
            sOtherUserContext.attachInfoAndAddProvider(getTargetContext(),
                    new PhotoPickerProvider() {
                        @NonNull
                        @Override
                        public AssetFileDescriptor onOpenPreview(@NonNull String mediaId,
                                @NonNull Point size, @NonNull Bundle extras,
                                @NonNull CancellationSignal signal) throws FileNotFoundException {
                            assertThat(Long.parseLong(mediaId))
                                    .isEqualTo(ContentUris.parseId(sMediaStoreUriInOtherContext));
                            return mock(AssetFileDescriptor.class);
                        }
                    }, PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);

            try (AssetFileDescriptor afd = sTestPickerUriResolver.openTypedAssetFile(
                    sTestPickerUri, "image/*", /* opts */ null, /* signal */ null,
                    LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                            0), /* wantsThumb */ true)) {
                assertThat(afd).isNotNull();
            }
        } finally {
            sOtherUserContext.attachInfoAndAddProvider(getTargetContext(),
                    new PhotoPickerProvider(),
                    PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);
        }

    }

    @Test
    public void testPickerUriResolver_pickerUri_fileOpenWithRequireOriginal() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        // Grants given on original uri
        updateReadUriPermission(sTestPickerUri, /* grant */ true);
        sTestPickerUri = MediaStore.setRequireOriginal(sTestPickerUri);

        assertThat(PickerUriResolver.getUserId(sTestPickerUri)).isEqualTo(TEST_USER);
        try (ParcelFileDescriptor pfd = sTestPickerUriResolver.openFile(sTestPickerUri,
                "r", /* signal */ null,
                LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                        0))) {
            fail("Require original should not be supported for picker uri:" + sTestPickerUri);
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try (ParcelFileDescriptor pfd = sTestPickerUriResolver.openFile(sTestPickerUri,
                "r", /* signal */ null,
                LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                        0))) {
            fail("Require original should not be supported for picker uri:" + sTestPickerUri);
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        try (AssetFileDescriptor afd = sTestPickerUriResolver.openTypedAssetFile(sTestPickerUri,
                "image/*", /* opts */ null, /* signal */ null,
                LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                        0), /* wantsThumb */ false)) {
            fail("Require original should not be supported for picker uri:" + sTestPickerUri);
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try (AssetFileDescriptor afd = sTestPickerUriResolver.openTypedAssetFile(sTestPickerUri,
                "image/*", /* opts */ null, /* signal */ null,
                LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                        0), /* wantsThumb */ false)) {
            fail("Require original should not be supported for picker uri:" + sTestPickerUri);
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        testQuery(sTestPickerUri);
        testGetType(sTestPickerUri, "image/jpeg");
    }

    @Test
    public void testPickerUriResolver_pickerGetContentUri_fileOpenWithRequireOriginal()
            throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_GET_CONTENT);
        // Grants given on original uri
        updateReadUriPermission(sTestPickerUri, /* grant */ true);
        sTestPickerUri = MediaStore.setRequireOriginal(sTestPickerUri);

        assertThat(PickerUriResolver.getUserId(sTestPickerUri)).isEqualTo(TEST_USER);
        try (ParcelFileDescriptor pfd = sTestPickerUriResolver.openFile(sTestPickerUri,
                "r", /* signal */ null,
                LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                        ~LocalCallingIdentity.PERMISSION_IS_REDACTION_NEEDED))) {
            assertThat(pfd).isNotNull();
        }
        try (ParcelFileDescriptor pfd = sTestPickerUriResolver.openFile(sTestPickerUri,
                "r", /* signal */ null,
                LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                        LocalCallingIdentity.PERMISSION_IS_REDACTION_NEEDED))) {
            fail("Require original should not be supported when calling package does not have "
                    + "required permission");
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        try (AssetFileDescriptor afd = sTestPickerUriResolver.openTypedAssetFile(sTestPickerUri,
                "image/*", /* opts */ null, /* signal */ null,
                LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                        ~LocalCallingIdentity.PERMISSION_IS_REDACTION_NEEDED),
                /* wantsThumb */ false)) {
            assertThat(afd).isNotNull();
        }
        try (AssetFileDescriptor afd = sTestPickerUriResolver.openTypedAssetFile(sTestPickerUri,
                "image/*", /* opts */ null, /* signal */ null,
                LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                        LocalCallingIdentity.PERMISSION_IS_REDACTION_NEEDED),
                /* wantsThumb */ false)) {
            fail("Require original should not be supported when calling package does not have "
                    + "required permission");
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        testQuery(sTestPickerUri);
        testGetType(sTestPickerUri, "image/jpeg");
    }

    @Test
    public void testQueryUnknownColumn() throws Exception {
        sTestPickerUri = getPickerUriForId(ContentUris.parseId(sMediaStoreUriInOtherContext),
                TEST_USER, ACTION_PICK_IMAGES);
        final int myUid = Process.myUid();
        final int myPid = Process.myPid();
        final String myPackageName = getContext().getPackageName();
        final String[] invalidProjection = new String[] {"invalidColumn"};

        updateReadUriPermissionForSelf(sTestPickerUri, /* grant */ true);
        try (Cursor c = sTestPickerUriResolver.query(sTestPickerUri,
                invalidProjection, myPid, myUid, myPackageName)) {
            assertThat(c).isNotNull();
            assertThat(c.moveToFirst()).isTrue();
        } finally {
            updateReadUriPermissionForSelf(sTestPickerUri, /* grant */ false);
        }
    }

    private static IsolatedContext createOtherUserContext(int user) throws Exception {
        final UserHandle userHandle = UserHandle.of(user);
        // For unit testing: IsolatedContext is the context of another User: user.
        // PickerUriResolver should correctly be able to call into other user's content resolver
        // from the current context.
        final IsolatedContext otherUserContext = new IsolatedContext(getTargetContext(),
                "databases", /* asFuseThread */ false, userHandle);
        otherUserContext.setPickerUriResolver(new TestPickerUriResolver(otherUserContext));

        when(sCurrentContext.createPackageContextAsUser("android", /* flags= */ 0, userHandle)).
                thenReturn(otherUserContext);
        return otherUserContext;
    }

    private static Uri createTestFileInContext(Context context) throws Exception {
        TEST_FILE.createNewFile();
        // Write 1 byte because 0byte files are not valid in the picker db
        try (FileOutputStream fos = new FileOutputStream(TEST_FILE)) {
            fos.write(1);
        }

        final Uri uri = MediaStore.scanFile(context.getContentResolver(), TEST_FILE);
        assertThat(uri).isNotNull();
        MediaStore.waitForIdle(context.getContentResolver());
        return uri;
    }

    private void updateReadUriPermission(Uri uri, boolean grant) {
        final int permission = grant ? PERMISSION_GRANTED : PERMISSION_DENIED;
        when(sCurrentContext.checkUriPermission(uri, -1, -1,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)).thenReturn(permission);
    }

    private void updateReadUriPermissionForSelf(Uri uri, boolean grant) {
        final int permission = grant ? PERMISSION_GRANTED : PERMISSION_DENIED;
        when(sCurrentContext.checkUriPermission(uri, Process.myPid() , Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION)).thenReturn(permission);
    }

    private void updateReadUriPermissionForUid(Uri uri, boolean grant, int uid) {
        final int permission = grant ? PERMISSION_GRANTED : PERMISSION_DENIED;
        when(sCurrentContext.checkUriPermission(uri, -1 , uid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)).thenReturn(permission);
    }

    private static Uri getPickerUriForId(long id, int user, String action) {
        return getPickerUriForIdWithCustomAuthority(id, user, action,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);
    }

    private static Uri getPickerUriForIdWithCustomAuthority(long id, int user, String action,
            String authority) {
        final Uri providerUri = PickerUriResolver
                .getMediaUri(authority)
                .buildUpon()
                .appendPath(String.valueOf(id))
                .build();
        return PickerUriResolver.wrapProviderUri(providerUri, action, user);
    }

    private void testOpenFile(Uri uri) throws Exception {
        try (ParcelFileDescriptor pfd = sTestPickerUriResolver.openFile(uri, "r", /* signal */ null,
                LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                        0))) {
            assertThat(pfd).isNotNull();
        }
    }

    private void testOpenTypedAssetFile(Uri uri) throws Exception {
        try (AssetFileDescriptor afd = sTestPickerUriResolver.openTypedAssetFile(uri, "image/*",
                /* opts */ null, /* signal */ null,
                LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                        0), /* wantsThumb */ false)) {
            assertThat(afd).isNotNull();
        }
    }

    private void testQuery(Uri uri) throws Exception {
        Cursor result = sTestPickerUriResolver.query(uri,
                /* projection */ null, /* callingPid */ -1, /* callingUid */ -1,
                /* callingPackageName= */ TAG);
        assertThat(result).isNotNull();
        assertThat(result.getCount()).isEqualTo(1);
        result.moveToFirst();
        int idx = result.getColumnIndexOrThrow(CloudMediaProviderContract.MediaColumns.ID);
        assertThat(result.getString(idx)).isEqualTo(TEST_ID);
    }

    private void testGetType(Uri uri, String expectedMimeType) throws Exception {
        String mimeType = sTestPickerUriResolver.getType(uri,
                /* callingPid */ -1, /* callingUid */ -1);
        assertThat(mimeType).isEqualTo(expectedMimeType);
    }

    private void testOpenFileInvalidUser(Uri uri) {
        try {
            sTestPickerUriResolver.openFile(uri, "r", /* signal */ null,
                    LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                            0));
            fail("Invalid user specified in the picker uri: " + uri);
        } catch (FileNotFoundException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("No item at " + uri);
        }
    }

    private void testOpenTypedAssetFileInvalidUser(Uri uri) throws Exception {
        try {
            sTestPickerUriResolver.openTypedAssetFile(uri, "image/*", /* opts */ null,
                    /* signal */ null,
                    LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                            0), /* wantsThumb */ false);
            fail("Invalid user specified in the picker uri: " + uri);
        } catch (FileNotFoundException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("No item at " + uri);
        }
    }

    private void testQueryInvalidUser(Uri uri) throws Exception {
        Cursor result = sTestPickerUriResolver.query(uri, /* projection */ null,
                /* callingPid */ -1, /* callingUid */ -1, /* callingPackageName= */ TAG);
        assertThat(result).isNotNull();
        assertThat(result.getCount()).isEqualTo(0);
    }

    private void testGetTypeInvalidUser(Uri uri) throws Exception {
        try {
            sTestPickerUriResolver.getType(uri, /* callingPid */ -1, /* callingUid */ -1);
            fail("Invalid user specified in the picker uri: " + uri);
        } catch (IllegalStateException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("Cannot find content resolver for uri: "
                    + uri);
        }
    }

    private void testOpenFile_permissionDenied(Uri uri) throws Exception {
        try {
            sTestPickerUriResolver.openFile(uri, "r", /* signal */ null,
                    LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                            0));
            fail("openFile should fail if the caller does not have permission grant on the picker"
                    + " uri: " + uri);
        } catch (SecurityException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("Calling uid ( -1 ) does not have"
                    + " permission to access picker uri: " + uri);
        }
    }

    private void testOpenTypedAssetFile_permissionDenied(Uri uri) throws Exception {
        try {
            sTestPickerUriResolver.openTypedAssetFile(uri, "image/*", /* opts */ null,
                    /* signal */ null,
                    LocalCallingIdentity.forTest(sCurrentContext, /* uid */ -1, /* permission */
                            0), /* wantsThumb */ false);
            fail("openTypedAssetFile should fail if the caller does not have permission grant on"
                    + " the picker uri: " + uri);
        } catch (SecurityException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("Calling uid ( -1 ) does not have"
                    + " permission to access picker uri: " + uri);
        }
    }

    private void testQuery_permissionDenied(Uri uri) throws Exception {
        try {
            sTestPickerUriResolver.query(uri, /* projection */ null,
                    /* callingPid */ -1, /* callingUid */ -1, /* callingPackageName= */ TAG);
            fail("query should fail if the caller does not have permission grant on"
                    + " the picker uri: " + uri);
        } catch (SecurityException expected) {
            // expected
            assertThat(expected.getMessage()).isEqualTo("Calling uid ( -1 ) does not have"
                    + " permission to access picker uri: " + uri);
        }
    }

    private void testGetType_permissionDenied(Uri uri) throws Exception {
        if (SdkLevel.isAtLeastU()) {
            try {
                sTestPickerUriResolver.getType(uri, /* callingPid */ -1, /* callingUid */ -1);
                fail("getType should fail if the caller does not have permission grant on"
                        + " the picker uri: " + uri);
            } catch (SecurityException expected) {
                // expected
                assertThat(expected.getMessage()).isEqualTo("Calling uid ( -1 ) does not have"
                        + " permission to access picker uri: " + uri);
            }
        } else {
            // getType is unaffected by uri permission grants for U- builds
            testGetType(uri, "image/jpeg");
        }
    }
}
