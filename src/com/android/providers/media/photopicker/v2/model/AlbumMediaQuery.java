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

package com.android.providers.media.photopicker.v2.model;

import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_ALBUM_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_CLOUD_ID;

import static java.util.Objects.requireNonNull;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.v2.SelectSQLiteQueryBuilder;

/**
 * This is a convenience class for Album content related SQL queries performed on the Picker
 * Database.
 */
public class AlbumMediaQuery extends MediaQuery {
    @NonNull
    final String mAlbumId;
    @NonNull
    final String mAlbumAuthority;

    public AlbumMediaQuery(
            @NonNull Bundle queryArgs,
            @NonNull String albumId) {
        super(queryArgs);

        mAlbumAuthority = requireNonNull(queryArgs.getString("album_authority"));
        mAlbumId = requireNonNull(albumId);

        // IS_VISIBLE column is not present in album_media table, so we should not add a where
        // clause that filters on this value.
        mShouldDedupe = false;
    }

    @NonNull
    public String getAlbumId() {
        return mAlbumId;
    }

    @NonNull
    public String getAlbumAuthority() {
        return mAlbumAuthority;
    }

    @Override
    public void addWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            boolean reverseOrder
    ) {
        super.addWhereClause(queryBuilder, localAuthority, cloudAuthority, reverseOrder);

        queryBuilder.appendWhereStandalone(KEY_ALBUM_ID + " = '" + mAlbumId + "'");

        // Don't include cloud items if the album authority is not equal to the cloud authority.
        if (!mAlbumAuthority.equals(cloudAuthority)) {
            queryBuilder.appendWhereStandalone(KEY_CLOUD_ID + " IS NULL");
        }
    }
}
