/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker.util;

import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Provide the utility methods to handle cursor.
 */
public class CursorUtils {

    /**
     * Get the string from the {@code cursor} with the {@code columnName}.
     *
     * @param cursor the cursor to be parsed
     * @param columnName the column name of the value
     * @return the string value from the {@code cursor}, or {@code null} when {@code cursor} doesn't
     *         contain {@code columnName}
     */
    @Nullable
    public static String getCursorString(@NonNull Cursor cursor, @NonNull String columnName) {
        final int index = cursor.getColumnIndex(columnName);
        return (index != -1) ? cursor.getString(index) : null;
    }

    /**
     * Get the long value from the {@code cursor} with the {@code columnName}.
     *
     * @param cursor the cursor to be parsed
     * @param columnName the column name of the value
     * @return the long value from the {@code cursor}, or -1 when {@code cursor} doesn't contain
     *         {@code columnName}
     */
    public static long getCursorLong(@NonNull Cursor cursor, @NonNull String columnName) {
        final int index = cursor.getColumnIndex(columnName);
        if (index == -1) {
            return -1;
        }

        final String value = cursor.getString(index);
        if (value == null) {
            return -1;
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Get the int value from the {@code cursor} with the {@code columnName}.
     *
     * @param cursor the cursor to be parsed
     * @param columnName the column name of the value
     * @return the int value from the {@code cursor}, or 0 when {@code cursor} doesn't contain
     *         {@code columnName}
     */
    public static int getCursorInt(@NonNull Cursor cursor, @NonNull String columnName) {
        final int index = cursor.getColumnIndex(columnName);
        return (index != -1) ? cursor.getInt(index) : 0;
    }
}
