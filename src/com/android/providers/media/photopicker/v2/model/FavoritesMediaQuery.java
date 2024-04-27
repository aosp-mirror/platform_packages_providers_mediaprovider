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

import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_IS_FAVORITE;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.v2.SelectSQLiteQueryBuilder;

/**
 * This is a convenience class for Favorites album related SQL queries performed on the Picker
 * Database.
 */
public class FavoritesMediaQuery extends MediaQuery {
    public FavoritesMediaQuery(
            @NonNull Bundle queryArgs,
            int pageSize) {
        super(queryArgs);

        mPageSize = pageSize;

        // Don't remove duplicates because it is possible that the duplicate row is marked as
        // favorite.
        mShouldDedupe = false;
    }

    @Override
    public void addWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            boolean reverseOrder
    ) {
        super.addWhereClause(queryBuilder, localAuthority, cloudAuthority, reverseOrder);

        queryBuilder.appendWhereStandalone(KEY_IS_FAVORITE + " = 1");
    }
}
