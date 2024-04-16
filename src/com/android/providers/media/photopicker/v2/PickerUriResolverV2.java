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

import static com.android.providers.media.MediaApplication.getAppContext;

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
    public static final String BASE_PICKER_PATH = "picker_internal/v2/";
    static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static final int PICKER_INTERNAL_MEDIA = 1;
    static final int PICKER_INTERNAL_ALBUM = 2;
    static final int PICKER_INTERNAL_ALBUM_CONTENT = 3;
    static final int PICKER_INTERNAL_AVAILABLE_PROVIDERS = 4;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            UriMatcher.NO_MATCH,
            PICKER_INTERNAL_MEDIA,
            PICKER_INTERNAL_ALBUM,
            PICKER_INTERNAL_ALBUM_CONTENT,
            PICKER_INTERNAL_AVAILABLE_PROVIDERS,
    })
    private @interface PickerQuery {}

    static {
        sUriMatcher.addURI(MediaStore.AUTHORITY, BASE_PICKER_PATH + "media", PICKER_INTERNAL_MEDIA);
        sUriMatcher.addURI(MediaStore.AUTHORITY, BASE_PICKER_PATH + "album", PICKER_INTERNAL_ALBUM);
        sUriMatcher.addURI(
                MediaStore.AUTHORITY,
                BASE_PICKER_PATH + "album_content",
                PICKER_INTERNAL_ALBUM_CONTENT
        );
        sUriMatcher.addURI(
                MediaStore.AUTHORITY,
                BASE_PICKER_PATH + "available_providers",
                PICKER_INTERNAL_AVAILABLE_PROVIDERS
        );
    }

    /**
     * Redirect a Picker internal query to the right {@link PickerDataLayerV2} method to serve the
     * request.
     */
    @NonNull
    public static Cursor query(Context appContext, @NonNull Uri uri, @Nullable Bundle queryArgs) {
        @PickerQuery
        final int query = sUriMatcher.match(uri);

        switch (query) {
            case PICKER_INTERNAL_MEDIA:
                return PickerDataLayerV2.queryMedia(appContext, queryArgs);
            case PICKER_INTERNAL_ALBUM:
                return PickerDataLayerV2.queryAlbum(queryArgs);
            case PICKER_INTERNAL_ALBUM_CONTENT:
                return PickerDataLayerV2.queryAlbumContent(queryArgs);
            case PICKER_INTERNAL_AVAILABLE_PROVIDERS:
                return PickerDataLayerV2.queryAvailableProviders(getAppContext());
            default:
                throw new UnsupportedOperationException("Could not recognize content URI " + uri);
        }
    }
}
