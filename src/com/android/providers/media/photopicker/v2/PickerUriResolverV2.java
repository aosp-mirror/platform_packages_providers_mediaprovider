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

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class PickerUriResolverV2 {
    public static final String PICKER_INTERNAL_PATH_SEGMENT = "picker_internal";
    public static final String PICKER_V2_PATH_SEGMENT = "v2";
    public static final String BASE_PICKER_PATH =
            PICKER_INTERNAL_PATH_SEGMENT + "/" + PICKER_V2_PATH_SEGMENT + "/";
    public static final String AVAILABLE_PROVIDERS_PATH_SEGMENT = "available_providers";
    public static final String COLLECTION_INFO_PATH_SEGMENT = "collection_info";
    public static final String MEDIA_PATH_SEGMENT = "media";
    public static final String ALBUM_PATH_SEGMENT = "album";
    public static final String SEARCH_RESULT_MEDIA_PATH_SEGMENT = "search_media";
    public static final String UPDATE_PATH_SEGMENT = "update";
    private static final String MEDIA_GRANTS_COUNT_PATH_SEGMENT = "media_grants_count";
    private static final String PREVIEW_PATH_SEGMENT = "preview";
    private static final String PRE_SELECTION_PATH_SEGMENT = "pre_selection";
    private static final String SEARCH_SUGGESTIONS_PATH_SEGMENT = "search_suggestions";


    static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static final int PICKER_INTERNAL_MEDIA = 1;
    static final int PICKER_INTERNAL_ALBUM = 2;
    static final int PICKER_INTERNAL_ALBUM_CONTENT = 3;
    static final int PICKER_INTERNAL_AVAILABLE_PROVIDERS = 4;
    static final int PICKER_INTERNAL_COLLECTION_INFO = 5;
    static final int PICKER_INTERNAL_MEDIA_GRANTS_COUNT = 6;
    static final int PICKER_INTERNAL_MEDIA_PREVIEW = 7;
    static final int PICKER_INTERNAL_PRE_SELECTION = 8;
    static final int PICKER_INTERNAL_SEARCH_MEDIA = 9;
    static final int PICKER_INTERNAL_SEARCH_SUGGESTIONS = 10;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            UriMatcher.NO_MATCH,
            PICKER_INTERNAL_MEDIA,
            PICKER_INTERNAL_ALBUM,
            PICKER_INTERNAL_ALBUM_CONTENT,
            PICKER_INTERNAL_AVAILABLE_PROVIDERS,
            PICKER_INTERNAL_COLLECTION_INFO,
            PICKER_INTERNAL_MEDIA_GRANTS_COUNT,
            PICKER_INTERNAL_MEDIA_PREVIEW,
            PICKER_INTERNAL_PRE_SELECTION,
            PICKER_INTERNAL_SEARCH_MEDIA,
            PICKER_INTERNAL_SEARCH_SUGGESTIONS,
    })
    private @interface PickerQuery {}

    static {
        sUriMatcher.addURI(MediaStore.AUTHORITY, BASE_PICKER_PATH + MEDIA_PATH_SEGMENT,
                PICKER_INTERNAL_MEDIA);
        sUriMatcher.addURI(MediaStore.AUTHORITY, BASE_PICKER_PATH + ALBUM_PATH_SEGMENT,
                PICKER_INTERNAL_ALBUM);
        sUriMatcher.addURI(
                MediaStore.AUTHORITY,
                BASE_PICKER_PATH + ALBUM_PATH_SEGMENT + "/*",
                PICKER_INTERNAL_ALBUM_CONTENT
        );
        sUriMatcher.addURI(
                MediaStore.AUTHORITY,
                BASE_PICKER_PATH + AVAILABLE_PROVIDERS_PATH_SEGMENT,
                PICKER_INTERNAL_AVAILABLE_PROVIDERS
        );
        sUriMatcher.addURI(
                MediaStore.AUTHORITY,
                BASE_PICKER_PATH + COLLECTION_INFO_PATH_SEGMENT,
                PICKER_INTERNAL_COLLECTION_INFO
        );
        sUriMatcher.addURI(MediaStore.AUTHORITY, BASE_PICKER_PATH + MEDIA_GRANTS_COUNT_PATH_SEGMENT,
                PICKER_INTERNAL_MEDIA_GRANTS_COUNT);
        sUriMatcher.addURI(MediaStore.AUTHORITY,
                BASE_PICKER_PATH + MEDIA_PATH_SEGMENT + "/" + PREVIEW_PATH_SEGMENT,
                PICKER_INTERNAL_MEDIA_PREVIEW);
        sUriMatcher.addURI(MediaStore.AUTHORITY,
                BASE_PICKER_PATH + MEDIA_PATH_SEGMENT + "/" + PRE_SELECTION_PATH_SEGMENT,
                PICKER_INTERNAL_PRE_SELECTION);
        sUriMatcher.addURI(
                MediaStore.AUTHORITY,
                BASE_PICKER_PATH + SEARCH_RESULT_MEDIA_PATH_SEGMENT + "/*",
                PICKER_INTERNAL_SEARCH_MEDIA
        );
        sUriMatcher.addURI(MediaStore.AUTHORITY,
                BASE_PICKER_PATH + SEARCH_SUGGESTIONS_PATH_SEGMENT,
                PICKER_INTERNAL_SEARCH_SUGGESTIONS);
    }

    /**
     * Redirect a Picker internal query to the right {@link PickerDataLayerV2} method to serve the
     * request.
     */
    @Nullable
    public static Cursor query(
            @NonNull Context appContext,
            @NonNull Uri uri,
            @Nullable Bundle queryArgs) {
        @PickerQuery
        final int query = sUriMatcher.match(uri);

        switch (query) {
            case PICKER_INTERNAL_MEDIA:
                return PickerDataLayerV2.queryMedia(appContext, requireNonNull(queryArgs));
            case PICKER_INTERNAL_ALBUM:
                return PickerDataLayerV2.queryAlbums(appContext, requireNonNull(queryArgs));
            case PICKER_INTERNAL_ALBUM_CONTENT:
                final String albumId = uri.getLastPathSegment();
                return PickerDataLayerV2.queryAlbumMedia(
                        appContext,
                        requireNonNull(queryArgs),
                        requireNonNull(albumId));
            case PICKER_INTERNAL_AVAILABLE_PROVIDERS:
                return PickerDataLayerV2.queryAvailableProviders(appContext);
            case PICKER_INTERNAL_COLLECTION_INFO:
                return PickerDataLayerV2.queryCollectionInfo();
            case PICKER_INTERNAL_MEDIA_GRANTS_COUNT:
                return PickerDataLayerV2.fetchCountForPreGrantedItems(appContext,
                        requireNonNull(queryArgs));
            case PICKER_INTERNAL_MEDIA_PREVIEW:
                return PickerDataLayerV2.queryPreviewMedia(appContext, queryArgs);
            case PICKER_INTERNAL_PRE_SELECTION:
                return PickerDataLayerV2.queryMediaForPreSelection(appContext, queryArgs);
            case PICKER_INTERNAL_SEARCH_MEDIA:
                final int searchRequestId =
                        Integer.parseInt(requireNonNull(uri.getLastPathSegment()));
                return PickerDataLayerV2.querySearchMedia(
                        appContext,
                        requireNonNull(queryArgs),
                        searchRequestId);
            case PICKER_INTERNAL_SEARCH_SUGGESTIONS:
                return PickerDataLayerV2.querySearchSuggestions(
                        appContext,
                        requireNonNull(queryArgs),
                        null
                );
            default:
                throw new UnsupportedOperationException("Could not recognize content URI " + uri);
        }
    }
}
