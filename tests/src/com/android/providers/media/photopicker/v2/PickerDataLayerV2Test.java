/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.media.photopicker.v2;

import static com.android.providers.media.photopicker.util.PickerDbTestUtils.ALBUM_ID;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_3;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_4;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_PROVIDER;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.DATE_TAKEN_MS;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.GENERATION_MODIFIED;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.GIF_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.JPEG_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_3;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_4;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_PROVIDER;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.MP4_VIDEO_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.PNG_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.STANDARD_MIME_TYPE_EXTENSION;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.TEST_PACKAGE_NAME;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAddAlbumMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAddMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertInsertGrantsOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getAlbumCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getAlbumMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getCloudMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getLocalMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getMediaGrantsCursor;
import static com.android.providers.media.photopicker.v2.PickerDataLayerV2.COLUMN_GRANTS_COUNT;
import static com.android.providers.media.photopicker.v2.model.AlbumsCursorWrapper.EMPTY_MEDIA_ID;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MergeCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Process;
import android.os.UserHandle;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.test.InstrumentationRegistry;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.WorkManager;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.cloudproviders.SearchProvider;
import com.android.providers.media.photopicker.CategoriesState;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.SearchState;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.v2.model.MediaGroup;
import com.android.providers.media.photopicker.v2.model.MediaSource;
import com.android.providers.media.photopicker.v2.model.SearchSuggestion;
import com.android.providers.media.photopicker.v2.model.SearchTextRequest;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SearchSuggestionsDatabaseUtils;
import com.android.providers.media.photopicker.v2.sqlite.SearchSuggestionsQuery;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

public class PickerDataLayerV2Test {
    @Mock
    private PickerSyncController mMockSyncController;
    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private SearchState mSearchState;
    @Mock
    private WorkManager mMockWorkManager;
    @Mock
    private Operation mMockOperation;
    @Mock
    private ListenableFuture<Operation.State.SUCCESS> mMockFuture;
    @Mock
    CategoriesState mCategoriesState;
    private PickerDbFacade mFacade;
    private Context mContext;
    private MockContentResolver mMockContentResolver;
    private TestContentProvider mLocalProvider;
    private TestContentProvider mCloudProvider;


    private static class TestContentProvider extends MockContentProvider {
        private Cursor mQueryResult = null;

        TestContentProvider() {
            super();
        }

        @Override
        public Cursor query(Uri uri,
                String[] projection,
                Bundle queryArgs,
                CancellationSignal cancellationSignal) {
            return mQueryResult;
        }

        public void setQueryResult(Cursor queryResult) {
            this.mQueryResult = queryResult;
        }
    }

    @Before
    public void setUp() {
        initMocks(this);
        PickerSyncController.setInstance(mMockSyncController);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        mFacade = new PickerDbFacade(mContext, new PickerSyncLockManager(), LOCAL_PROVIDER);
        mFacade.setCloudProvider(CLOUD_PROVIDER);
        mLocalProvider = new TestContentProvider();
        mCloudProvider = new TestContentProvider();
        mMockContentResolver = new MockContentResolver();
        mMockContentResolver.addProvider(LOCAL_PROVIDER, mLocalProvider);
        mMockContentResolver.addProvider(CLOUD_PROVIDER, mCloudProvider);

        doReturn(LOCAL_PROVIDER).when(mMockSyncController).getLocalProvider();
        doReturn(CLOUD_PROVIDER).when(mMockSyncController).getCloudProvider();
        doReturn(CLOUD_PROVIDER).when(mMockSyncController).getCloudProviderOrDefault(any());
        doReturn(mFacade).when(mMockSyncController).getDbFacade();
        doReturn(mSearchState).when(mMockSyncController).getSearchState();
        doReturn(mCategoriesState).when(mMockSyncController).getCategoriesState();
        doReturn(new PickerSyncLockManager()).when(mMockSyncController).getPickerSyncLockManager();
        doReturn(mMockContentResolver).when(mMockContext).getContentResolver();
    }

    @After
    public void tearDown() {
        if (mFacade != null) {
            mFacade.setCloudProvider(null);
        }
    }

    @Test
    public void testAvailableProvidersNoCloudProvider() {
        doReturn(/* cloudProviderAuthority */ null)
                .when(mMockSyncController).getCloudProviderOrDefault(any());

        final ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.packageName = LOCAL_PROVIDER;
        providerInfo.name = "LOCAL_PROVIDER";
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.nonLocalizedLabel = providerInfo.name;
        providerInfo.applicationInfo = applicationInfo;
        doReturn(mMockPackageManager)
                .when(mMockContext).getPackageManager();
        doReturn(providerInfo)
                .when(mMockPackageManager)
                .resolveContentProvider(any(), anyInt());

        try (Cursor availableProviders = PickerDataLayerV2.queryAvailableProviders(mMockContext)) {
            availableProviders.moveToFirst();

            assertEquals(
                    "Only local provider should be available when cloud provider is null",
                    /* expected */ 1,
                    availableProviders.getCount()
            );

            assertEquals(
                    "Available provider should serve local media",
                    /* expected */ MediaSource.LOCAL,
                    MediaSource.valueOf(availableProviders.getString(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .MEDIA_SOURCE.getColumnName())))
            );

            assertEquals(
                    "Local provider authority is not correct",
                    /* expected */ LOCAL_PROVIDER,
                    availableProviders.getString(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .AUTHORITY.getColumnName()))
            );

