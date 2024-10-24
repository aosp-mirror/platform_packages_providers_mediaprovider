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

package com.android.providers.media.photopicker.v2.sqlite;

import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Utility class to query media items from search_result_media table and media table in Picker DB.
 */
public class SearchMediaQuery {
    @Nullable
    private final String mIntentAction;
    @NonNull
    private final List<String> mProviders;
    protected final int mPageSize;
    @NonNull
    final SearchLocalMediaSubQuery mLocalMediaSubQuery;
    @NonNull
    final SearchCloudMediaSubquery mCloudMediaSubquery;

    public SearchMediaQuery(Bundle queryArgs, int searchRequestID) {
        mIntentAction = queryArgs.getString("intent_action");
        mProviders = new ArrayList<>(
                Objects.requireNonNull(queryArgs.getStringArrayList("providers")));
        mPageSize = queryArgs.getInt("page_size", Integer.MAX_VALUE);

        mLocalMediaSubQuery = new SearchLocalMediaSubQuery(queryArgs, searchRequestID);
        mCloudMediaSubquery = new SearchCloudMediaSubquery(queryArgs, searchRequestID);
    }

    /**
     * @param database SQLiteDatabase wrapper for Picker DB
     * @param localAuthority authority of the local provider if it should be queried,
     *                       otherwise null.
     * @param cloudAuthority authority of the cloud provider if it should be queried,
     *                       otherwise null.
     * @return A string that contains the table clause of the sql query after joining the
     * media table and search_result_media.
     */
    public String getTableWithRequiredJoins(@NonNull SQLiteDatabase database,
                                            @Nullable String localAuthority,
                                            @Nullable String cloudAuthority) {

        final MediaProjection mediaProjection = new MediaProjection(
                localAuthority,
                cloudAuthority,
                mIntentAction,
                PickerSQLConstants.Table.MEDIA
        );

        final String localMediaRawQuery = getSubQuery(
                database,
                mLocalMediaSubQuery,
                localAuthority,
                cloudAuthority,
                mediaProjection
        );
        final String cloudMediaRawQuery = getSubQuery(
                database,
                mCloudMediaSubquery,
                localAuthority,
                cloudAuthority,
                mediaProjection
        );
        return String.format(
                Locale.ROOT,
                "( %s UNION ALL %s )",
                localMediaRawQuery,
                cloudMediaRawQuery);
    }

    private String getSubQuery(
            @NonNull SQLiteDatabase database,
            @NonNull SearchMediaSubQuery searchMediaSubquery,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            @NonNull MediaProjection mediaProjection) {
        final SelectSQLiteQueryBuilder subQueryBuilder =
                new SelectSQLiteQueryBuilder(database);
        subQueryBuilder
                .setTables(searchMediaSubquery.getTableWithRequiredJoins())
                .setProjection(mediaProjection.getAll());
        searchMediaSubquery.addWhereClause(
                subQueryBuilder,
                PickerSQLConstants.Table.MEDIA,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ false
        );
        return subQueryBuilder.buildQuery();
    }

    @Nullable
    public String getIntentAction() {
        return mIntentAction;
    }

    @NonNull
    public List<String> getProviders() {
        return mProviders;
    }

    public int getPageSize() {
        return mPageSize;
    }
}
