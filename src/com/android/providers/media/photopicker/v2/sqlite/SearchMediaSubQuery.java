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

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.v2.model.MediaQuery;

import java.util.Locale;

/**
 * Utility class to query either local or cloud media items from search_result_media table
 * and media table in Picker DB.
 */
public abstract class SearchMediaSubQuery extends MediaQuery {
    private final int mSearchRequestID;

    public SearchMediaSubQuery(Bundle queryArgs, int searchRequestID) {
        super(queryArgs);

        mSearchRequestID = searchRequestID;
    }

    /**
     * @return A string that contains the table clause of the sql query after joining the
     * media table and search_result_media.
     */
    public abstract String getTableWithRequiredJoins();

    @Override
    public void addWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            boolean reverseOrder
    ) {
        super.addWhereClause(queryBuilder, table, localAuthority, cloudAuthority, reverseOrder);

        queryBuilder.appendWhereStandalone(
                String.format(
                        Locale.ROOT,
                        "%s.%s = '%d'",
                        PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name(),
                        PickerSQLConstants.SearchResultMediaTableColumns
                                .SEARCH_REQUEST_ID.getColumnName(),
                        mSearchRequestID));
    }
}