            assertEquals(
                    "Local provider UID is not correct",
                    /* expected */ Process.myUid(),
                    availableProviders.getInt(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .UID.getColumnName()))
            );

            assertEquals(
                    "Local provider's label is not correct",
                    /* expected */ "LOCAL_PROVIDER",
                    availableProviders.getString(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .DISPLAY_NAME.getColumnName()))
            );
        }
    }

    @Test
    public void testAvailableProvidersWithCloudProvider() throws
            PackageManager.NameNotFoundException {
        final int cloudUID = Integer.MAX_VALUE;
        final ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.packageName = CLOUD_PROVIDER;
        providerInfo.name = "PROVIDER";
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.nonLocalizedLabel = providerInfo.name;
        providerInfo.applicationInfo = applicationInfo;

        doReturn(mMockPackageManager)
                .when(mMockContext).getPackageManager();
        doReturn(cloudUID)
                .when(mMockPackageManager)
                .getPackageUid(any(), anyInt());
        doReturn(providerInfo)
                .when(mMockPackageManager)
                .resolveContentProvider(any(), anyInt());

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor availableProviders = PickerDataLayerV2.queryAvailableProviders(mMockContext)) {
            availableProviders.moveToFirst();

            assertEquals(
                    "Both local and cloud providers should be available",
                    /* expected */ 2,
                    availableProviders.getCount()
            );

            assertEquals(
                    "Available provider should serve local media",
                    /* expected */ MediaSource.LOCAL,
                    MediaSource.valueOf(availableProviders.getString(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .MEDIA_SOURCE.getColumnName())))
            );

            assertEquals(
                    "Local provider authority is not correct",
                    /* expected */ LOCAL_PROVIDER,
                    availableProviders.getString(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .AUTHORITY.getColumnName()))
            );

            assertEquals(
                    "Local provider UID is not correct",
                    /* expected */ Process.myUid(),
                    availableProviders.getInt(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .UID.getColumnName()))
            );

            assertEquals(
                    "Local provider's label is not correct",
                    /* expected */ "PROVIDER",
                    availableProviders.getString(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .DISPLAY_NAME.getColumnName()))
            );

            availableProviders.moveToNext();

            assertEquals(
                    "Available provider should serve remote media",
                    /* expected */ MediaSource.REMOTE,
                    MediaSource.valueOf(availableProviders.getString(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .MEDIA_SOURCE.getColumnName())))
            );

            assertEquals(
                    "Cloud provider authority is not correct",
                    /* expected */ CLOUD_PROVIDER,
                    availableProviders.getString(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .AUTHORITY.getColumnName()))
            );

            assertEquals(
                    "Cloud provider UID is not correct",
                    /* expected */ cloudUID,
                    availableProviders.getInt(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .UID.getColumnName()))
            );

            assertEquals(
                    "Cloud provider's label is not correct",
                    /* expected */ "PROVIDER",
                    availableProviders.getString(
                            availableProviders.getColumnIndexOrThrow(
                                    PickerSQLConstants.AvailableProviderResponse
                                            .DISPLAY_NAME.getColumnName()))
            );
        }
    }

    @Test
    public void testQueryMediaWithInvalidProviders() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, DATE_TAKEN_MS, /* pageSize */ 2,
                        new ArrayList<>(Arrays.asList("invalid.provider"))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testQueryMediaWithCloudQueryDisabled() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, DATE_TAKEN_MS, /* pageSize */ 2,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testQueryLocalMediaSortOrder() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS + 1,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 3,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            // LOCAL_ID_1 has the most recent date taken.
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            // LOCAL_ID_2 and LOCAL_ID_3 have the same date taken but the Picker ID of LOCAL_ID_3
            // should be greater.
            assertMediaCursor(cr, LOCAL_ID_3, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testQueryLocalMediaWithGrants() {
        Cursor cursorForMediaWithoutGrants = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS + 1,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorForMediaWithGrants = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS,
                GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorForMediaWithoutGrants,
                /* writeCount */1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorForMediaWithGrants,
                /* writeCount */1);
        int testUid = 123;
        doReturn(mMockPackageManager)
                .when(mMockContext).getPackageManager();
        String[] packageNames = new String[]{TEST_PACKAGE_NAME};
        doReturn(packageNames).when(mMockPackageManager).getPackagesForUid(testUid);
        // insert a grant for the second item inserted in media.
        assertInsertGrantsOperation(mFacade, getMediaGrantsCursor(LOCAL_ID_2), /* writeCount */1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 3,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        new ArrayList<>(Arrays.asList("video/*")),
                        MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP,
                        testUid))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            // verify item with isPreGranted as false.
            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE, MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP,
                    /* isPreGranted */ false);

            // verify item with isPreGranted as true.
            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE,
                    MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP,
                    /* isPreGranted */ true);
        }
    }

    @Test
    public void testQueryLocalMediaForPreview() {
        Cursor cursorForMediaWithoutGrants = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS + 1,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorForMediaWithGrants = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS,
                GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorForMediaWithGrantsButDeSelected = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS,
                GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorForMediaWithoutGrants,
                /* writeCount */1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorForMediaWithGrants,
                /* writeCount */1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorForMediaWithGrantsButDeSelected,
                /* writeCount */1);

        int testUid = 123;
        doReturn(mMockPackageManager)
                .when(mMockContext).getPackageManager();
        String[] packageNames = new String[]{TEST_PACKAGE_NAME};
        doReturn(packageNames).when(mMockPackageManager).getPackagesForUid(testUid);
        // insert a grant for the second item inserted in media.
        assertInsertGrantsOperation(mFacade, getMediaGrantsCursor(LOCAL_ID_2), /* writeCount */1);
        // insert a grant for the third item inserted in media.
        assertInsertGrantsOperation(mFacade, getMediaGrantsCursor(LOCAL_ID_3), /* writeCount */1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());

        Bundle extras = getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 3,
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                new ArrayList<>(Arrays.asList("video/*")),
                MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP,
                testUid);

        extras.putBoolean("is_preview_session", true);
        extras.putBoolean("is_first_page", true);
        extras.putStringArrayList("current_de_selection", new ArrayList<>(List.of(LOCAL_ID_3)));
        extras.putStringArrayList("current_selection", new ArrayList<>(List.of(LOCAL_ID_1)));

        // Expected result:
        // 1. one item with LOCAL_ID_1 that has been added as current selection.
        // 2. one item with LOCAL_ID_2 which is a pre-granted item.
        // 3. item with LOCAL_ID_3 should not be included in the cursor because it is de-selected.

        try (Cursor cr = PickerDataLayerV2.queryPreviewMedia(
                mMockContext, extras)) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            // verify item with isPreGranted as false.
            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE, MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP,
                    /* isPreGranted */ false);

            // verify item with isPreGranted as true.
            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE,
                    MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP,
                    /* isPreGranted */ true);
        }
    }

    @Test
    public void queryMediaOnlyLocalWithPreSelection() {
        Cursor cursorLocal1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorLocal2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorCloud1 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorCloud2 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorLocal1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorLocal2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursorCloud1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursorCloud2, 1);

        Bundle queryArgs = getMediaQueryExtras(Long.MAX_VALUE, DATE_TAKEN_MS, /* pageSize */ 2,
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));

        queryArgs.putInt(Intent.EXTRA_UID, Process.myUid());
        // add uris for selection
        String uriPlaceHolder = "content://media/picker/0/%s/media/%s";
        queryArgs.putStringArrayList("pre_selection_uris", new ArrayList<>(Arrays.asList(
                String.format(uriPlaceHolder, LOCAL_PROVIDER, LOCAL_ID_1) // valid local uri
        )));


        try (Cursor cr = PickerDataLayerV2.queryMediaForPreSelection(
                mMockContext, queryArgs)) {
            // only the 1 local item in the input uris should be returned.
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void queryMediaCloudOnlyWithPreSelection() {
        Cursor cursorLocal1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorLocal2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorCloud1 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorCloud2 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorLocal1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorLocal2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursorCloud1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursorCloud2, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());


        Bundle queryArgs = getMediaQueryExtras(Long.MAX_VALUE, DATE_TAKEN_MS, /* pageSize */ 2,
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));

        queryArgs.putInt(Intent.EXTRA_UID, Process.myUid());
        // add uris for selection
        String uriPlaceHolder = "content://media/picker/0/%s/media/%s";
        queryArgs.putStringArrayList("pre_selection_uris", new ArrayList<>(Arrays.asList(
                String.format(uriPlaceHolder, CLOUD_PROVIDER, CLOUD_ID_2) // valid cloud uri
        )));


        try (Cursor cr = PickerDataLayerV2.queryMediaForPreSelection(
                mMockContext, queryArgs)) {
            // only the 1 cloud items in the input uris should be returned.
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertMediaCursor(cr, CLOUD_ID_2, CLOUD_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void queryMediaWithCloudQueryEnabledWithPreSelection() {
        Cursor cursorLocal1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorLocal2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorCloud1 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursorCloud2 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorLocal1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursorLocal2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursorCloud1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursorCloud2, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());


        Bundle queryArgs = getMediaQueryExtras(Long.MAX_VALUE, DATE_TAKEN_MS, /* pageSize */ 2,
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));

        queryArgs.putInt(Intent.EXTRA_UID, Process.myUid());
        // add uris for selection
        String uriPlaceHolder = "content://media/picker/0/%s/media/%s";
        queryArgs.putStringArrayList("pre_selection_uris", new ArrayList<>(Arrays.asList(
                String.format(uriPlaceHolder, LOCAL_PROVIDER, LOCAL_ID_1), // valid local uri
                String.format(uriPlaceHolder, CLOUD_PROVIDER, CLOUD_ID_2), // valid cloud uri
                // uri for invalid media as LOCAL_ID_3 this has not been inserted,
                String.format(uriPlaceHolder, LOCAL_PROVIDER, LOCAL_ID_3),
                // uri with invalid cloud provider
                String.format(uriPlaceHolder, "cloud.provider.invalid", CLOUD_ID_2)
                )));


        try (Cursor cr = PickerDataLayerV2.queryMediaForPreSelection(
                mMockContext, queryArgs)) {
            // only the 2 items in the input uris should be returned.
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertMediaCursor(cr, CLOUD_ID_2, CLOUD_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testFetchMediaGrantsCount() {
        int testUid = 123;
        int userId = PickerSyncController.uidToUserId(testUid);
        doReturn(mMockPackageManager)
                .when(mMockContext).getPackageManager();
        String[] packageNames = new String[]{TEST_PACKAGE_NAME};
        doReturn(packageNames).when(mMockPackageManager).getPackagesForUid(testUid);


        // insert 2 grants corresponding to testUid.
        assertInsertGrantsOperation(mFacade,
                getMediaGrantsCursor(LOCAL_ID_1, TEST_PACKAGE_NAME, userId), /* writeCount */1);
        assertInsertGrantsOperation(mFacade,
                getMediaGrantsCursor(LOCAL_ID_2, TEST_PACKAGE_NAME, userId), /* writeCount */1);

        // insert grants with different packageName or userIds.
        String TEST_PACKAGE_NAME_2 = "package.name.two";
        int TEST_USER_ID_2 = 10;

        // same id but different packageName
        assertInsertGrantsOperation(mFacade, getMediaGrantsCursor(LOCAL_ID_2, TEST_PACKAGE_NAME_2,
                UserHandle.myUserId()), /* writeCount */1);
        // same id but different userId
        assertInsertGrantsOperation(mFacade, getMediaGrantsCursor(LOCAL_ID_2, TEST_PACKAGE_NAME,
                TEST_USER_ID_2), /* writeCount */1);
        // both packageName and userId different
        assertInsertGrantsOperation(mFacade,
                getMediaGrantsCursor(LOCAL_ID_2, TEST_PACKAGE_NAME_2, TEST_USER_ID_2), 1);
        // every aspect different
        assertInsertGrantsOperation(mFacade,
                getMediaGrantsCursor(LOCAL_ID_3, TEST_PACKAGE_NAME_2, TEST_USER_ID_2), 1);

        Bundle input = new Bundle();
        input.putInt(Intent.EXTRA_UID, testUid);

        try (Cursor cr = PickerDataLayerV2.fetchMediaGrantsCount(
                mMockContext, input)) {

            // cursor should only contain 1 row that represents the count.
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(1);

            // verify that the cursor contains the count. Ensure that only 2 grants are considered
            // even when there were total 4 grants inserted. This ensures that the grants were
            // filtered properly based on the packageName and UserId.
            cr.moveToFirst();
            int columnIndexForCount = cr.getColumnIndex(COLUMN_GRANTS_COUNT);
            assertWithMessage(
                    "column index should not be -1.")
                    .that(columnIndexForCount).isNotEqualTo(-1);
            assertWithMessage(
                    "Unexpected number grants count, expected to be 2.")
                    .that(cr.getInt(columnIndexForCount)).isEqualTo(2);
        }
    }

    @Test
    public void queryMediaWithCloudQueryEnabled() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, DATE_TAKEN_MS, /* pageSize */ 2,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertMediaCursor(cr, CLOUD_ID, CLOUD_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testQueryCloudMediaSortOrder() {
        Cursor cursor1 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS + 1,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 3,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            // CLOUD_ID_1 has the most recent date taken.
            assertMediaCursor(cr, CLOUD_ID_1, CLOUD_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            // CLOUD_ID_2 and CLOUD_ID_3 have the same date taken but the Picker ID of CLOUD_ID_3
            // should be greater.
            assertMediaCursor(cr, CLOUD_ID_3, CLOUD_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, CLOUD_ID_2, CLOUD_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testQueryLocalAndCloudMediaSortOrder() {
        Cursor cursor1 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS + 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 3,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            // CLOUD_ID_1 has the most recent date taken.
            assertMediaCursor(cr, CLOUD_ID_1, CLOUD_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            // LOCAL_ID_1 and CLOUD_ID_3 have the same date taken but the Picker ID of LOCAL_ID_1
            // should be greater.
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, CLOUD_ID_2, CLOUD_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testQueryMediaActionGetContent() {
        Cursor cursor1 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS + 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        final Bundle mediaQueryExtras = getMediaQueryExtras(Long.MAX_VALUE,
                Long.MAX_VALUE, /* pageSize */ 3,
                new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)));
        mediaQueryExtras.putString("intent_action", Intent.ACTION_GET_CONTENT);
        try (Cursor cr = PickerDataLayerV2.queryMedia(mMockContext, mediaQueryExtras)) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            // CLOUD_ID_1 has the most recent date taken.
            assertMediaCursor(cr, CLOUD_ID_1, CLOUD_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE, Intent.ACTION_GET_CONTENT);

            cr.moveToNext();
            // LOCAL_ID_1 and CLOUD_ID_3 have the same date taken but the Picker ID of LOCAL_ID_1
            // should be greater.
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE,
                    Intent.ACTION_GET_CONTENT);

            cr.moveToNext();
            assertMediaCursor(cr, CLOUD_ID_2, CLOUD_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE,
                    Intent.ACTION_GET_CONTENT);
        }
    }

    @Test
    public void testLocalAndCloudQueryDedupe() {
        Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, DATE_TAKEN_MS - 1);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS + 1,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 3,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, CLOUD_ID_2, CLOUD_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testAllVideoMimeTypeFilter() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        new ArrayList<>(Arrays.asList("video/*"))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testAllImageMimeTypeFilter() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        new ArrayList<>(Arrays.asList("image/*"))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_4, LOCAL_PROVIDER, DATE_TAKEN_MS, GIF_IMAGE_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_3, LOCAL_PROVIDER, DATE_TAKEN_MS,
                    PNG_IMAGE_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS, JPEG_IMAGE_MIME_TYPE);
        }
    }

    @Test
    public void testSpecificMimeTypeFilter() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        new ArrayList<>(
                                Arrays.asList(GIF_IMAGE_MIME_TYPE, JPEG_IMAGE_MIME_TYPE))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_4, LOCAL_PROVIDER, DATE_TAKEN_MS, GIF_IMAGE_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS, JPEG_IMAGE_MIME_TYPE);
        }
    }

    @Test
    public void testMixOfGeneralAndSpecificMimeTypeFilters() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        new ArrayList<String>(
                                Arrays.asList(GIF_IMAGE_MIME_TYPE, "video/*"))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_4, LOCAL_PROVIDER, DATE_TAKEN_MS,
                    GIF_IMAGE_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }


    @Test
    public void testDefaultAlbumsWithCloudQueriesDisabled() {
        Cursor cursor1 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(CLOUD_ID_4, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor4, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbums(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            // Favorites album will be displayed by default
            cr.moveToFirst();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            // Camera album will be displayed by default
            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);
        }
    }

    @Test
    public void testFavoritesAlbumMediaWithCloudDisabled() {
        Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, DATE_TAKEN_MS,
                GIF_IMAGE_MIME_TYPE, /* isFavorite */ true);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                GIF_IMAGE_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS - 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cursor4 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                GIF_IMAGE_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbumMedia(
                mMockContext, getAlbumMediaQueryExtras(
                        Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        LOCAL_PROVIDER),
                CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES)) {

            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS, GIF_IMAGE_MIME_TYPE);
        }
    }

    @Test
    public void testFavoritesAlbumMediaWithLocalAndCloudItems() {
        Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, DATE_TAKEN_MS,
                GIF_IMAGE_MIME_TYPE, /* isFavorite */ true);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                GIF_IMAGE_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS - 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cursor4 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                GIF_IMAGE_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbumMedia(
                mMockContext, getAlbumMediaQueryExtras(
                        Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        LOCAL_PROVIDER),
                CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES)) {

            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS, GIF_IMAGE_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS, GIF_IMAGE_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, CLOUD_ID_2, CLOUD_PROVIDER, DATE_TAKEN_MS - 1,
                    GIF_IMAGE_MIME_TYPE);
        }
    }

    @Test
    public void testVideosMergedAlbumMediaWithCloudDisabled() {
        Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, DATE_TAKEN_MS,
                MP4_VIDEO_MIME_TYPE, /* isFavorite */ true);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS - 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cursor4 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                GIF_IMAGE_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbumMedia(
                mMockContext, getAlbumMediaQueryExtras(
                        Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        LOCAL_PROVIDER),
                CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS)) {

            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testVideosMergedAlbumMedia() {
        Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, DATE_TAKEN_MS,
                MP4_VIDEO_MIME_TYPE, /* isFavorite */ true);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS - 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cursor4 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                GIF_IMAGE_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbumMedia(
                mMockContext, getAlbumMediaQueryExtras(
                        Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        LOCAL_PROVIDER),
                CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS)) {

            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, CLOUD_ID_2, CLOUD_PROVIDER, DATE_TAKEN_MS - 1,
                    MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testLocalAlbumMediaQuery() {
        Cursor cursor1 = getAlbumMediaCursor(LOCAL_ID_1, /* cloudId */ null, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getAlbumMediaCursor(LOCAL_ID_2, /* cloudId */ null, DATE_TAKEN_MS);
        Cursor cursor3 = getAlbumMediaCursor(/* localId */ null, CLOUD_ID_1, DATE_TAKEN_MS);

        assertAddAlbumMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1, ALBUM_ID);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbumMedia(
                mMockContext, getAlbumMediaQueryExtras(
                        Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        LOCAL_PROVIDER),
                ALBUM_ID)) {

            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS,
                    MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testCloudAlbumMediaQuery() {
        Cursor cursor1 = getAlbumMediaCursor(LOCAL_ID_1, /* cloudId */ null, DATE_TAKEN_MS);
        Cursor cursor2 = getAlbumMediaCursor(/* localId */ null, CLOUD_ID_1, DATE_TAKEN_MS);

        assertAddAlbumMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1, ALBUM_ID);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbumMedia(
                mMockContext, getAlbumMediaQueryExtras(
                        Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER)),
                        CLOUD_PROVIDER),
                ALBUM_ID)) {

            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertMediaCursor(cr, CLOUD_ID_1, CLOUD_PROVIDER, DATE_TAKEN_MS,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS,
                    MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    @Ignore("TODO(b/339604051): Enable when the bug is fixed.")
    public void testCloudAlbumMediaQueryWhenCloudIsDisabled() {
        Cursor cursor1 = getAlbumMediaCursor(LOCAL_ID_1, /* cloudId */ null, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getAlbumMediaCursor(LOCAL_ID_2, /* cloudId */ null, DATE_TAKEN_MS);
        Cursor cursor3 = getAlbumMediaCursor(/* localId */ null, CLOUD_ID_1, DATE_TAKEN_MS);

        assertAddAlbumMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1, ALBUM_ID);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbumMedia(
                mMockContext, getAlbumMediaQueryExtras(
                        Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER)),
                        CLOUD_PROVIDER),
                ALBUM_ID)) {

            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testLocalVideosAlbum() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbums(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            // Favorites album will be displayed by default
            cr.moveToFirst();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            // Camera album will be displayed by default
            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_2);
        }
    }

    @Test
    public void testCloudVideosAlbum() {
        Cursor cursor1 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(CLOUD_ID_4, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor4, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbums(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            // Favorites albums will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID, MediaSource.LOCAL);

            // Camera album will be displayed by default
            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ CLOUD_ID_2,
                    MediaSource.REMOTE);
        }
    }

    @Test
    public void testMergedLocalAndCloudVideosAlbum() {
        Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, DATE_TAKEN_MS);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS - 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbums(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            // Favorites albums will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            // Camera album will be displayed by default
            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_1);
        }
    }

    @Test
    public void testLocalFavoritesAlbum() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS + 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor4, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbums(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            // Favorites albums will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_2);

            // Camera album will be displayed by default
            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);
        }
    }

    @Test
    public void testCloudFavoritesAlbum() {
        Cursor cursor1 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cursor2 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS + 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbums(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ CLOUD_ID_1,
                    MediaSource.REMOTE);

            // Camera album will be displayed by default
            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            cr.moveToNext();
            // Videos album will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID,
                    MediaSource.LOCAL);
        }
    }

    @Test
    public void testMergedLocalAndCloudFavoritesAlbum() {
        Cursor cursor1 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, DATE_TAKEN_MS,
                GIF_IMAGE_MIME_TYPE, /* isFavorite */ true);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS,
                GENERATION_MODIFIED, /* mediaStoreUri */ null, /* sizeBytes */ 1,
                GIF_IMAGE_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS - 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryAlbums(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_1);

            cr.moveToNext();
            // Camera album will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            cr.moveToNext();
            // Videos album will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);
        }
    }

    @Test
    public void testLocalAlbums() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        Cursor cursor2 = getAlbumCursor(CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                DATE_TAKEN_MS, LOCAL_ID_2, LOCAL_PROVIDER);
        mLocalProvider.setQueryResult(cursor2);

        try (Cursor cr = PickerDataLayerV2.queryAlbums(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            // Favorites album will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            cr.moveToNext();
            // Camera album will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_2);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_1);
        }
    }

    @Test
    public void testCloudAlbums() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        Cursor cursor2 = getAlbumCursor("CloudAlbum", DATE_TAKEN_MS, CLOUD_ID_1, CLOUD_PROVIDER);
        mCloudProvider.setQueryResult(cursor2);

        try (Cursor cr = PickerDataLayerV2.queryAlbums(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(4);

            cr.moveToFirst();
            // Favorites albums will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            cr.moveToNext();
            // Camera album will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_1);

            cr.moveToNext();
            assertAlbumCursor(cr, /* albumId */ "CloudAlbum", CLOUD_PROVIDER,
                    /* dateTaken */ DATE_TAKEN_MS, /* coverMediaId */ CLOUD_ID_1);
        }
    }

    @Test
    public void testCloudAndLocalAlbums() {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);

        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        List<Cursor> localAlbumCursors = new ArrayList<>();
        localAlbumCursors.add(getAlbumCursor(
                CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS,
                DATE_TAKEN_MS, LOCAL_ID_2, LOCAL_PROVIDER));
        localAlbumCursors.add(getAlbumCursor(
                CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS,
                DATE_TAKEN_MS, LOCAL_ID_2, LOCAL_PROVIDER));
        localAlbumCursors.add(getAlbumCursor(
                CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                DATE_TAKEN_MS, LOCAL_ID_2, LOCAL_PROVIDER));
        MergeCursor allLocalAlbumsCursor =
                new MergeCursor(localAlbumCursors.toArray(new Cursor[0]));
        mLocalProvider.setQueryResult(allLocalAlbumsCursor);

        Cursor cursor5 = getAlbumCursor("CloudAlbum", DATE_TAKEN_MS, CLOUD_ID_1, CLOUD_PROVIDER);
        mCloudProvider.setQueryResult(cursor5);

        try (Cursor cr = PickerDataLayerV2.queryAlbums(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 10,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(6);

            cr.moveToFirst();
            // Favorites albums will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ EMPTY_MEDIA_ID);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_2);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_1);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_2);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_2);


            cr.moveToNext();
            assertAlbumCursor(cr, /* albumId */ "CloudAlbum", CLOUD_PROVIDER,
                    /* dateTaken */ DATE_TAKEN_MS, /* coverMediaId */ CLOUD_ID_1);
        }
    }

    @Test
    public void testPaginationFirstPage() {
        Cursor cursor1 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cursor2 = getLocalMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS + 1);
        Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS + 2);
        Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS + 3);
        Cursor cursor5 = getLocalMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS + 4);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor5, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, /* pageSize */ 3,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_4, LOCAL_PROVIDER, DATE_TAKEN_MS + 4,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_3, LOCAL_PROVIDER, DATE_TAKEN_MS + 3,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS + 2,
                    MP4_VIDEO_MIME_TYPE);

            assertWithMessage("Unexpected value of previous date taken in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .PREV_PAGE_DATE_TAKEN.getKey(), Long.MIN_VALUE))
                    .isEqualTo(Long.MIN_VALUE);

            assertWithMessage("Unexpected value of previous picker id in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .PREV_PAGE_ID.getKey(), Long.MIN_VALUE))
                    .isEqualTo(Long.MIN_VALUE);

            assertWithMessage("Unexpected value of next date taken in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .NEXT_PAGE_DATE_TAKEN.getKey(), Long.MIN_VALUE))
                    .isEqualTo(DATE_TAKEN_MS + 1);

            assertWithMessage("Unexpected value of next picker id in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .NEXT_PAGE_ID.getKey(), Long.MIN_VALUE))
                    .isEqualTo(2);

            assertWithMessage("Unexpected value of items before count in the media cursor.")
                    .that(cr.getExtras().getInt(PickerSQLConstants.MediaResponseExtras
                            .ITEMS_BEFORE_COUNT.getKey(), Integer.MIN_VALUE))
                    .isEqualTo(0);
        }
    }

    @Test
    public void testPaginationLastPage() {
        Cursor cursor1 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cursor2 = getLocalMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS + 1);
        Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS + 2);
        Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS + 3);
        Cursor cursor5 = getLocalMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS + 4);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor5, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE,
                        DATE_TAKEN_MS + 1,
                        /* pageSize */ 2,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);

            assertWithMessage("Unexpected value of previous date taken in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .PREV_PAGE_DATE_TAKEN.getKey(), Long.MIN_VALUE))
                    .isEqualTo(DATE_TAKEN_MS + 3);

            assertWithMessage("Unexpected value of previous picker id in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .PREV_PAGE_ID.getKey(), Long.MIN_VALUE))
                    .isEqualTo(4);

            assertWithMessage("Unexpected value of next date taken in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .NEXT_PAGE_DATE_TAKEN.getKey(), Long.MIN_VALUE))
                    .isEqualTo(Long.MIN_VALUE);

            assertWithMessage("Unexpected value of next picker id in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .NEXT_PAGE_ID.getKey(), Long.MIN_VALUE))
                    .isEqualTo(Long.MIN_VALUE);

            assertWithMessage("Unexpected value of items before count in the media cursor.")
                    .that(cr.getExtras().getInt(PickerSQLConstants.MediaResponseExtras
                            .ITEMS_BEFORE_COUNT.getKey(), Integer.MIN_VALUE))
                    .isEqualTo(3);
        }
    }

    @Test
    public void testPaginationLastPagePartialPreviousPage() {
        Cursor cursor1 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cursor2 = getLocalMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS + 1);
        Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS + 2);
        Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS + 3);
        Cursor cursor5 = getLocalMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS + 4);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor5, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE,
                        DATE_TAKEN_MS + 2,
                        /* pageSize */ 3,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS + 2,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID, LOCAL_PROVIDER, DATE_TAKEN_MS, MP4_VIDEO_MIME_TYPE);

            assertWithMessage("Unexpected value of previous date taken in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .PREV_PAGE_DATE_TAKEN.getKey(), Long.MIN_VALUE))
                    .isEqualTo(DATE_TAKEN_MS + 4);

            assertWithMessage("Unexpected value of previous picker id in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .PREV_PAGE_ID.getKey(), Long.MIN_VALUE))
                    .isEqualTo(5);

            assertWithMessage("Unexpected value of next date taken in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .NEXT_PAGE_DATE_TAKEN.getKey(), Long.MIN_VALUE))
                    .isEqualTo(Long.MIN_VALUE);

            assertWithMessage("Unexpected value of next picker id in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .NEXT_PAGE_ID.getKey(), Long.MIN_VALUE))
                    .isEqualTo(Long.MIN_VALUE);

            assertWithMessage("Unexpected value of items before count in the media cursor.")
                    .that(cr.getExtras().getInt(PickerSQLConstants.MediaResponseExtras
                            .ITEMS_BEFORE_COUNT.getKey(), Integer.MIN_VALUE))
                    .isEqualTo(2);
        }
    }

    @Test
    public void testPaginationMiddlePage() {
        Cursor cursor1 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cursor2 = getLocalMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS + 1);
        Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS + 2);
        Cursor cursor4 = getLocalMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS + 3);
        Cursor cursor5 = getLocalMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS + 4);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor4, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor5, 1);

        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(false).when(mMockSyncController).shouldQueryCloudMedia(any(), any());

        try (Cursor cr = PickerDataLayerV2.queryMedia(
                mMockContext, getMediaQueryExtras(Long.MAX_VALUE,
                        DATE_TAKEN_MS + 2,
                        /* pageSize */ 2,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, CLOUD_PROVIDER))))) {
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertMediaCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER, DATE_TAKEN_MS + 2,
                    MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertMediaCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER, DATE_TAKEN_MS + 1,
                    MP4_VIDEO_MIME_TYPE);

            assertWithMessage("Unexpected value of previous date taken in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .PREV_PAGE_DATE_TAKEN.getKey(), Long.MIN_VALUE))
                    .isEqualTo(DATE_TAKEN_MS + 4);

            assertWithMessage("Unexpected value of previous picker id in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .PREV_PAGE_ID.getKey(), Long.MIN_VALUE))
                    .isEqualTo(5);

            assertWithMessage("Unexpected value of next date taken in the media cursor.")
                    .that(cr.getExtras().getLong(PickerSQLConstants.MediaResponseExtras
                            .NEXT_PAGE_DATE_TAKEN.getKey(), Long.MIN_VALUE))
                    .isEqualTo(DATE_TAKEN_MS);

            assertWithMessage("Unexpected value of next picker id in the media cursor.")
                    .that(cr.getExtras().getLong("next_page_picker_id", Long.MIN_VALUE))
                    .isEqualTo(1);

            assertWithMessage("Unexpected value of items before count in the media cursor.")
                    .that(cr.getExtras().getInt(PickerSQLConstants.MediaResponseExtras
                            .ITEMS_BEFORE_COUNT.getKey(), Integer.MIN_VALUE))
                    .isEqualTo(2);
        }
    }

    @Test
    public void testQuerySearchSuggestionsZeroState() {
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController)
                .getCloudProviderOrDefault(any());
        doReturn(mSearchState).when(mMockSyncController).getSearchState();
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());
        doReturn(true).when(mSearchState).isCloudSearchEnabled(any());

        final Bundle bundle = new Bundle();
        bundle.putString("prefix", "");
        bundle.putStringArrayList("providers", new ArrayList<>(List.of(SearchProvider.AUTHORITY)));
        final SearchSuggestionsQuery query = new SearchSuggestionsQuery(bundle);

        // Async tasks are run synchronously during tests to make tests deterministic and prevent
        // flaky test results.
        final Executor currentThreadExecutor = Runnable::run;

        try (Cursor cursor = PickerDataLayerV2.querySearchSuggestions(
                mContext, bundle, currentThreadExecutor, null)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(SearchProvider.DEFAULT_SUGGESTION_RESULTS.getCount());

            final String projection = PickerSQLConstants.SearchSuggestionsResponseColumns
                            .MEDIA_SET_ID.getProjection();
            if (cursor.moveToFirst() && SearchProvider.DEFAULT_SUGGESTION_RESULTS.moveToFirst()) {
                do {
                    assertWithMessage("Media ID is not as expected")
                            .that(cursor.getString(cursor.getColumnIndexOrThrow(projection)))
                            .isEqualTo(SearchProvider.DEFAULT_SUGGESTION_RESULTS.getString(
                                    SearchProvider.DEFAULT_SUGGESTION_RESULTS
                                            .getColumnIndexOrThrow(projection)));
                } while (cursor.moveToNext()
                        && SearchProvider.DEFAULT_SUGGESTION_RESULTS.moveToNext());
            }
        }

        final List<SearchSuggestion> searchSuggestions = SearchSuggestionsDatabaseUtils
                .getCachedSuggestions(mFacade.getDatabase(), query);

        assertWithMessage("Suggestions should not be null")
                .that(searchSuggestions)
                .isNotNull();

        assertWithMessage("Suggestions size is not as expected")
                .that(searchSuggestions.size())
                .isEqualTo(SearchProvider.DEFAULT_SUGGESTION_RESULTS.getCount());
    }

    @Test
    public void testQuerySearchSuggestionsNonZeroState() {
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController)
                .getCloudProviderOrDefault(any());
        doReturn(mSearchState).when(mMockSyncController).getSearchState();
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());
        doReturn(true).when(mSearchState).isCloudSearchEnabled(any());

        final Bundle bundle = new Bundle();
        bundle.putString("prefix", "x");
        bundle.putStringArrayList("providers", new ArrayList<>(List.of(SearchProvider.AUTHORITY)));
        final SearchSuggestionsQuery query = new SearchSuggestionsQuery(bundle);

        // Async tasks are run synchronously during tests to make tests deterministic and prevent
        // flaky test results.
        final Executor currentThreadExecutor = Runnable::run;

        try (Cursor cursor = PickerDataLayerV2.querySearchSuggestions(
                mContext, bundle, currentThreadExecutor, null)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(SearchProvider.DEFAULT_SUGGESTION_RESULTS.getCount());

            final String projection = PickerSQLConstants.SearchSuggestionsResponseColumns
                    .MEDIA_SET_ID.getProjection();
            if (cursor.moveToFirst() && SearchProvider.DEFAULT_SUGGESTION_RESULTS.moveToFirst()) {
                do {
                    assertWithMessage("Media ID is not as expected")
                            .that(cursor.getString(cursor.getColumnIndexOrThrow(projection)))
                            .isEqualTo(SearchProvider.DEFAULT_SUGGESTION_RESULTS.getString(
                                    SearchProvider.DEFAULT_SUGGESTION_RESULTS
                                            .getColumnIndexOrThrow(projection)));
                } while (cursor.moveToNext()
                        && SearchProvider.DEFAULT_SUGGESTION_RESULTS.moveToNext());
            }
        }

        final List<SearchSuggestion> searchSuggestions = SearchSuggestionsDatabaseUtils
                .getCachedSuggestions(mFacade.getDatabase(), query);

        assertWithMessage("Suggestions should not be null")
                .that(searchSuggestions)
                .isNotNull();

        assertWithMessage("Suggestions size is not as expected")
                .that(searchSuggestions.size())
                .isEqualTo(0);
    }

    @Test
    public void testQuerySearchSuggestionsWithHistory() {
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController)
                .getCloudProviderOrDefault(any());
        doReturn(mSearchState).when(mMockSyncController).getSearchState();
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());
        doReturn(true).when(mSearchState).isCloudSearchEnabled(any());

        final Bundle bundle = new Bundle();
        bundle.putString("prefix", "");
        bundle.putStringArrayList("providers", new ArrayList<>(List.of(SearchProvider.AUTHORITY)));
        final SearchSuggestionsQuery query = new SearchSuggestionsQuery(bundle);

        // Async tasks are run synchronously during tests to make tests deterministic and prevent
        // flaky test results.
        final Executor currentThreadExecutor = Runnable::run;

        SearchSuggestionsDatabaseUtils.saveSearchHistory(
                mFacade.getDatabase(),
                new SearchTextRequest(null, "mountains"));

        try (Cursor cursor = PickerDataLayerV2.querySearchSuggestions(
                mContext, bundle, currentThreadExecutor, null)) {
            assertWithMessage("Cursor should not be null")
                    .that(cursor)
                    .isNotNull();

            assertWithMessage("Cursor count is not as expected")
                    .that(cursor.getCount())
                    .isEqualTo(SearchProvider.DEFAULT_SUGGESTION_RESULTS.getCount() + 1);

            final String projection = PickerSQLConstants.SearchSuggestionsResponseColumns
                    .MEDIA_SET_ID.getProjection();

            cursor.moveToFirst();
            assertWithMessage("Media ID is not as expected")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(projection)))
                    .isNull();

            if (cursor.moveToNext() && SearchProvider.DEFAULT_SUGGESTION_RESULTS.moveToFirst()) {
                do {
                    assertWithMessage("Media ID is not as expected")
                            .that(cursor.getString(cursor.getColumnIndexOrThrow(projection)))
                            .isEqualTo(SearchProvider.DEFAULT_SUGGESTION_RESULTS.getString(
                                    SearchProvider.DEFAULT_SUGGESTION_RESULTS
                                            .getColumnIndexOrThrow(projection)));
                } while (cursor.moveToNext()
                        && SearchProvider.DEFAULT_SUGGESTION_RESULTS.moveToNext());
            }
        }
    }

    @Test
    public void testHandleNewSearchRequest() {
        doReturn(true).when(mMockSyncController).shouldQueryLocalMediaForSearch(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMediaForSearch(any(), any());
        doReturn(mMockOperation).when(mMockWorkManager)
                .enqueueUniqueWork(anyString(), any(ExistingWorkPolicy.class),
                        any(OneTimeWorkRequest.class));
        doReturn(mMockFuture).when(mMockOperation).getResult();

        final String searchText = "volcano";
        final Bundle extras = getCreateSearchRequestExtras(new SearchTextRequest(null, searchText));
        final Executor currentThreadExecutor = Runnable::run;

        final Bundle result = PickerDataLayerV2.handleNewSearchRequest(
                mMockContext, extras, currentThreadExecutor, mMockWorkManager);

        // Assert that a new search request was created
        assertThat(result).isNotNull();
        assertThat(result.getInt("search_request_id")).isEqualTo(1);

        // Assert that both local and cloud syncs were scheduled
        verify(mMockWorkManager, times(2))
                .enqueueUniqueWork(anyString(), any(ExistingWorkPolicy.class),
                        any(OneTimeWorkRequest.class));

        // Assert that search request was saved as search history in database
        final List<SearchSuggestion> suggestions =
                SearchSuggestionsDatabaseUtils.getHistorySuggestions(
                        mFacade.getDatabase(),
                        new SearchSuggestionsQuery("", new ArrayList<>()));
        assertThat(suggestions.size()).isEqualTo(1);
        assertThat(suggestions.get(0).getSearchText()).isEqualTo(searchText);
    }

    @Test
    public void testTriggerMediaSetsSyncRequest() {
        doReturn(true).when(mMockSyncController).shouldQueryLocalMediaSets(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMediaSets(any(), any());
        doReturn(mMockOperation).when(mMockWorkManager)
                .enqueueUniqueWork(anyString(), any(ExistingWorkPolicy.class),
                        any(OneTimeWorkRequest.class));
        doReturn(mMockFuture).when(mMockOperation).getResult();

        Bundle extras = new Bundle();
        extras.putString("authority", SearchProvider.AUTHORITY);
        extras.putStringArray("mime_types", new String[] { "image/*" });
        extras.putString("category_id", "id");
        extras.putStringArrayList("providers", new ArrayList<>(List.of(SearchProvider.AUTHORITY)));

        PickerDataLayerV2.triggerMediaSetsSync(extras, mContext, mMockWorkManager);

        // Assert that both local and cloud syncs were scheduled
        verify(mMockWorkManager, times(1))
                .enqueueUniqueWork(anyString(), any(ExistingWorkPolicy.class),
                        any(OneTimeWorkRequest.class));
    }

    @Test
    public void testQueryCategoriesAndAlbums() {
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController)
                .getCloudProviderOrDefault(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());
        doReturn(true).when(mCategoriesState).areCategoriesEnabled(any(), any());

        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);

        try (Cursor cursor = PickerDataLayerV2.queryCategoriesAndAlbums(
                mContext,
                getMediaQueryExtras(Long.MAX_VALUE, Long.MAX_VALUE, 100,
                        new ArrayList<>(Arrays.asList(LOCAL_PROVIDER, SearchProvider.AUTHORITY))),
                /* cancellationSignal */ null)) {
            assertWithMessage("Count of albums and categories")
                    .that(cursor.getCount())
                    .isEqualTo(5);

            cursor.moveToFirst();
            assertWithMessage("Unexpected media group")
                    .that(MediaGroup.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow(
                                    PickerSQLConstants.MediaGroupResponseColumns
                                            .MEDIA_GROUP.getColumnName()))))
                    .isEqualTo(MediaGroup.ALBUM);
            assertWithMessage("Unexpected album id")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaGroupResponseColumns.GROUP_ID.getColumnName())))
                    .isEqualTo(CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES);

            cursor.moveToNext();
            assertWithMessage("Unexpected media group")
                    .that(MediaGroup.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow(
                                    PickerSQLConstants.MediaGroupResponseColumns
                                            .MEDIA_GROUP.getColumnName()))))
                    .isEqualTo(MediaGroup.ALBUM);

            assertWithMessage("Unexpected album id")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaGroupResponseColumns.GROUP_ID.getColumnName())))
                    .isEqualTo(CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA);

            cursor.moveToNext();
            // Assert that the next media groupd is people and pets category
            assertWithMessage("Unexpected media group")
                    .that(MediaGroup.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow(
                                    PickerSQLConstants.MediaGroupResponseColumns
                                            .MEDIA_GROUP.getColumnName()))))
                    .isEqualTo(MediaGroup.CATEGORY);

            cursor.moveToNext();
            assertWithMessage("Unexpected media group")
                    .that(MediaGroup.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow(
                                    PickerSQLConstants.MediaGroupResponseColumns
                                            .MEDIA_GROUP.getColumnName()))))
                    .isEqualTo(MediaGroup.ALBUM);

            assertWithMessage("Unexpected album id")
                    .that(cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaGroupResponseColumns.GROUP_ID.getColumnName())))
                    .isEqualTo(CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS);

            cursor.moveToNext();
            // Assert that the next media groupd is a cloud album
            assertWithMessage("Unexpected media group")
                    .that(MediaGroup.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow(
                                    PickerSQLConstants.MediaGroupResponseColumns
                                            .MEDIA_GROUP.getColumnName()))))
                    .isEqualTo(MediaGroup.ALBUM);

            final Uri coverUri = Uri.parse(
                    cursor.getString(cursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaGroupResponseColumns
                                    .UNWRAPPED_COVER_URI.getColumnName())));
            assertWithMessage("Unexpected media group")
                    .that(coverUri.getLastPathSegment())
                    .isEqualTo(LOCAL_ID_1);
        }
    }

    private static Bundle getCreateSearchRequestExtras(SearchTextRequest searchTextRequest) {
        final Bundle bundle = new Bundle();
        bundle.putString("search_text", searchTextRequest.getSearchText());
        bundle.putStringArrayList("providers", new ArrayList<>(List.of(SearchProvider.AUTHORITY)));
        return bundle;
    }

    private static void assertMediaCursor(Cursor cursor, String id, String authority,
            Long dateTaken, String mimeType) {
        assertMediaCursor(cursor, id, authority, dateTaken, mimeType,
                MediaStore.ACTION_PICK_IMAGES, /* isPreGranted */ false);
    }
    private static void assertMediaCursor(Cursor cursor, String id, String authority,
            Long dateTaken, String mimeType, String intent) {
        assertMediaCursor(cursor, id, authority, dateTaken, mimeType,
                intent, /* isPreGranted */ false);
    }

    private static void assertMediaCursor(Cursor cursor, String id, String authority,
            Long dateTaken, String mimeType, String intent, boolean isPreGranted) {
        assertWithMessage("Unexpected value of id in the media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())))
                .isEqualTo(id);

        assertWithMessage("Unexpected value of authority in the media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.AUTHORITY.getProjectedName())))
                .isEqualTo(authority);

        assertWithMessage("Unexpected value of date taken in the media cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName())))
                .isEqualTo(dateTaken);

        assertWithMessage("Unexpected value of mime type in the media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.MIME_TYPE.getProjectedName())))
                .isEqualTo(mimeType);

        final Uri expectedUri = getMediaUri(id, authority, intent);

        assertWithMessage("Unexpected value of uri in the media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.WRAPPED_URI.getProjectedName())))
                .isEqualTo(expectedUri.toString());

        assertWithMessage("Unexpected value of grants in the media cursor.")
                .that(cursor.getInt(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaResponse.IS_PRE_GRANTED.getProjectedName())))
                .isEqualTo(isPreGranted ? 1 : 0);
    }

    private static void assertAlbumCursor(Cursor cursor, String albumId, String authority,
            Long dateTaken, String coverMediaId) {
        final MediaSource mediaSource = LOCAL_PROVIDER.equals(authority)
                ? MediaSource.LOCAL
                : MediaSource.REMOTE;
        assertAlbumCursor(cursor, albumId, authority, dateTaken, coverMediaId, mediaSource);
    }

    private static void assertAlbumCursor(Cursor cursor, String albumId, String authority,
            Long dateTaken, String coverMediaId, MediaSource mediaSource) {
        assertWithMessage("Unexpected value of id in the media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.ALBUM_ID.getColumnName())))
                .isEqualTo(albumId);

        assertWithMessage("Unexpected value of authority in the media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.AUTHORITY.getColumnName())))
                .isEqualTo(authority);

        assertWithMessage("Unexpected value of date taken in the media cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.DATE_TAKEN.getColumnName())))
                .isEqualTo(dateTaken);

        final Uri coverUri = Uri.parse(
                cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.UNWRAPPED_COVER_URI.getColumnName()))
        );

        if (EMPTY_MEDIA_ID.equals(coverMediaId)) {
            assertWithMessage("Unexpected value of cover uri.")
                    .that(coverUri)
                    .isEqualTo(Uri.EMPTY);
        } else {
            assertWithMessage("Unexpected value of cover media id in the media cursor.")
                    .that(coverUri.getLastPathSegment())
                    .isEqualTo(coverMediaId);
        }

        assertWithMessage("Unexpected value of media source in the media cursor.")
                .that(MediaSource.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.COVER_MEDIA_SOURCE.getColumnName()))))
                .isEqualTo(mediaSource);
    }

    private static Uri getMediaUri(String id, String authority, String intent) {
        return PickerUriResolver.wrapProviderUri(
                ItemsProvider.getItemsUri(id, authority, UserId.CURRENT_USER),
                intent,
                MediaStore.MY_USER_ID
        );
    }

    private Bundle getMediaQueryExtras(Long pickerId, Long dateTakenMillis, int pageSize,
            List<String> providers) {
        Bundle extras = new Bundle();
        extras.putLong("picker_id", pickerId);
        extras.putLong("date_taken_millis", dateTakenMillis);
        extras.putInt("page_size", pageSize);
        extras.putStringArrayList("providers", new ArrayList<>(providers));
        extras.putString("intent_action", MediaStore.ACTION_PICK_IMAGES);
        return extras;
    }

    private Bundle getMediaQueryExtras(Long pickerId, Long dateTakenMillis, int pageSize,
            List<String> providers, List<String> mimeTypes) {
        Bundle extras = getMediaQueryExtras(
                pickerId,
                dateTakenMillis,
                pageSize,
                providers
        );
        extras.putStringArrayList("mime_types", new ArrayList<>(mimeTypes));
        return extras;
    }

    private Bundle getMediaQueryExtras(
            Long pickerId, Long dateTakenMillis, int pageSize,
            List<String> providers, List<String> mimeTypes,
            String intentAction, int callingUid) {
        Bundle extras = getMediaQueryExtras(
                pickerId,
                dateTakenMillis,
                pageSize,
                providers,
                mimeTypes
        );
        extras.putInt(Intent.EXTRA_UID, callingUid);
        extras.putString("intent_action", intentAction);
        return extras;
    }

    private Bundle getAlbumMediaQueryExtras(Long pickerId, Long dateTakenMillis, int pageSize,
            List<String> providers, String albumAuthority) {
        Bundle extras = getMediaQueryExtras(
                pickerId,
                dateTakenMillis,
                pageSize,
                providers
        );
        extras.putString("album_authority", albumAuthority);
        return extras;
    }
}
