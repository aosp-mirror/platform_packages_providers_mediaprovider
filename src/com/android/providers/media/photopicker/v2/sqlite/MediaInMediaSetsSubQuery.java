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
 * Utility class to query either local or cloud media items from media_in_media_sets table
 * and media table in Picker DB.
 */
public abstract class MediaInMediaSetsSubQuery extends MediaQuery {
    private final String mMediaSetPickerId;

    public MediaInMediaSetsSubQuery(Bundle queryArgs, String mediaSetPickerId) {
        super(queryArgs);

        mMediaSetPickerId = mediaSetPickerId;
    }

    /**
     * @return A string that contains the table clause of the sql query after joining the
     * media table and media_in_media_sets table.
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
                        "%s.%s = '%s'",
                        PickerSQLConstants.Table.MEDIA_IN_MEDIA_SETS.name(),
                        PickerSQLConstants.MediaInMediaSetsTableColumns
                                .MEDIA_SETS_PICKER_ID.getColumnName(),
                        mMediaSetPickerId));
    }
}
