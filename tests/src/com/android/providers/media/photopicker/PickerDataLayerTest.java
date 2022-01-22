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

import static com.android.providers.media.PickerProviderMediaGenerator.ALBUM_COLUMN_TYPE_CLOUD;
import static com.android.providers.media.PickerProviderMediaGenerator.ALBUM_COLUMN_TYPE_FAVORITES;
import static com.android.providers.media.PickerProviderMediaGenerator.ALBUM_COLUMN_TYPE_LOCAL;
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
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.data.model.Category;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PickerDataLayerTest {
    private static final String TAG = "PickerDataLayerTest";

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
    private static final Pair<String, String> CLOUD_AND_LOCAL_1
            = Pair.create(LOCAL_ID_1, CLOUD_ID_1);

    private static final String VERSION_1 = "1";
    private static final String VERSION_2 = "2";

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

        mLocalMediaGenerator.setVersion(VERSION_1);
        mCloudPrimaryMediaGenerator.setVersion(VERSION_1);
        mCloudSecondaryMediaGenerator.setVersion(VERSION_1);

        mContext = InstrumentationRegistry.getTargetContext();

        // Delete db so it's recreated on next access and previous test state is cleared
        final File dbPath = mContext.getDatabasePath(DB_NAME);
        dbPath.delete();

        mDbHelper = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        mFacade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY, mDbHelper);
        mDataLayer = new PickerDataLayer(mContext, mFacade);
        mController = new PickerSyncController(mContext, mFacade, LOCAL_PROVIDER_AUTHORITY,
                /* syncDelay */ 0);

        // Set cloud provider to null to discard
        mFacade.setCloudProvider(null);

        Assume.assumeTrue(PickerDbFacade.isPickerDbEnabled());
    }

    @Test
    public void testFetchMediaNoFilter() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchMedia(buildDefaultQueryArgs())) {
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

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchMedia(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        final Bundle favoriteQueryArgs = buildQueryArgs(Category.CATEGORY_FAVORITES,
                ALBUM_COLUMN_TYPE_FAVORITES, MIME_TYPE_DEFAULT, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchMedia(favoriteQueryArgs)) {
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

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchMedia(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        final Bundle favoriteMimeTypeQueryArgs = buildQueryArgs(Category.CATEGORY_FAVORITES,
                ALBUM_COLUMN_TYPE_FAVORITES, VIDEO_MIME_TYPE, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchMedia(favoriteMimeTypeQueryArgs)) {
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

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchMedia(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        final Bundle favoriteSizeQueryArgs = buildQueryArgs(Category.CATEGORY_FAVORITES,
                ALBUM_COLUMN_TYPE_FAVORITES, MIME_TYPE_DEFAULT, SIZE_BYTES - 1);

        try (Cursor cr = mDataLayer.fetchMedia(favoriteSizeQueryArgs)) {
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

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchMedia(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);
        }

        final Bundle favoriteSizeAndMimeTypeQueryArgs = buildQueryArgs(Category.CATEGORY_FAVORITES,
                ALBUM_COLUMN_TYPE_FAVORITES, VIDEO_MIME_TYPE, SIZE_BYTES - 1);

        try (Cursor cr = mDataLayer.fetchMedia(favoriteSizeAndMimeTypeQueryArgs)) {
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

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchMedia(queryArgs)) {
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

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchMedia(queryArgs)) {
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

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchMedia(queryArgs)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
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

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchAlbums(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(3);

            assertAlbumCursor(cr, ALBUM_ID_1, ALBUM_COLUMN_TYPE_LOCAL);
            assertAlbumCursor(cr, Category.CATEGORY_FAVORITES, ALBUM_COLUMN_TYPE_FAVORITES);
            assertAlbumCursor(cr, ALBUM_ID_2, ALBUM_COLUMN_TYPE_CLOUD);
        }

        try (Cursor cr = mDataLayer.fetchMedia(defaultQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(4);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        final Bundle localAlbumQueryArgs = buildQueryArgs(ALBUM_ID_1,
                ALBUM_COLUMN_TYPE_LOCAL, MIME_TYPE_DEFAULT, SIZE_BYTES_DEFAULT);

        final Bundle cloudAlbumQueryArgs = buildQueryArgs(ALBUM_ID_2,
                ALBUM_COLUMN_TYPE_CLOUD, MIME_TYPE_DEFAULT, SIZE_BYTES_DEFAULT);

        final Bundle favoriteAlbumQueryArgs = buildQueryArgs(Category.CATEGORY_FAVORITES,
                ALBUM_COLUMN_TYPE_FAVORITES, MIME_TYPE_DEFAULT, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchMedia(localAlbumQueryArgs)) {
            assertWithMessage("Local album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        try (Cursor cr = mDataLayer.fetchMedia(cloudAlbumQueryArgs)) {
            assertWithMessage("Cloud album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        try (Cursor cr = mDataLayer.fetchMedia(favoriteAlbumQueryArgs)) {
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

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchAlbums(mimeTypeQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertAlbumCursor(cr, ALBUM_ID_1, ALBUM_COLUMN_TYPE_LOCAL);
            assertAlbumCursor(cr, ALBUM_ID_2, ALBUM_COLUMN_TYPE_CLOUD);
        }

        final Bundle localAlbumAndMimeTypeQueryArgs = buildQueryArgs(ALBUM_ID_1,
                ALBUM_COLUMN_TYPE_LOCAL, IMAGE_MIME_TYPE, SIZE_BYTES_DEFAULT);

        final Bundle cloudAlbumAndMimeTypeQueryArgs = buildQueryArgs(ALBUM_ID_2,
                ALBUM_COLUMN_TYPE_CLOUD, IMAGE_MIME_TYPE, SIZE_BYTES_DEFAULT);

        try (Cursor cr = mDataLayer.fetchMedia(localAlbumAndMimeTypeQueryArgs)) {
            assertWithMessage("Local album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        try (Cursor cr = mDataLayer.fetchMedia(cloudAlbumAndMimeTypeQueryArgs)) {
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

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchAlbums(sizeQueryArgs)) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertAlbumCursor(cr, ALBUM_ID_1, ALBUM_COLUMN_TYPE_LOCAL);
            assertAlbumCursor(cr, ALBUM_ID_2, ALBUM_COLUMN_TYPE_CLOUD);
        }

        final Bundle localAlbumAndSizeQueryArgs = buildQueryArgs(ALBUM_ID_1,
                ALBUM_COLUMN_TYPE_LOCAL, MIME_TYPE_DEFAULT, SIZE_BYTES -1);

        final Bundle cloudAlbumAndSizeQueryArgs = buildQueryArgs(ALBUM_ID_2,
                ALBUM_COLUMN_TYPE_CLOUD, MIME_TYPE_DEFAULT, SIZE_BYTES -1);

        try (Cursor cr = mDataLayer.fetchMedia(localAlbumAndSizeQueryArgs)) {
            assertWithMessage("Local album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
        }

        try (Cursor cr = mDataLayer.fetchMedia(cloudAlbumAndSizeQueryArgs)) {
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
                ALBUM_COLUMN_TYPE_CLOUD, VIDEO_MIME_TYPE, SIZE_BYTES - 1);

        mController.syncPicker();
        try (Cursor cr = mDataLayer.fetchAlbums(mimeTypeAndSizeQueryArgs)) {
            assertWithMessage("Local album count").that(cr.getCount()).isEqualTo(2);

            assertAlbumCursor(cr, ALBUM_ID_1, ALBUM_COLUMN_TYPE_LOCAL);
            assertAlbumCursor(cr, ALBUM_ID_2, ALBUM_COLUMN_TYPE_CLOUD);
        }

        try (Cursor cr = mDataLayer.fetchMedia(cloudAlbumAndMimeTypeQueryArgs)) {
            assertWithMessage("Cloud album count").that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
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

        queryArgs.putString(MediaStore.QUERY_ARG_MIME_TYPE, mimeType);
        queryArgs.putLong(MediaStore.QUERY_ARG_SIZE_BYTES, sizeBytes);

        return queryArgs;
    }

    private static Bundle buildQueryArgs(String albumId, String albumType, String mimeType,
            long sizeBytes) {
        final Bundle queryArgs = buildQueryArgs(mimeType, sizeBytes);

        queryArgs.putString(MediaStore.QUERY_ARG_ALBUM_ID, albumId);
        queryArgs.putString(MediaStore.QUERY_ARG_ALBUM_TYPE, albumType);

        if (Objects.equals(albumType, ALBUM_COLUMN_TYPE_CLOUD)) {
            queryArgs.putString(MediaStore.EXTRA_CLOUD_PROVIDER,
                    CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

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

    private static void assertAlbumCursor(Cursor cursor, String id, String type) {
        cursor.moveToNext();
        assertThat(cursor.getString(cursor.getColumnIndex(AlbumColumns.ID)))
                .isEqualTo(id);
        assertThat(cursor.getString(cursor.getColumnIndex(AlbumColumns.TYPE)))
                .isEqualTo(type);
    }

    private static void assertCursor(Cursor cursor, String id, String expectedAuthority) {
        cursor.moveToNext();
        assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.ID)))
                .isEqualTo(id);

        final int authorityIdx = cursor.getColumnIndex(MediaColumns.AUTHORITY);
        final String authority;
        if (authorityIdx >= 0) {
            // Cursor from picker db has authority as a column
            authority = cursor.getString(authorityIdx);
        } else {
            // Cursor from provider directly doesn't have an authority column but will
            // have the authority set as an extra
            final Bundle bundle = cursor.getExtras();
            authority = bundle.getString(MediaColumns.AUTHORITY);
        }
        assertThat(authority).isEqualTo(expectedAuthority);
    }
}
