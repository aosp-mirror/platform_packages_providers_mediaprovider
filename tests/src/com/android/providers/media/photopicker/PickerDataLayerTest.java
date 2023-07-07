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

package com.android.providers.media.photopicker;

import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS;

import static com.android.providers.media.PickerProviderMediaGenerator.MediaGenerator;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LONG_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_DEFAULT;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract.AlbumColumns;
import android.provider.CloudMediaProviderContract.MediaColumns;
import android.provider.MediaStore;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.PickerProviderMediaGenerator;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class PickerDataLayerTest {
    private static final String TAG = "PickerDataLayerTest";

    private static final String PACKAGE_NAME = "com.android.providers.media.tests";
    private static final String LOCAL_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.local";
    private static final String CLOUD_PRIMARY_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.cloud_primary";
    private static final String CLOUD_SECONDARY_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.cloud_secondary";

    private final MediaGenerator mLocalMediaGenerator =
            PickerProviderMediaGenerator.getMediaGenerator(LOCAL_PROVIDER_AUTHORITY);
    private final MediaGenerator mCloudPrimaryMediaGenerator =
            PickerProviderMediaGenerator.getMediaGenerator(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
    private final MediaGenerator mCloudSecondaryMediaGenerator =
            PickerProviderMediaGenerator.getMediaGenerator(CLOUD_SECONDARY_PROVIDER_AUTHORITY);

    private static final int DB_VERSION_1 = 1;
    private static final String DB_NAME = "test_db";

    private static final String LOCAL_ID_1 = "1";
    private static final String LOCAL_ID_2 = "2";

    private static final String CLOUD_ID_1 = "1";
    private static final String CLOUD_ID_2 = "2";

    private static final String ALBUM_ID_1 = "1";
    private static final String ALBUM_ID_2 = "2";

    private static final String MIME_TYPE_DEFAULT = STRING_DEFAULT;
    private static final long SIZE_BYTES_DEFAULT = LONG_DEFAULT;

    private static final Pair<String, String> LOCAL_ONLY_1 = Pair.create(LOCAL_ID_1, null);
    private static final Pair<String, String> LOCAL_ONLY_2 = Pair.create(LOCAL_ID_2, null);
    private static final Pair<String, String> CLOUD_ONLY_1 = Pair.create(null, CLOUD_ID_1);
    private static final Pair<String, String> CLOUD_ONLY_2 = Pair.create(null, CLOUD_ID_2);

    private static final String COLLECTION_1 = "1";

    private static final String IMAGE_MIME_TYPE = "image/jpeg";
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final long SIZE_BYTES = 50;

    private Context mContext;
    private PickerDatabaseHelper mDbHelper;
    private PickerDbFacade mFacade;
    private PickerDataLayer mDataLayer;
    private PickerSyncController mController;

    @Before
    public void setUp() {
        mLocalMediaGenerator.resetAll();
        mCloudPrimaryMediaGenerator.resetAll();
        mCloudSecondaryMediaGenerator.resetAll();

        mLocalMediaGenerator.setMediaCollectionId(COLLECTION_1);
        mCloudPrimaryMediaGenerator.setMediaCollectionId(COLLECTION_1);
        mCloudSecondaryMediaGenerator.setMediaCollectionId(COLLECTION_1);

        mContext = InstrumentationRegistry.getTargetContext();

        // Delete db so it's recreated on next access and previous test state is cleared
        final File dbPath = mContext.getDatabasePath(DB_NAME);
        dbPath.delete();

        mDbHelper = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        mFacade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY, mDbHelper);

        final TestConfigStore configStore = new TestConfigStore();
        configStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);
        configStore.setPickerSyncDelayMs(0);

        mController = new PickerSyncController(
                mContext, mFacade, configStore, LOCAL_PROVIDER_AUTHORITY);
        mDataLayer = new PickerDataLayer(mContext, mFacade, mController);

        // Set cloud provider to null to discard
        mFacade.setCloudProvider(null);
    }

    @After
    public void tearDown() {
        if (mFacade != null) {
            mFacade.setCloudProvider(null);
        }
    }

    @Test
    public void testFetchMediaNoFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);

        try (Cursor cr = mDataLayer.fetchAllMedia(buildDefaultQueryArgs())) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchMediaFavorites() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, /* albumId */ null, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ true);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ true);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        final Bundle defaultQueryArgs = buildDefaultQueryArgs();

        try (Cursor cr = mDataLayer.fetchAllMedia(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        final Bundle favoriteQueryArgs = buildQueryArgs(ALBUM_ID_FAVORITES,
                LOCAL_PROVIDER_AUTHORITY, MIME_TYPE_DEFAULT, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchAllMedia(favoriteQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchMediaFavoritesMimeTypeFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, /* albumId */ null, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ true);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ true);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        final Bundle defaultQueryArgs = buildDefaultQueryArgs();

        try (Cursor cr = mDataLayer.fetchAllMedia(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        final Bundle favoriteMimeTypeQueryArgs = buildQueryArgs(ALBUM_ID_FAVORITES,
                LOCAL_PROVIDER_AUTHORITY, VIDEO_MIME_TYPE, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchAllMedia(favoriteMimeTypeQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchMediaFavoritesSizeFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, /* albumId */ null, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ true);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ true);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        final Bundle defaultQueryArgs = buildDefaultQueryArgs();

        try (Cursor cr = mDataLayer.fetchAllMedia(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        final Bundle favoriteSizeQueryArgs = buildQueryArgs(ALBUM_ID_FAVORITES,
                LOCAL_PROVIDER_AUTHORITY, MIME_TYPE_DEFAULT, SIZE_BYTES - 1);

        try (Cursor cr = mDataLayer.fetchAllMedia(favoriteSizeQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchMediaFavoritesMimeTypeAndSizeFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, /* albumId */ null, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ true);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ true);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        final Bundle defaultQueryArgs = buildDefaultQueryArgs();

        try (Cursor cr = mDataLayer.fetchAllMedia(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        final Bundle favoriteSizeAndMimeTypeQueryArgs = buildQueryArgs(ALBUM_ID_FAVORITES,
                LOCAL_PROVIDER_AUTHORITY, VIDEO_MIME_TYPE, SIZE_BYTES - 1);

        try (Cursor cr = mDataLayer.fetchAllMedia(favoriteSizeAndMimeTypeQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testFetchMediaMimeTypeFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, /* albumId */ null, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        final Bundle queryArgs = buildQueryArgs(IMAGE_MIME_TYPE, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchAllMedia(queryArgs)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchMediaSizeFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        final Bundle queryArgs = buildQueryArgs(IMAGE_MIME_TYPE, SIZE_BYTES - 1);

        try (Cursor cr = mDataLayer.fetchAllMedia(queryArgs)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchMediaMimeTypeAndSizeFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, /* albumId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, /* albumId */ null, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2, /* albumId */ null, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        final Bundle queryArgs = buildQueryArgs(VIDEO_MIME_TYPE, SIZE_BYTES - 1);

        try (Cursor cr = mDataLayer.fetchAllMedia(queryArgs)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchMediaLocalOnly() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);

        Bundle queryArgs = buildDefaultQueryArgs();
        // Verify that we only see local content
        try (Cursor cr = mDataLayer.fetchLocalMedia(queryArgs)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // Verify that we see cloud content
        try (Cursor cr = mDataLayer.fetchAllMedia(queryArgs)) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchAlbumMedia() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        mLocalMediaGenerator.createAlbum(ALBUM_ID_1);
        mCloudPrimaryMediaGenerator.createAlbum(ALBUM_ID_2);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, ALBUM_ID_1, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, ALBUM_ID_2, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2, /* albumId */ null, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ true);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2, /* albumdId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ true);

        final Bundle defaultQueryArgs = buildDefaultQueryArgs();

        try (Cursor cr = mDataLayer.fetchAllAlbums(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);

            // Most recent media item marked as favorite will be the cover of the Favorites album.
            // In this scenario, Favorites album cover was generated with cloud authority, so the
            // Favorites album authority should be cloud provider authority. Similarly, the most
            // recent video was generated with local authority, so the Videos album authority should
            // be local provider authority.
            assertAlbumCursor(cr, ALBUM_ID_FAVORITES, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertAlbumCursor(cr, ALBUM_ID_VIDEOS, LOCAL_PROVIDER_AUTHORITY);
            assertAlbumCursor(cr, ALBUM_ID_1, LOCAL_PROVIDER_AUTHORITY);
            assertAlbumCursor(cr, ALBUM_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        try (Cursor cr = mDataLayer.fetchAllMedia(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        final Bundle localAlbumQueryArgs = buildQueryArgs(ALBUM_ID_1,
                LOCAL_PROVIDER_AUTHORITY, MIME_TYPE_DEFAULT, SIZE_BYTES_DEFAULT);

        final Bundle cloudAlbumQueryArgs = buildQueryArgs(ALBUM_ID_2,
                CLOUD_PRIMARY_PROVIDER_AUTHORITY, MIME_TYPE_DEFAULT, SIZE_BYTES_DEFAULT);

        final Bundle favoriteAlbumQueryArgs = buildQueryArgs(ALBUM_ID_FAVORITES,
                LOCAL_PROVIDER_AUTHORITY, MIME_TYPE_DEFAULT, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchAllMedia(localAlbumQueryArgs)) {
            assertWithMessage("Local album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        try (Cursor cr = mDataLayer.fetchAllMedia(cloudAlbumQueryArgs)) {
            assertWithMessage("Cloud album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        try (Cursor cr = mDataLayer.fetchAllMedia(favoriteAlbumQueryArgs)) {
            assertWithMessage("Favorite album count").that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchAlbumMediaMimeTypeFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        mLocalMediaGenerator.createAlbum(ALBUM_ID_1);
        mCloudPrimaryMediaGenerator.createAlbum(ALBUM_ID_2);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, ALBUM_ID_1, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, ALBUM_ID_2, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2, ALBUM_ID_1, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2, ALBUM_ID_2, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        final Bundle mimeTypeQueryArgs = buildQueryArgs(IMAGE_MIME_TYPE, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchAllAlbums(mimeTypeQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertAlbumCursor(cr, ALBUM_ID_1, LOCAL_PROVIDER_AUTHORITY);
            assertAlbumCursor(cr, ALBUM_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        final Bundle localAlbumAndMimeTypeQueryArgs = buildQueryArgs(ALBUM_ID_1,
                LOCAL_PROVIDER_AUTHORITY, IMAGE_MIME_TYPE, SIZE_BYTES_DEFAULT);

        final Bundle cloudAlbumAndMimeTypeQueryArgs = buildQueryArgs(ALBUM_ID_2,
                CLOUD_PRIMARY_PROVIDER_AUTHORITY, IMAGE_MIME_TYPE, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchAllMedia(localAlbumAndMimeTypeQueryArgs)) {
            assertWithMessage("Local album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        try (Cursor cr = mDataLayer.fetchAllMedia(cloudAlbumAndMimeTypeQueryArgs)) {
            assertWithMessage("Cloud album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchAlbumMediaSizeFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        mLocalMediaGenerator.createAlbum(ALBUM_ID_1);
        mCloudPrimaryMediaGenerator.createAlbum(ALBUM_ID_2);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, ALBUM_ID_1, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, ALBUM_ID_2, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ false);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2, ALBUM_ID_1, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2, ALBUM_ID_2, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        final Bundle sizeQueryArgs = buildQueryArgs(MIME_TYPE_DEFAULT, SIZE_BYTES - 1);

        try (Cursor cr = mDataLayer.fetchAllAlbums(sizeQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(3);

            assertAlbumCursor(cr, ALBUM_ID_VIDEOS, LOCAL_PROVIDER_AUTHORITY);
            assertAlbumCursor(cr, ALBUM_ID_1, LOCAL_PROVIDER_AUTHORITY);
            assertAlbumCursor(cr, ALBUM_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        final Bundle localAlbumAndSizeQueryArgs = buildQueryArgs(ALBUM_ID_1,
                LOCAL_PROVIDER_AUTHORITY, MIME_TYPE_DEFAULT, SIZE_BYTES -1);

        final Bundle cloudAlbumAndSizeQueryArgs = buildQueryArgs(ALBUM_ID_2,
                CLOUD_PRIMARY_PROVIDER_AUTHORITY, MIME_TYPE_DEFAULT, SIZE_BYTES -1);

        try (Cursor cr = mDataLayer.fetchAllMedia(localAlbumAndSizeQueryArgs)) {
            assertWithMessage("Local album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
        }

        try (Cursor cr = mDataLayer.fetchAllMedia(cloudAlbumAndSizeQueryArgs)) {
            assertWithMessage("Cloud album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchAlbumMediaMimeTypeAndSizeFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        mLocalMediaGenerator.createAlbum(ALBUM_ID_1);
        mCloudPrimaryMediaGenerator.createAlbum(ALBUM_ID_2);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, ALBUM_ID_1, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, ALBUM_ID_2, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ false);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2, ALBUM_ID_1, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES - 1,
                /* isFavorite */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2, ALBUM_ID_2, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        final Bundle mimeTypeAndSizeQueryArgs = buildQueryArgs(VIDEO_MIME_TYPE, SIZE_BYTES -1);

        final Bundle cloudAlbumAndMimeTypeQueryArgs = buildQueryArgs(ALBUM_ID_2,
                CLOUD_PRIMARY_PROVIDER_AUTHORITY, VIDEO_MIME_TYPE, SIZE_BYTES - 1);

        try (Cursor cr = mDataLayer.fetchAllAlbums(mimeTypeAndSizeQueryArgs)) {
            assertWithMessage("Merged and Local album count").that(cr.getCount()).isEqualTo(3);

            // Most recent video will be the cover of the Videos album. In this scenario, Videos
            // album cover was generated with cloud authority, so the Videos album authority should
            // be cloud provider authority.
            assertAlbumCursor(cr, ALBUM_ID_VIDEOS, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertAlbumCursor(cr, ALBUM_ID_1, LOCAL_PROVIDER_AUTHORITY);
            assertAlbumCursor(cr, ALBUM_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        try (Cursor cr = mDataLayer.fetchAllMedia(cloudAlbumAndMimeTypeQueryArgs)) {
            assertWithMessage("Cloud album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchAlbumMediaLocalOnly() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Create a cloud album and add one cloud only item
        mCloudPrimaryMediaGenerator.createAlbum(ALBUM_ID_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1, ALBUM_ID_1, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ false);

        // Add one local only and one cloud only item and mark them favorite
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1, /* albumId */ null, VIDEO_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ true);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2, /* albumdId */ null, IMAGE_MIME_TYPE,
                MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE, SIZE_BYTES, /* isFavorite */ true);

        // Album Info:
        // Album_ID_1 - Cloud Album - 1 Video File
        // Videos     - Merged Album - 2 Video Files (1 local + 1 cloud)
        // Favorites  - Merged Album - 2 files (1 local + 1 cloud)

        final Bundle defaultQueryArgs = buildDefaultQueryArgs();
        // Verify that we see both local and cloud albums
        try (Cursor cr = mDataLayer.fetchAllAlbums(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(3);
        }

        // Verify that we only see local albums with isLocalOnly=true
        try (Cursor cr = mDataLayer.fetchLocalAlbums(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(2);

            cr.moveToNext();
            assertThat(cr.getString(cr.getColumnIndex(AlbumColumns.ID)))
                    .isEqualTo(ALBUM_ID_FAVORITES);
            cr.moveToNext();
            assertThat(cr.getString(cr.getColumnIndex(AlbumColumns.ID)))
                    .isEqualTo(ALBUM_ID_VIDEOS);
        }

        final Bundle favoriteAlbumQueryArgs = buildQueryArgs(ALBUM_ID_FAVORITES,
                LOCAL_PROVIDER_AUTHORITY, MIME_TYPE_DEFAULT, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchLocalMedia(favoriteAlbumQueryArgs)) {
            assertWithMessage("Favorite album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // Verify that we get cloud content in Album Media when queried with localOnly=false
        try (Cursor cr = mDataLayer.fetchAllMedia(favoriteAlbumQueryArgs)) {
            assertWithMessage("Favorite album count").that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFetchCloudAccountInfo() {
        // Cloud provider is not set so cloud account info is null
        assertThat(mDataLayer.fetchCloudAccountInfo()).isNull();

        // Set cloud provider
        mFacade.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Still null since cloud provider doesn't return account info yet
        assertThat(mDataLayer.fetchCloudAccountInfo()).isNull();

        // Fake cloud provider cloud account info
        final String expectedName = "bar";
        final Intent expectedIntent = new Intent("foo");
        mCloudPrimaryMediaGenerator.setAccountInfo(expectedName, expectedIntent);

        // Verify account info
        final PickerDataLayer.AccountInfo info = mDataLayer.fetchCloudAccountInfo();
        assertThat(info).isNotNull();
        assertThat(info.accountName).isEqualTo(expectedName);
        assertThat(info.accountConfigurationIntent).isEqualTo(expectedIntent);
    }

    private static void waitForIdle() {
        final CountDownLatch latch = new CountDownLatch(1);
        BackgroundThread.getExecutor().execute(() -> {
            latch.countDown();
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }

    }

    private static Bundle buildDefaultQueryArgs() {
        return buildQueryArgs(MIME_TYPE_DEFAULT, SIZE_BYTES_DEFAULT);
    }

    private static Bundle buildQueryArgs(String mimeType, long sizeBytes) {
        final Bundle queryArgs = new Bundle();

        if (mimeType != null) {
            queryArgs.putStringArray(MediaStore.QUERY_ARG_MIME_TYPE, new String[]{mimeType});
        }
        queryArgs.putLong(MediaStore.QUERY_ARG_SIZE_BYTES, sizeBytes);

        return queryArgs;
    }

    private static Bundle buildQueryArgs(String albumId, String albumAuthority, String mimeType,
            long sizeBytes) {
        final Bundle queryArgs = buildQueryArgs(mimeType, sizeBytes);

        queryArgs.putString(MediaStore.QUERY_ARG_ALBUM_ID, albumId);
        queryArgs.putString(MediaStore.QUERY_ARG_ALBUM_AUTHORITY, albumAuthority);

        return queryArgs;
    }

    private static void addMedia(MediaGenerator generator, Pair<String, String> media) {
        generator.addMedia(media.first, media.second);
    }

    private static void addMedia(MediaGenerator generator, Pair<String, String> media,
            String albumId, String mimeType, int standardMimeTypeExtension, long sizeBytes,
            boolean isFavorite) {
        generator.addMedia(media.first, media.second, albumId, mimeType,
                standardMimeTypeExtension, sizeBytes, isFavorite);
    }

    private static void deleteMedia(MediaGenerator generator, Pair<String, String> media) {
        generator.deleteMedia(media.first, media.second);
    }

    private Cursor queryMedia() {
        return mFacade.queryMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).build());
    }

    private void assertEmptyCursor() {
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    private static void assertAlbumCursor(Cursor cursor, String id, String expectedAuthority) {
        cursor.moveToNext();
        assertThat(cursor.getString(cursor.getColumnIndex(AlbumColumns.ID)))
                .isEqualTo(id);
        final int authorityIdx = cursor.getColumnIndex(AlbumColumns.AUTHORITY);
        String authority = null;
        if (authorityIdx >= 0) {
            // Cursor from picker db has authority as a column
            authority = cursor.getString(authorityIdx);
        }
        if (authority == null) {
            // Cursor from provider directly doesn't have an authority column but will
            // have the authority set as an extra
            final Bundle bundle = cursor.getExtras();
            authority = bundle.getString(MediaStore.EXTRA_CLOUD_PROVIDER);
        }

        assertThat(authority).isEqualTo(expectedAuthority);
    }

    private static void assertCursor(Cursor cursor, String id, String expectedAuthority) {
        cursor.moveToNext();
        assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.ID)))
                .isEqualTo(id);
        assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.AUTHORITY)))
                .isEqualTo(expectedAuthority);
    }
}
