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

import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Helper SQLite Query Builder class that uses the Builder pattern to make writing SQL queries
 * easier.
 *
 * This is a thin wrapper around {@link android.database.sqlite.SQLiteQueryBuilder}.
 *
 * @param <T> The concrete class that implements SQLiteQueryBuilder.
 */
public abstract class SQLiteQueryBuilder<T extends SQLiteQueryBuilder<?>> {
    @NonNull
    protected android.database.sqlite.SQLiteQueryBuilder mSQLiteQueryBuilder;
    @NonNull
    protected SQLiteDatabase mSQLiteDatabase;

    public SQLiteQueryBuilder(@NonNull SQLiteDatabase database) {
        mSQLiteQueryBuilder = new android.database.sqlite.SQLiteQueryBuilder();
        mSQLiteDatabase = database;
    }

    /**
     * Sets the list of tables to query. Multiple tables can be specified to perform a join.
     * For example:
     * setTables("foo, bar")
     * setTables("foo LEFT OUTER JOIN bar ON (foo.id = bar.foo_id)")
     *
     * @param inTables the list of tables to query on
     */
    public T setTables(@Nullable String inTables) {
        mSQLiteQueryBuilder.setTables(inTables);
        return (T) this;
    }

    /**
     * Builds the SQL query and returns it in String format.
     */
    public abstract String buildQuery();
}
