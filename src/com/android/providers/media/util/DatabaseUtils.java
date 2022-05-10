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

import static android.content.ContentResolver.QUERY_ARG_GROUP_COLUMNS;
import static android.content.ContentResolver.QUERY_ARG_LIMIT;
import static android.content.ContentResolver.QUERY_ARG_OFFSET;
import static android.content.ContentResolver.QUERY_ARG_SORT_COLLATION;
import static android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS;
import static android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION;
import static android.content.ContentResolver.QUERY_ARG_SORT_LOCALE;
import static android.content.ContentResolver.QUERY_ARG_SQL_GROUP_BY;
import static android.content.ContentResolver.QUERY_ARG_SQL_LIMIT;
import static android.content.ContentResolver.QUERY_ARG_SQL_SELECTION;
import static android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS;
import static android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER;
import static android.content.ContentResolver.QUERY_SORT_DIRECTION_ASCENDING;
import static android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING;

import static com.android.providers.media.util.Logging.TAG;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Bundle;
import android.os.Trace;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

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
                            // Escape single quote character while appending the string.
                            res.append(arg.toString().replace("'", "''"));
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

    /**
     * Simple attempt to balance the given SQL expression by adding parenthesis
     * when needed.
     * <p>
     * Since this is only used for recovering from abusive apps, we're not
     * interested in trying to build a fully valid SQL parser up in Java. It'll
     * give up when it encounters complex SQL, such as string literals.
     */
    public static @Nullable String maybeBalance(@Nullable String sql) {
        if (sql == null) return null;

        int count = 0;
        char literal = '\0';
        for (int i = 0; i < sql.length(); i++) {
            final char c = sql.charAt(i);

            if (c == '\'' || c == '"') {
                if (literal == '\0') {
                    // Start literal
                    literal = c;
                } else if (literal == c) {
                    // End literal
                    literal = '\0';
                }
            }

            if (literal == '\0') {
                if (c == '(') {
                    count++;
                } else if (c == ')') {
                    count--;
                }
            }
        }
        while (count > 0) {
            sql = sql + ")";
            count--;
        }
        while (count < 0) {
            sql = "(" + sql;
            count++;
        }
        return sql;
    }

    /**
     * {@link ContentResolver} offers several query arguments, ranging from
     * helpful higher-level concepts like
     * {@link ContentResolver#QUERY_ARG_GROUP_COLUMNS} to raw SQL like
     * {@link ContentResolver#QUERY_ARG_SQL_GROUP_BY}. We prefer the
     * higher-level concepts when defined by the caller, but we'll fall back to
     * the raw SQL if that's all the caller provided.
     * <p>
     * This method will "resolve" all higher-level query arguments into the raw
     * SQL arguments, giving us easy values to carry over into
     * {@link SQLiteQueryBuilder}.
     */
    public static void resolveQueryArgs(@NonNull Bundle queryArgs,
            @NonNull Consumer<String> honored,
            @NonNull Function<String, String> collatorFactory) {
        // We're always going to handle selections
        honored.accept(QUERY_ARG_SQL_SELECTION);
        honored.accept(QUERY_ARG_SQL_SELECTION_ARGS);

        resolveGroupBy(queryArgs, honored);
        resolveSortOrder(queryArgs, honored, collatorFactory);
        resolveLimit(queryArgs, honored);
    }

    private static void resolveGroupBy(@NonNull Bundle queryArgs,
            @NonNull Consumer<String> honored) {
        final String[] columns = queryArgs.getStringArray(QUERY_ARG_GROUP_COLUMNS);
        if (columns != null && columns.length != 0) {
            String groupBy = TextUtils.join(", ", columns);
            honored.accept(QUERY_ARG_GROUP_COLUMNS);

            queryArgs.putString(QUERY_ARG_SQL_GROUP_BY, groupBy);
        } else {
            honored.accept(QUERY_ARG_SQL_GROUP_BY);
        }
    }

    private static void resolveSortOrder(@NonNull Bundle queryArgs,
            @NonNull Consumer<String> honored,
            @NonNull Function<String, String> collatorFactory) {
        final String[] columns = queryArgs.getStringArray(QUERY_ARG_SORT_COLUMNS);
        if (columns != null && columns.length != 0) {
            String sortOrder = TextUtils.join(", ", columns);
            honored.accept(QUERY_ARG_SORT_COLUMNS);

            if (queryArgs.containsKey(QUERY_ARG_SORT_LOCALE)) {
                final String collatorName = collatorFactory.apply(
                        queryArgs.getString(QUERY_ARG_SORT_LOCALE));
                sortOrder += " COLLATE " + collatorName;
                honored.accept(QUERY_ARG_SORT_LOCALE);
            } else {
                // Interpret PRIMARY and SECONDARY collation strength as no-case collation based
                // on their javadoc descriptions.
                final int collation = queryArgs.getInt(
                        QUERY_ARG_SORT_COLLATION, java.text.Collator.IDENTICAL);
                switch (collation) {
                    case java.text.Collator.IDENTICAL:
                        honored.accept(QUERY_ARG_SORT_COLLATION);
                        break;
                    case java.text.Collator.PRIMARY:
                    case java.text.Collator.SECONDARY:
                        sortOrder += " COLLATE NOCASE";
                        honored.accept(QUERY_ARG_SORT_COLLATION);
                        break;
                }
            }

            final int sortDir = queryArgs.getInt(QUERY_ARG_SORT_DIRECTION, Integer.MIN_VALUE);
            switch (sortDir) {
                case QUERY_SORT_DIRECTION_ASCENDING:
                    sortOrder += " ASC";
                    honored.accept(QUERY_ARG_SORT_DIRECTION);
                    break;
                case QUERY_SORT_DIRECTION_DESCENDING:
                    sortOrder += " DESC";
                    honored.accept(QUERY_ARG_SORT_DIRECTION);
                    break;
            }

            queryArgs.putString(QUERY_ARG_SQL_SORT_ORDER, sortOrder);
        } else {
            honored.accept(QUERY_ARG_SQL_SORT_ORDER);
        }
    }

    private static void resolveLimit(@NonNull Bundle queryArgs,
            @NonNull Consumer<String> honored) {
        final int limit = queryArgs.getInt(QUERY_ARG_LIMIT, Integer.MIN_VALUE);
        if (limit != Integer.MIN_VALUE) {
            String limitString = Integer.toString(limit);
            honored.accept(QUERY_ARG_LIMIT);

            final int offset = queryArgs.getInt(QUERY_ARG_OFFSET, Integer.MIN_VALUE);
            if (offset != Integer.MIN_VALUE) {
                limitString += " OFFSET " + offset;
                honored.accept(QUERY_ARG_OFFSET);
            }

            queryArgs.putString(QUERY_ARG_SQL_LIMIT, limitString);
        } else {
            honored.accept(QUERY_ARG_SQL_LIMIT);
        }
    }

    /**
     * Gracefully recover from abusive callers that are smashing limits into
     * {@link Uri}.
     */
    public static void recoverAbusiveLimit(@NonNull Uri uri, @NonNull Bundle queryArgs) {
        final String origLimit = queryArgs.getString(QUERY_ARG_SQL_LIMIT);
        final String uriLimit = uri.getQueryParameter("limit");

        if (!TextUtils.isEmpty(uriLimit)) {
            // Yell if we already had a group by requested
            if (!TextUtils.isEmpty(origLimit)) {
                throw new IllegalArgumentException(
                        "Abusive '" + uriLimit + "' conflicts with requested '" + origLimit + "'");
            }

            Log.w(TAG, "Recovered abusive '" + uriLimit + "' from '" + uri + "'");

            queryArgs.putString(QUERY_ARG_SQL_LIMIT, uriLimit);
        }
    }

    /**
     * Gracefully recover from abusive callers that are smashing invalid
     * {@code GROUP BY} clauses into {@code WHERE} clauses.
     */
    public static void recoverAbusiveSelection(@NonNull Bundle queryArgs) {
        final String origSelection = queryArgs.getString(QUERY_ARG_SQL_SELECTION);
        final String origGroupBy = queryArgs.getString(QUERY_ARG_SQL_GROUP_BY);

        final int index = (origSelection != null)
                ? origSelection.toUpperCase(Locale.ROOT).indexOf(" GROUP BY ") : -1;
        if (index != -1) {
            String selection = origSelection.substring(0, index);
            String groupBy = origSelection.substring(index + " GROUP BY ".length());

            // Try balancing things out
            selection = maybeBalance(selection);
            groupBy = maybeBalance(groupBy);

            // Yell if we already had a group by requested
            if (!TextUtils.isEmpty(origGroupBy)) {
                throw new IllegalArgumentException(
                        "Abusive '" + groupBy + "' conflicts with requested '" + origGroupBy + "'");
            }

            Log.w(TAG, "Recovered abusive '" + selection + "' and '" + groupBy + "' from '"
                    + origSelection + "'");

            queryArgs.putString(QUERY_ARG_SQL_SELECTION, selection);
            queryArgs.putString(QUERY_ARG_SQL_GROUP_BY, groupBy);
        }
    }

    /**
     * Gracefully recover from abusive callers that are smashing limits into
     * {@code ORDER BY} clauses.
     */
    public static void recoverAbusiveSortOrder(@NonNull Bundle queryArgs) {
        final String origSortOrder = queryArgs.getString(QUERY_ARG_SQL_SORT_ORDER);
        final String origLimit = queryArgs.getString(QUERY_ARG_SQL_LIMIT);

        final int index = (origSortOrder != null)
                ? origSortOrder.toUpperCase(Locale.ROOT).indexOf(" LIMIT ") : -1;
        if (index != -1) {
            String sortOrder = origSortOrder.substring(0, index);
            String limit = origSortOrder.substring(index + " LIMIT ".length());

            // Yell if we already had a limit requested
            if (!TextUtils.isEmpty(origLimit)) {
                throw new IllegalArgumentException(
                        "Abusive '" + limit + "' conflicts with requested '" + origLimit + "'");
            }

            Log.w(TAG, "Recovered abusive '" + sortOrder + "' and '" + limit + "' from '"
                    + origSortOrder + "'");

            queryArgs.putString(QUERY_ARG_SQL_SORT_ORDER, sortOrder);
            queryArgs.putString(QUERY_ARG_SQL_LIMIT, limit);
        }
    }

    /**
     * Shamelessly borrowed from {@link ContentResolver}.
     */
    public static @Nullable Bundle createSqlQueryBundle(
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder) {

        if (selection == null && selectionArgs == null && sortOrder == null) {
            return null;
        }

        Bundle queryArgs = new Bundle();
        if (selection != null) {
            queryArgs.putString(QUERY_ARG_SQL_SELECTION, selection);
        }
        if (selectionArgs != null) {
            queryArgs.putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
        }
        if (sortOrder != null) {
            queryArgs.putString(QUERY_ARG_SQL_SORT_ORDER, sortOrder);
        }
        return queryArgs;
    }

    public static @NonNull ArrayMap<String, Object> getValues(@NonNull ContentValues values) {
        final ArrayMap<String, Object> res = new ArrayMap<>();
        for (String key : values.keySet()) {
            res.put(key, values.get(key));
        }
        return res;
    }

    public static long executeInsert(@NonNull SQLiteDatabase db, @NonNull String sql,
            @Nullable Object[] bindArgs) throws SQLException {
        Trace.beginSection("executeInsert");
        try (SQLiteStatement st = db.compileStatement(sql)) {
            bindArgs(st, bindArgs);
            return st.executeInsert();
        } finally {
            Trace.endSection();
        }
    }

    public static int executeUpdateDelete(@NonNull SQLiteDatabase db, @NonNull String sql,
            @Nullable Object[] bindArgs) throws SQLException {
        Trace.beginSection("executeUpdateDelete");
        try (SQLiteStatement st = db.compileStatement(sql)) {
            bindArgs(st, bindArgs);
            return st.executeUpdateDelete();
        } finally {
            Trace.endSection();
        }
    }

    private static void bindArgs(@NonNull SQLiteStatement st, @Nullable Object[] bindArgs) {
        if (bindArgs == null) return;

        for (int i = 0; i < bindArgs.length; i++) {
            final Object bindArg = bindArgs[i];
            switch (getTypeOfObject(bindArg)) {
                case Cursor.FIELD_TYPE_NULL:
                    st.bindNull(i + 1);
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    st.bindLong(i + 1, ((Number) bindArg).longValue());
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    st.bindDouble(i + 1, ((Number) bindArg).doubleValue());
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    st.bindBlob(i + 1, (byte[]) bindArg);
                    break;
                case Cursor.FIELD_TYPE_STRING:
                default:
                    if (bindArg instanceof Boolean) {
                        // Provide compatibility with legacy
                        // applications which may pass Boolean values in
                        // bind args.
                        st.bindLong(i + 1, ((Boolean) bindArg).booleanValue() ? 1 : 0);
                    } else {
                        st.bindString(i + 1, bindArg.toString());
                    }
                    break;
            }
        }
    }

    public static @NonNull String bindList(@NonNull Object... args) {
        final StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (int i = 0; i < args.length; i++) {
            sb.append('?');
            if (i < args.length - 1) {
                sb.append(',');
            }
        }
        sb.append(')');
        return DatabaseUtils.bindSelection(sb.toString(), args);
    }

    /**
     * Escape the given argument for use in a {@code LIKE} statement.
     */
    public static String escapeForLike(@NonNull String arg) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arg.length(); i++) {
            final char c = arg.charAt(i);
            switch (c) {
                case '%': sb.append('\\');
                    break;
                case '_': sb.append('\\');
                    break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String replaceMatchAnyChar(@NonNull String arg) {
        return arg.replace('*', '%');
    }

    public static boolean parseBoolean(@Nullable Object value, boolean def) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        } else if (value instanceof String) {
            final String stringValue = ((String) value).toLowerCase(Locale.ROOT);
            return (!"false".equals(stringValue) && !"0".equals(stringValue));
        } else {
            return def;
        }
    }

    public static boolean getAsBoolean(@NonNull Bundle extras,
            @NonNull String key, boolean def) {
        return parseBoolean(extras.get(key), def);
    }

    public static boolean getAsBoolean(@NonNull ContentValues values,
            @NonNull String key, boolean def) {
        return parseBoolean(values.get(key), def);
    }

    public static long getAsLong(@NonNull ContentValues values,
            @NonNull String key, long def) {
        final Long value = values.getAsLong(key);
        return (value != null) ? value : def;
    }
}
