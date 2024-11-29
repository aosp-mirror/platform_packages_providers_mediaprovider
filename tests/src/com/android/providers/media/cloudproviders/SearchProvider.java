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

package com.android.providers.media.cloudproviders;

import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_3;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_4;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getAlbumCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getCloudMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getLocalMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getMediaCategoriesCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getSuggestionCursor;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.graphics.Point;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;

import java.io.FileNotFoundException;
import java.util.List;

public class SearchProvider extends CloudMediaProvider {
    public static final String AUTHORITY =
            "com.android.providers.media.photopicker.tests.cloud_search_provider";

    public static final MergeCursor DEFAULT_CLOUD_MEDIA = new MergeCursor(List.of(
            getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, 0),
            getCloudMediaCursor(CLOUD_ID_2, LOCAL_ID_2, 0),
            getCloudMediaCursor(CLOUD_ID_3, null, 0),
            getCloudMediaCursor(CLOUD_ID_4, null, 0)
    ).toArray(new Cursor[0]));

    public static final MergeCursor DEFAULT_CLOUD_SEARCH_RESULTS = new MergeCursor(List.of(
            getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, 1),
            getCloudMediaCursor(CLOUD_ID_3, null, 0)
    ).toArray(new Cursor[0]));

    public static final MergeCursor DEFAULT_LOCAL_SEARCH_RESULTS = new MergeCursor(List.of(
            getLocalMediaCursor(LOCAL_ID_1, 1),
            getLocalMediaCursor(LOCAL_ID_2, 0)
    ).toArray(new Cursor[0]));

    private static Cursor sSearchResults = DEFAULT_CLOUD_SEARCH_RESULTS;

    public static final MergeCursor DEFAULT_SUGGESTION_RESULTS = new MergeCursor(List.of(
            getSuggestionCursor(CLOUD_ID_1),
            getSuggestionCursor(CLOUD_ID_2)
    ).toArray(new Cursor[0]));

    private static Cursor sSearchSuggestions = DEFAULT_SUGGESTION_RESULTS;

    public static final MergeCursor DEFAULT_CATEGORY_RESULTS = new MergeCursor(List.of(
            getMediaCategoriesCursor("people_and_pets")
    ).toArray(new Cursor[0]));

    private static Cursor sMediaCategories = DEFAULT_CATEGORY_RESULTS;

    public static final MergeCursor DEFAULT_ALBUM_RESULTS = new MergeCursor(List.of(
            getAlbumCursor("cloud_album", 0L, /* coverId */ CLOUD_ID_1, AUTHORITY)
    ).toArray(new Cursor[0]));

    private static Cursor sAlbums = DEFAULT_ALBUM_RESULTS;

    @Override
    public Cursor onSearchMedia(String mediaSetId, String fallbackSearchText,
                                Bundle extras, CancellationSignal cancellationSignal) {
        return sSearchResults;
    }

    @Override
    public Cursor onSearchMedia(String searchText,
                                Bundle extras, CancellationSignal cancellationSignal) {
        return sSearchResults;
    }

    @Override
    public Cursor onQuerySearchSuggestions(String prefixText, Bundle extras,
                                           CancellationSignal cancellationSignal) {
        return sSearchSuggestions;
    }

    @Override
    public Cursor onQueryMediaSets(String mediaCategoryId,
            Bundle extras, CancellationSignal cancellationSignal) {
        return getCursorForMediaSetSyncTest();
    }

    @Override
    public Cursor onQueryMediaCategories(String parentCategoryId, Bundle extras,
                                         CancellationSignal cancellationSignal) {
        return sMediaCategories;
    }

    @Override
    public Cursor onQueryAlbums(Bundle extras) {
        return sAlbums;
    }

    @Override
    public CloudMediaProviderContract.Capabilities onGetCapabilities() {
        return new CloudMediaProviderContract.Capabilities.Builder()
                .setSearchEnabled(true)
                .setMediaCategoriesEnabled(true)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor onQueryMedia(Bundle extras) {
        throw new UnsupportedOperationException("onQueryMedia not supported");
    }

    @Override
    public Cursor onQueryDeletedMedia(Bundle extras) {
        throw new UnsupportedOperationException("onQueryDeletedMedia not supported");
    }

    @Override
    public AssetFileDescriptor onOpenPreview(
            String mediaId,
            Point size,
            Bundle extras,
            CancellationSignal signal) throws FileNotFoundException {
        throw new UnsupportedOperationException("onOpenPreview not supported");
    }

    @Override
    public ParcelFileDescriptor onOpenMedia(
            String mediaId,
            Bundle extras,
            CancellationSignal signal) throws FileNotFoundException {
        throw new UnsupportedOperationException("onOpenMedia not supported");
    }

    @Override
    public Bundle onGetMediaCollectionInfo(Bundle extras) {
        throw new UnsupportedOperationException("onGetMediaCollectionInfo not supported");
    }

    public static void setSearchResults(Cursor searchResults) {
        sSearchResults = searchResults;
    }

    public static Cursor getSearchResults() {
        return sSearchResults;
    }

    /*
     Returns a media set data cursor for tests
     */
    public static Cursor getCursorForMediaSetSyncTest() {
        String[] columns = new String[]{
                CloudMediaProviderContract.MediaSetColumns.ID,
                CloudMediaProviderContract.MediaSetColumns.DISPLAY_NAME,
                CloudMediaProviderContract.MediaSetColumns.MEDIA_COVER_ID
        };

        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(new Object[] { "mediaSetId", "name", "id" });

        return cursor;
    }
}
