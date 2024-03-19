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
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_DATE_TAKEN_MS;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_ID;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.v2.SelectSQLiteQueryBuilder;

import java.util.List;
import java.util.Objects;

/**
 * Encapsulates all query arguments of a Media query i.e. query to fetch Media Items from Picker DB.
 */
public class MediaQuery {
    @NonNull
    private Long mDateTakenMs = Long.MIN_VALUE;
    @NonNull
    private Long mPickerId = Long.MIN_VALUE;
    @NonNull
    private Integer mPageSize = Integer.MAX_VALUE;
    @NonNull
    private final List<String> mProviders;

    public MediaQuery(Bundle queryArgs) {
        mPickerId = queryArgs.getLong("picker_id", Long.MIN_VALUE);
        mDateTakenMs = queryArgs.getLong("date_taken_millis", Long.MIN_VALUE);
        mPageSize = queryArgs.getInt("page_size", Integer.MAX_VALUE);
        mProviders = queryArgs.getStringArrayList("providers");

        Objects.requireNonNull(mProviders);
    }

    @NonNull
    public Integer getPageSize() {
        return mPageSize;
    }

    @NonNull
    public List<String> getProviders() {
        return mProviders;
    }

    /**
     * @param queryBuilder Adds SQL query where clause based on the Media query arguments to the
     *                     given query builder.
     * @param shouldQueryCloudMedia true if cloud media should be queried or not.
     * @param cloudAuthority The authority of the cloud provider.
     * @param reverseOrder by default, the sort order of the media query is
     *                     (Date taken DESC, Picker ID DESC). But for some queries we want to query
     *                     media in the reverse sort order i.e. (Date taken ASC, Picker id ASC).
     *                     This is true when the query is running in the reverse sort order.
     */
    public void addWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            boolean shouldQueryCloudMedia,
            @Nullable String cloudAuthority,
            boolean reverseOrder
    ) {
        if (!shouldQueryCloudMedia
                || mProviders.contains(cloudAuthority)) {
            queryBuilder.appendWhereStandalone(KEY_CLOUD_ID + " IS NULL");
        }

        addDateTakenClause(queryBuilder, reverseOrder);
    }

    /**
     * Adds the date taken clause to the given query builder.
     *
     * @param queryBuilder SelectSQLiteQueryBuilder to add the where clause
     * @param reverseOrder Since the media results are sorted by (Date taken DESC, Picker ID DESC),
     *                     this field is true when the query is made in the reverse order of the
     *                     expected sort order i.e. (Date taken ASC, Picker ID ASC),
     *                     and is false otherwise.
     */
    private void addDateTakenClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            boolean reverseOrder
    ) {
        if (mDateTakenMs != null && mDateTakenMs != Long.MIN_VALUE) {
            if (reverseOrder) {
                if (mPickerId != null && mPickerId != Long.MIN_VALUE) {
                    queryBuilder.appendWhereStandalone(
                            KEY_DATE_TAKEN_MS + " > " + mDateTakenMs
                                    + " OR ( " + KEY_DATE_TAKEN_MS + " = " + mDateTakenMs
                                    + " AND " + KEY_ID + " > " + mPickerId + ")");
                } else {
                    queryBuilder.appendWhereStandalone(
                            KEY_DATE_TAKEN_MS + " > " + mDateTakenMs);
                }
            } else {
                if (mPickerId != null && mPickerId != Long.MIN_VALUE) {
                    queryBuilder.appendWhereStandalone(
                            KEY_DATE_TAKEN_MS + " < " + mDateTakenMs
                                    + " OR ( " + KEY_DATE_TAKEN_MS + " = " + mDateTakenMs
                                    + " AND " + KEY_ID + " <= " + mPickerId + ")");
                } else {
                    queryBuilder.appendWhereStandalone(
                            KEY_DATE_TAKEN_MS + " < " + mDateTakenMs);
                }
            }

        }
    }
}
