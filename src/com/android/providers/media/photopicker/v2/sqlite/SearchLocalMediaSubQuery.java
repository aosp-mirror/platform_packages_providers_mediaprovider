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

import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_CLOUD_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_LOCAL_ID;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Utility class to query local media items from search_result_media table and media table in
 * Picker DB.
 */
public class SearchLocalMediaSubQuery extends SearchMediaSubQuery {
    public SearchLocalMediaSubQuery(Bundle queryArgs, int searchRequestID) {
        super(queryArgs, searchRequestID);
    }

    @Override
    public String getTableWithRequiredJoins() {
        return String.format(
                Locale.ROOT,
                " %s INNER JOIN %s ON %s.%s = %s.%s ",
                PickerSQLConstants.Table.MEDIA.name(),
                PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name(),
                PickerSQLConstants.Table.MEDIA.name(),
                KEY_LOCAL_ID,
                PickerSQLConstants.Table.SEARCH_RESULT_MEDIA.name(),
                PickerSQLConstants.SearchResultMediaTableColumns.LOCAL_ID.getColumnName());
    }

    @Override
    public void addWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            boolean reverseOrder
    ) {
        super.addWhereClause(queryBuilder, table, localAuthority, cloudAuthority, reverseOrder);

        // In order to identify if a row represents local media item and not a cloud media item,
        // check if the cloud_id is null. We can't have a check on local_id because local_id can be
        // populated for a cloud media item as well.
        queryBuilder.appendWhereStandalone(
                String.format(
                        Locale.ROOT,
                        "%s.%s IS NULL",
                        PickerSQLConstants.Table.MEDIA.name(),
                        KEY_CLOUD_ID));
    }
}
