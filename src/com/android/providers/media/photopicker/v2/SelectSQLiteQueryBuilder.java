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

import android.annotation.SuppressLint;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Helper SQLite Query Builder class that uses the Builder pattern to make writing Select SQL
 * queries easier.
 *
 * This is a thin wrapper around {@link android.database.sqlite.SQLiteQueryBuilder}.
 */
public class SelectSQLiteQueryBuilder extends SQLiteQueryBuilder<SelectSQLiteQueryBuilder> {
    @Nullable
    private String[] mProjection;
    @Nullable
    private String mSortOrder;
    @Nullable
    private Integer mLimit;
    @Nullable
    private Integer mOffset;

    public SelectSQLiteQueryBuilder(@NonNull SQLiteDatabase database) {
        super(database);
    }

    /**
     * This method sets the projection of the Select SQL query.
     * It is recommended to set this for all SQL queries. The default value is null.
     * Passing null will return all columns, which is discouraged to prevent reading data from
     * storage that isn't going to be used.
     *
     * @param projection A list of which columns to return.
     * @return An instance of this class.
     */
    public SelectSQLiteQueryBuilder setProjection(@Nullable List<String> projection) {
        String[] projectionArray = null;
        if (projection != null) {
            projectionArray = projection.toArray(new String[0]);
        }
        return setProjection(projectionArray);
    }

    /**
     * This method sets the projection of the Select SQL query.
     * It is recommended to set this for all SQL queries. The default value is null.
     * Passing null will return all columns, which is discouraged to prevent reading data from
     * storage that isn't going to be used.
     *
     * @param projection A String array of which columns to return.
     * @return An instance of this class.
     */
    public SelectSQLiteQueryBuilder setProjection(@Nullable String[] projection) {
        this.mProjection = projection;
        return this;
    }

    /**
     * Sort order tells us how to order the rows, formatted as an SQL ORDER BY clause
     * (excluding the ORDER BY itself).
     * Not setting it or setting it to null will use the default sort order, which may be unordered.
     *
     * @param sortOrder A String denoting the sort order.
     * @return An instance of this class.
     */
    public SelectSQLiteQueryBuilder setSortOrder(@Nullable String sortOrder) {
        this.mSortOrder = sortOrder;
        return this;
    }

    /**
     * Limits the number of rows returned by the query, formatted as LIMIT clause.
     * Not setting it or setting it to null denotes no LIMIT clause.
     *
     * @param limit An integer that denotes the query limit
     * @return An instance of this class.
     */
    public SelectSQLiteQueryBuilder setLimit(@Nullable Integer limit) {
        this.mLimit = limit;
        return this;
    }

    /**
     * Offsets the query result by the given number of rows.
     *
     * @param offset An integer that denotes the query offset.
     * @return An instance of this class.
     */
    public SelectSQLiteQueryBuilder setOffset(@Nullable Integer offset) {
        this.mOffset = offset;
        return this;
    }

    /**
     * Add a standalone chunk to the WHERE clause of this query.
     * It automatically appends AND to any existing WHERE clause already under construction before
     * appending the given standalone expression wrapped in parentheses.
     */
    public SelectSQLiteQueryBuilder appendWhereStandalone(@NonNull String whereClause) {
        mSQLiteQueryBuilder.appendWhereStandalone(whereClause);
        return this;
    }

    /**
     * @return the raw select query built using class variables.
     */
    public String buildQuery() {
        return mSQLiteQueryBuilder.buildQuery(
                mProjection,
                /* selection */ null,
                /* groupBy */ null,
                /* having */ null,
                mSortOrder,
                buildLimitClause()
        );
    }

    /**
     * @return the limit clause built from the limit and offset class variables.
     */
    @SuppressLint("DefaultLocale")
    private String buildLimitClause() {
        if (mLimit == null) {
            return null;
        } else if (mOffset == null) {
            return mLimit.toString();
        } else {
            return String.format("%d OFFSET %d", mLimit, mOffset);
        }
    }
}
