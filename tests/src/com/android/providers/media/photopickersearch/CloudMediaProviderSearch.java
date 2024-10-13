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

package com.android.providers.media.photopickersearch;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;

public class CloudMediaProviderSearch extends CloudMediaProvider {

    public static final String[] MEDIA_PROJECTIONS =  new String[] {
            CloudMediaProviderContract.MediaColumns.ID
    };

    public static final String TEST_MEDIA_ID_FROM_SUGGESTED_SEARCH = "1";
    public static final String TEST_MEDIA_ID_IN_MEDIA_SET = "2";
    public static final String TEST_MEDIA_ID_FROM_TEXT_SEARCH = "3";

    public static final String TEST_MEDIA_SET_ID = "11";
    public static final String TEST_MEDIA_CATEGORY_ID = "111";
    public static final String TEST_SEARCH_SUGGESTION_MEDIA_SET_ID = "2";

    @Override
    public Cursor onSearchMedia(String mediaSetId, String fallbackSearchText,
            Bundle extras, CancellationSignal cancellationSignal) {
        MatrixCursor mockCursor = new MatrixCursor(MEDIA_PROJECTIONS);
        mockCursor.addRow(new Object[]{TEST_MEDIA_ID_FROM_SUGGESTED_SEARCH});
        return mockCursor;
    }

    @Override
    public Cursor onSearchMedia(String searchText,
            Bundle extras, CancellationSignal cancellationSignal) {
        MatrixCursor mockCursor = new MatrixCursor(MEDIA_PROJECTIONS);
        mockCursor.addRow(new Object[]{TEST_MEDIA_ID_FROM_TEXT_SEARCH});
        return mockCursor;
    }

    @Override
    public Cursor onQueryMediaInMediaSet(String mediaSetId, Bundle extras,
            CancellationSignal cancellationSignal) {
        MatrixCursor mockCursor = new MatrixCursor(MEDIA_PROJECTIONS);
        mockCursor.addRow(new Object[]{TEST_MEDIA_ID_IN_MEDIA_SET});
        return mockCursor;
    }

    @Override
    public Cursor onQueryMediaSets(String mediaCategoryId, Bundle extras,
            CancellationSignal cancellationSignal) {
        MatrixCursor mockCursor = new MatrixCursor(
                CloudMediaProviderContract.MediaSetColumns.ALL_PROJECTION);
        mockCursor.addRow(new Object[]{TEST_MEDIA_SET_ID, "Media Set 1", 1, 25});
        return mockCursor;
    }

    @Override
    public Cursor onQuerySearchSuggestions(String prefixText, Bundle extras,
            CancellationSignal cancellationSignal) {
        MatrixCursor mockCursor = new MatrixCursor(
                CloudMediaProviderContract.SearchSuggestionColumns.ALL_PROJECTION);
        mockCursor.addRow(new Object[]{TEST_SEARCH_SUGGESTION_MEDIA_SET_ID,
                1, "song", "Suggestion 1 for " + prefixText});
        return mockCursor;
    }

    @Override
    public Cursor onQueryMediaCategories(String parentCategoryId,
            Bundle extras, CancellationSignal cancellationSignal) {
        MatrixCursor mockCursor =
                new MatrixCursor(CloudMediaProviderContract.MediaCategoryColumns.ALL_PROJECTION);
        mockCursor.addRow(new Object[]{TEST_MEDIA_CATEGORY_ID, 1, null, 1, 2, 3, 4});
        return mockCursor;
    }

    @Override
    public Bundle onGetMediaCollectionInfo(@NonNull Bundle extras) {
        return new Bundle();
    }

    @Override
    public Cursor onQueryMedia(@NonNull Bundle extras) {
        return new MatrixCursor(new String[0]);
    }

    @Override
    public Cursor onQueryDeletedMedia(@NonNull Bundle extras) {
        return new MatrixCursor(new String[0]);
    }

    @Override
    public AssetFileDescriptor onOpenPreview(@NonNull String mediaId, @NonNull Point size,
            @Nullable Bundle extras, @Nullable CancellationSignal signal)
            throws FileNotFoundException {
        throw new UnsupportedOperationException("onOpenPreview not supported");
    }

    @Override
    public ParcelFileDescriptor onOpenMedia(@NonNull String mediaId, @Nullable Bundle extras,
            @Nullable CancellationSignal signal) throws FileNotFoundException {
        throw new UnsupportedOperationException("onOpenMedia not supported");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

}
