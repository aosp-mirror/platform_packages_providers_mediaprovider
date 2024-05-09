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
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_3;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_4;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_PROVIDER;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.MP4_VIDEO_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.STANDARD_MIME_TYPE_EXTENSION;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAddMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAddAlbumMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getAlbumCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getAlbumMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getCloudMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getLocalMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.GIF_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.PNG_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.JPEG_IMAGE_MIME_TYPE;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Process;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.test.InstrumentationRegistry;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.v2.model.MediaSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

public class PickerDataLayerV2Test {
    @Mock
    private PickerSyncController mMockSyncController;
    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mMockPackageManager;
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
        }
    }

    @Test
    public void testAvailableProvidersWithCloudProvider() throws
            PackageManager.NameNotFoundException {
        final int cloudUID = Integer.MAX_VALUE;
        final ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.packageName = CLOUD_PROVIDER;

        doReturn(mMockPackageManager)
                .when(mMockContext).getPackageManager();
        doReturn(cloudUID)
                .when(mMockPackageManager)
                .getPackageUid(CLOUD_PROVIDER, 0);
        doReturn(providerInfo)
                .when(mMockPackageManager)
                .resolveContentProvider(CLOUD_PROVIDER, 0);

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
    public void testMergedAlbumsWithCloudQueriesDisabled() {
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
            // Verify that merged albums are not displayed by default when cloud albums are
            // disabled.
            assertWithMessage(
                    "Unexpected number of rows in media query result")
                    .that(cr).isNull();
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
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
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
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            // Favorites albums will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ Integer.toString(Integer.MAX_VALUE));

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    CLOUD_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ CLOUD_ID_2);
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
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            // Favorites albums will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ Integer.toString(Integer.MAX_VALUE));

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
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            // Favorites albums will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_2);
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
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    CLOUD_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ CLOUD_ID_1);

            cr.moveToNext();
            // Videos album will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ Integer.toString(Integer.MAX_VALUE));
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
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_1);

            cr.moveToNext();
            // Videos album will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ Integer.toString(Integer.MAX_VALUE));
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
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_1);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_2);
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
                    .that(cr.getCount()).isEqualTo(3);

            cr.moveToFirst();
            // Favorites albums will be displayed by default
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE,
                    /* coverMediaId */ Integer.toString(Integer.MAX_VALUE));

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

        Cursor cursor2 = getAlbumCursor(CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                DATE_TAKEN_MS, LOCAL_ID_2, LOCAL_PROVIDER);
        mLocalProvider.setQueryResult(cursor2);

        Cursor cursor3 = getAlbumCursor("CloudAlbum", DATE_TAKEN_MS, CLOUD_ID_1, CLOUD_PROVIDER);
        mCloudProvider.setQueryResult(cursor3);

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
                    /* coverMediaId */ Integer.toString(Integer.MAX_VALUE));

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                    LOCAL_PROVIDER, /* dateTaken */ Long.MAX_VALUE, /* coverMediaId */ LOCAL_ID_1);

            cr.moveToNext();
            assertAlbumCursor(cr,
                    /* albumId */ CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
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
        }
    }

    private static void assertMediaCursor(Cursor cursor, String id, String authority,
            Long dateTaken, String mimeType) {
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

        //TODO(b/329122491): Uncomment URI tests when cloud ids with special characters are handled.
//        final Uri expectedUri = getMediaUri(id, authority);

//        assertWithMessage("Unexpected value of uri in the media cursor.")
//                .that(cursor.getString(cursor.getColumnIndexOrThrow(
//                        PickerSQLConstants.MediaResponse.URI.getProjectedName())))
//                .isEqualTo(expectedUri.toString());
    }

    private static void assertAlbumCursor(Cursor cursor, String albumId, String authority,
            Long dateTaken, String coverMediaId) {
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

        assertWithMessage("Unexpected value of cover media id in the media cursor.")
                .that(coverUri.getLastPathSegment())
                .isEqualTo(coverMediaId);

        final MediaSource mediaSource = LOCAL_PROVIDER.equals(authority)
                ? MediaSource.LOCAL
                : MediaSource.REMOTE;
        assertWithMessage("Unexpected value of media source in the media cursor.")
                .that(MediaSource.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.COVER_MEDIA_SOURCE.getColumnName()))))
                .isEqualTo(mediaSource);
    }

    private static Uri getMediaUri(String id, String authority) {
        return PickerUriResolver.wrapProviderUri(
                ItemsProvider.getItemsUri(id, authority, UserId.CURRENT_USER),
                Intent.ACTION_PICK,
                MediaStore.MY_USER_ID
        );
    }

    private Bundle getMediaQueryExtras(Long pickerId, Long dateTakenMillis, int pageSize,
            ArrayList<String> providers) {
        Bundle extras = new Bundle();
        extras.putLong("picker_id", pickerId);
        extras.putLong("date_taken_millis", dateTakenMillis);
        extras.putInt("page_size", pageSize);
        extras.putStringArrayList("providers", providers);
        return extras;
    }

    private Bundle getMediaQueryExtras(Long pickerId, Long dateTakenMillis, int pageSize,
            ArrayList<String> providers, ArrayList<String> mimeTypes) {
        Bundle extras = getMediaQueryExtras(
                pickerId,
                dateTakenMillis,
                pageSize,
                providers
        );
        extras.putStringArrayList("mime_types", mimeTypes);
        return extras;
    }

    private Bundle getAlbumMediaQueryExtras(Long pickerId, Long dateTakenMillis, int pageSize,
            ArrayList<String> providers, String albumAuthority) {
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
