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
 * Utility class to query local media items from media_in_media_sets table and media table in
 * Picker DB.
 */
public class MediaInMediaSetsLocalSubQuery extends MediaInMediaSetsSubQuery {
    public MediaInMediaSetsLocalSubQuery(Bundle queryArgs, String mediaSetPickerId) {
        super(queryArgs, mediaSetPickerId);
    }

    @Override
    public String getTableWithRequiredJoins() {
        return String.format(
                Locale.ROOT,
                " %s INNER JOIN %s ON %s.%s = %s.%s ",
                PickerSQLConstants.Table.MEDIA.name(),
                PickerSQLConstants.Table.MEDIA_IN_MEDIA_SETS.name(),
                PickerSQLConstants.Table.MEDIA.name(),
                KEY_LOCAL_ID,
                PickerSQLConstants.Table.MEDIA_IN_MEDIA_SETS.name(),
                PickerSQLConstants.MediaInMediaSetsTableColumns.LOCAL_ID.getColumnName());
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

        queryBuilder.appendWhereStandalone(
                String.format(
                        Locale.ROOT,
                        "%s.%s IS NULL",
                        PickerSQLConstants.Table.MEDIA.name(),
                        KEY_CLOUD_ID));
    }
}
