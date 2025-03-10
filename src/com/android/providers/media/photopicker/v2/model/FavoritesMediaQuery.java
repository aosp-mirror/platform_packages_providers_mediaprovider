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

import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_CLOUD_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_IS_FAVORITE;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_LOCAL_ID;
import static com.android.providers.media.photopicker.v2.sqlite.MediaProjection.prependTableName;

import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SelectSQLiteQueryBuilder;

import java.util.Locale;

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
    }

    public FavoritesMediaQuery(@NonNull Bundle queryArgs) {
        super(queryArgs);

        // This is not required for favorites album media query.
        mShouldPopulateItemsBeforeCount = false;
    }

    @Override
    public void addWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            boolean reverseOrder) {
        super.addWhereClause(queryBuilder, table, localAuthority, cloudAuthority, reverseOrder);

        final String localFavoriteMediaWhereClause =
                getLocalFavoriteMediaWhereClause(queryBuilder, table, cloudAuthority);
        final String cloudFavoriteMediaWhereClause = getCloudFavoriteMediaWhereClause(table);

        final String favoriteMediaWhereClause = (localFavoriteMediaWhereClause
                + " OR " + cloudFavoriteMediaWhereClause);
        queryBuilder.appendWhereStandalone(favoriteMediaWhereClause);
    }

    private String getLocalFavoriteMediaWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String cloudAuthority) {
        if (cloudAuthority == null) {
            return String.format(
                    Locale.ROOT,
                    "(%s = 1 AND %s IS NULL)",
                    prependTableName(table, KEY_IS_FAVORITE),
                    prependTableName(table, KEY_CLOUD_ID)
            );
        } else {
            // Select all the deduped local media items that have been marked as favorite in the
            // local media provider or the cloud media provider.
            final String[] columns = new String[1];
            columns[0] = prependTableName(table, KEY_LOCAL_ID);
            final String localMediaWhereClause =
                    prependTableName(table, KEY_LOCAL_ID) + " IS NOT NULL";
            final String favoriteMediaWhereClause =
                    prependTableName(table, KEY_IS_FAVORITE) + " = 1";

            final String innerQuery = SQLiteQueryBuilder.buildQueryString(
                    /* distinct */ true,
                    /* tables */ queryBuilder.getTables(),
                    /* columns */ columns,
                    /* where */ (localMediaWhereClause + " AND " + favoriteMediaWhereClause),
                    /* groupBy */ null,
                    /* having */ null,
                    /* orderBy */ null,
                    /* limit */ null
            );

            return String.format(Locale.ROOT,
                    "(%s IN (%s))",
                    prependTableName(table, KEY_LOCAL_ID),
                    innerQuery);
        }
    }

    private String getCloudFavoriteMediaWhereClause(@NonNull PickerSQLConstants.Table table) {
        return String.format(Locale.ROOT,
                "(%s = 1 AND %s IS NULL)",
                prependTableName(table, KEY_IS_FAVORITE),
                prependTableName(table, KEY_LOCAL_ID));
    }
}
