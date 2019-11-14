/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media.util;

import android.content.ContentValues;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class DatabaseUtils {
    /**
     * Bind the given selection with the given selection arguments.
     * <p>
     * Internally assumes that '?' is only ever used for arguments, and doesn't
     * appear as a literal or escaped value.
     * <p>
     * This method is typically useful for trusted code that needs to cook up a
     * fully-bound selection.
     *
     * @hide
     */
    public static @Nullable String bindSelection(@Nullable String selection,
            @Nullable Object... selectionArgs) {
        if (selection == null) return null;
        // If no arguments provided, so we can't bind anything
        if ((selectionArgs == null) || (selectionArgs.length == 0)) return selection;
        // If no bindings requested, so we can shortcut
        if (selection.indexOf('?') == -1) return selection;

        // Track the chars immediately before and after each bind request, to
        // decide if it needs additional whitespace added
        char before = ' ';
        char after = ' ';

        int argIndex = 0;
        final int len = selection.length();
        final StringBuilder res = new StringBuilder(len);
        for (int i = 0; i < len; ) {
            char c = selection.charAt(i++);
            if (c == '?') {
                // Assume this bind request is guarded until we find a specific
                // trailing character below
                after = ' ';

                // Sniff forward to see if the selection is requesting a
                // specific argument index
                int start = i;
                for (; i < len; i++) {
                    c = selection.charAt(i);
                    if (c < '0' || c > '9') {
                        after = c;
                        break;
                    }
                }
                if (start != i) {
                    argIndex = Integer.parseInt(selection.substring(start, i)) - 1;
                }

                // Manually bind the argument into the selection, adding
                // whitespace when needed for clarity
                final Object arg = selectionArgs[argIndex++];
                if (before != ' ' && before != '=') res.append(' ');
                switch (DatabaseUtils.getTypeOfObject(arg)) {
                    case Cursor.FIELD_TYPE_NULL:
                        res.append("NULL");
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        res.append(((Number) arg).longValue());
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        res.append(((Number) arg).doubleValue());
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        throw new IllegalArgumentException("Blobs not supported");
                    case Cursor.FIELD_TYPE_STRING:
                    default:
                        if (arg instanceof Boolean) {
                            // Provide compatibility with legacy applications which may pass
                            // Boolean values in bind args.
                            res.append(((Boolean) arg).booleanValue() ? 1 : 0);
                        } else {
                            res.append('\'');
                            res.append(arg.toString());
                            res.append('\'');
                        }
                        break;
                }
                if (after != ' ') res.append(' ');
            } else {
                res.append(c);
                before = c;
            }
        }
        return res.toString();
    }

    /**
     * Returns data type of the given object's value.
     *<p>
     * Returned values are
     * <ul>
     *   <li>{@link Cursor#FIELD_TYPE_NULL}</li>
     *   <li>{@link Cursor#FIELD_TYPE_INTEGER}</li>
     *   <li>{@link Cursor#FIELD_TYPE_FLOAT}</li>
     *   <li>{@link Cursor#FIELD_TYPE_STRING}</li>
     *   <li>{@link Cursor#FIELD_TYPE_BLOB}</li>
     *</ul>
     *</p>
     *
     * @param obj the object whose value type is to be returned
     * @return object value type
     * @hide
     */
    public static int getTypeOfObject(Object obj) {
        if (obj == null) {
            return Cursor.FIELD_TYPE_NULL;
        } else if (obj instanceof byte[]) {
            return Cursor.FIELD_TYPE_BLOB;
        } else if (obj instanceof Float || obj instanceof Double) {
            return Cursor.FIELD_TYPE_FLOAT;
        } else if (obj instanceof Long || obj instanceof Integer
                || obj instanceof Short || obj instanceof Byte) {
            return Cursor.FIELD_TYPE_INTEGER;
        } else {
            return Cursor.FIELD_TYPE_STRING;
        }
    }

    public static void copyFromCursorToContentValues(@NonNull String column, @NonNull Cursor cursor,
            @NonNull ContentValues values) {
        final int index = cursor.getColumnIndex(column);
        if (index != -1) {
            if (cursor.isNull(index)) {
                values.putNull(column);
            } else {
                values.put(column, cursor.getString(index));
            }
        }
    }
}
