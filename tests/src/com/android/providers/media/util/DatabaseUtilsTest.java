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
import static android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER;
import static android.content.ContentResolver.QUERY_SORT_DIRECTION_ASCENDING;
import static android.database.DatabaseUtils.bindSelection;

import static com.android.providers.media.util.DatabaseUtils.maybeBalance;
import static com.android.providers.media.util.DatabaseUtils.recoverAbusiveLimit;
import static com.android.providers.media.util.DatabaseUtils.recoverAbusiveSortOrder;
import static com.android.providers.media.util.DatabaseUtils.resolveQueryArgs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.ArraySet;
import android.util.Pair;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Function;

@RunWith(AndroidJUnit4.class)
public class DatabaseUtilsTest {
    private final Bundle args = new Bundle();
    private final ArraySet<String> honored = new ArraySet<>();

    private static final Object[] ARGS = { "baz", 4, null };

    @Before
    public void setUp() {
        args.clear();
        honored.clear();
    }

    @Test
    public void testBindSelection_none() throws Exception {
        assertEquals(null,
                bindSelection(null, ARGS));
        assertEquals("",
                bindSelection("", ARGS));
        assertEquals("foo=bar",
                bindSelection("foo=bar", ARGS));
    }

    @Test
    public void testBindSelection_normal() throws Exception {
        assertEquals("foo='baz'",
                bindSelection("foo=?", ARGS));
        assertEquals("foo='baz' AND bar=4",
                bindSelection("foo=? AND bar=?", ARGS));
        assertEquals("foo='baz' AND bar=4 AND meow=NULL",
                bindSelection("foo=? AND bar=? AND meow=?", ARGS));
    }

    @Test
    public void testBindSelection_whitespace() throws Exception {
        assertEquals("BETWEEN 5 AND 10",
                bindSelection("BETWEEN? AND ?", 5, 10));
        assertEquals("IN 'foo'",
                bindSelection("IN?", "foo"));
    }

    @Test
    public void testBindSelection_indexed() throws Exception {
        assertEquals("foo=10 AND bar=11 AND meow=1",
                bindSelection("foo=?10 AND bar=? AND meow=?1",
                        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
    }

    @Test
    public void testResolveQueryArgs_GroupBy() throws Exception {
        args.putStringArray(QUERY_ARG_GROUP_COLUMNS, new String[] { "foo", "bar" });
        args.putString(QUERY_ARG_SQL_GROUP_BY, "raw");

        resolveQueryArgs(args, honored::add, Function.identity());
        assertTrue(honored.contains(QUERY_ARG_GROUP_COLUMNS));
        assertFalse(honored.contains(QUERY_ARG_SQL_GROUP_BY));
        assertEquals("foo, bar", args.getString(QUERY_ARG_SQL_GROUP_BY));
    }

    @Test
    public void testResolveQueryArgs_GroupBy_Raw() throws Exception {
        args.putString(QUERY_ARG_SQL_GROUP_BY, "raw");

        resolveQueryArgs(args, honored::add, Function.identity());
        assertTrue(honored.contains(QUERY_ARG_SQL_GROUP_BY));
        assertEquals("raw", args.getString(QUERY_ARG_SQL_GROUP_BY));
    }

    @Test
    public void testResolveQueryArgs_SortOrder_Simple() throws Exception {
        args.putStringArray(QUERY_ARG_SORT_COLUMNS, new String[] { "foo", "bar" });

        resolveQueryArgs(args, honored::add, Function.identity());
        assertTrue(honored.contains(QUERY_ARG_SORT_COLUMNS));
        assertFalse(honored.contains(QUERY_ARG_SQL_SORT_ORDER));
        assertEquals("foo, bar", args.getString(QUERY_ARG_SQL_SORT_ORDER));
    }

    @Test
    public void testResolveQueryArgs_SortOrder_Locale() throws Exception {
        args.putStringArray(QUERY_ARG_SORT_COLUMNS, new String[] { "foo", "bar" });
        args.putString(QUERY_ARG_SORT_LOCALE, "zh");
        args.putInt(QUERY_ARG_SORT_DIRECTION, QUERY_SORT_DIRECTION_ASCENDING);
        args.putInt(QUERY_ARG_SORT_COLLATION, java.text.Collator.IDENTICAL);
        args.putString(QUERY_ARG_SQL_SORT_ORDER, "raw");

        resolveQueryArgs(args, honored::add, Function.identity());
        assertTrue(honored.contains(QUERY_ARG_SORT_COLUMNS));
        assertTrue(honored.contains(QUERY_ARG_SORT_LOCALE));
        assertTrue(honored.contains(QUERY_ARG_SORT_DIRECTION));
        assertFalse(honored.contains(QUERY_ARG_SORT_COLLATION));
        assertFalse(honored.contains(QUERY_ARG_SQL_SORT_ORDER));
        assertEquals("foo, bar COLLATE zh ASC", args.getString(QUERY_ARG_SQL_SORT_ORDER));
    }

    @Test
    public void testResolveQueryArgs_SortOrder_Raw() throws Exception {
        args.putString(QUERY_ARG_SQL_SORT_ORDER, "raw");

        resolveQueryArgs(args, honored::add, Function.identity());
        assertTrue(honored.contains(QUERY_ARG_SQL_SORT_ORDER));
        assertEquals("raw", args.getString(QUERY_ARG_SQL_SORT_ORDER));
    }

    @Test
    public void testResolveQueryArgs_Limit() throws Exception {
        args.putInt(QUERY_ARG_LIMIT, 32);
        args.putInt(QUERY_ARG_OFFSET, 64);
        args.putString(QUERY_ARG_SQL_LIMIT, "raw");

        resolveQueryArgs(args, honored::add, Function.identity());
        assertTrue(honored.contains(QUERY_ARG_LIMIT));
        assertTrue(honored.contains(QUERY_ARG_OFFSET));
        assertFalse(honored.contains(QUERY_ARG_SQL_LIMIT));
        assertEquals("32 OFFSET 64", args.getString(QUERY_ARG_SQL_LIMIT));
    }

    @Test
    public void testResolveQueryArgs_Limit_Raw() throws Exception {
        args.putString(QUERY_ARG_SQL_LIMIT, "raw");

        resolveQueryArgs(args, honored::add, Function.identity());
        assertTrue(honored.contains(QUERY_ARG_SQL_LIMIT));
        assertEquals("raw", args.getString(QUERY_ARG_SQL_LIMIT));
    }

    @Test
    public void testRecoverAbusiveLimit_Uri() throws Exception {
        final Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendQueryParameter("limit", "32").build();

        recoverAbusiveLimit(uri, args);
        assertEquals("32", args.getString(QUERY_ARG_SQL_LIMIT));
    }

    @Test
    public void testRecoverAbusiveLimit_Args() throws Exception {
        final Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        args.putString(QUERY_ARG_SQL_LIMIT, "32");

        recoverAbusiveLimit(uri, args);
        assertEquals("32", args.getString(QUERY_ARG_SQL_LIMIT));
    }

    @Test
    public void testRecoverAbusiveLimit_Both() throws Exception {
        final Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendQueryParameter("limit", "32").build();
        args.putString(QUERY_ARG_SQL_LIMIT, "32");

        try {
            recoverAbusiveLimit(uri, args);
            fail("Expected IAE when conflicting limits defined");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testRecoverAbusiveGroupBy_Conflicting() throws Exception {
        // Abusive group is fine
        recoverAbusiveGroupBy(Pair.create("foo=bar GROUP BY foo", null));

        // Official group is fine
        recoverAbusiveGroupBy(Pair.create("foo=bar", "foo"));

        // Conflicting groups should yell
        try {
            recoverAbusiveGroupBy(Pair.create("foo=bar GROUP BY foo", "foo"));
            fail("Expected IAE when conflicting groups defined");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testRecoverAbusiveGroupBy_Buckets() throws Exception {
        final Pair<String, String> input = Pair.create(
                "(media_type = 1 OR media_type = 3) AND bucket_display_name IS NOT NULL AND bucket_id IS NOT NULL AND _data NOT LIKE \"%/DCIM/%\" ) GROUP BY (bucket_id",
                null);
        final Pair<String, String> expected = Pair.create(
                "((media_type = 1 OR media_type = 3) AND bucket_display_name IS NOT NULL AND bucket_id IS NOT NULL AND _data NOT LIKE \"%/DCIM/%\" )",
                "(bucket_id)");
        assertEquals(expected, recoverAbusiveGroupBy(input));
    }

    @Test
    public void testRecoverAbusiveGroupBy_BucketsByPath() throws Exception {
        final Pair<String, String> input = Pair.create(
                "_data LIKE ? AND _data IS NOT NULL) GROUP BY (bucket_id",
                null);
        final Pair<String, String> expected = Pair.create(
                "(_data LIKE ? AND _data IS NOT NULL)",
                "(bucket_id)");
        assertEquals(expected, recoverAbusiveGroupBy(input));
    }

    @Test
    public void testRecoverAbusiveGroupBy_113651872() throws Exception {
        final Pair<String, String> input = Pair.create(
                "(LOWER(SUBSTR(_data, -4))=? OR LOWER(SUBSTR(_data, -5))=? OR LOWER(SUBSTR(_data, -4))=?) AND LOWER(SUBSTR(_data, 1, 65))!=?) GROUP BY (bucket_id),(bucket_display_name",
                null);
        final Pair<String, String> expected = Pair.create(
                "((LOWER(SUBSTR(_data, -4))=? OR LOWER(SUBSTR(_data, -5))=? OR LOWER(SUBSTR(_data, -4))=?) AND LOWER(SUBSTR(_data, 1, 65))!=?)",
                "(bucket_id),(bucket_display_name)");
        assertEquals(expected, recoverAbusiveGroupBy(input));
    }

    @Test
    public void testRecoverAbusiveGroupBy_113652519() throws Exception {
        final Pair<String, String> input = Pair.create(
                "1) GROUP BY 1,(2",
                null);
        final Pair<String, String> expected = Pair.create(
                "(1)",
                "1,(2)");
        assertEquals(expected, recoverAbusiveGroupBy(input));
    }

    @Test
    public void testRecoverAbusiveGroupBy_113652519_longer() throws Exception {
        final Pair<String, String> input = Pair.create(
                "mime_type IN ( ?, ?, ? ) AND 1) GROUP BY 1,(2",
                null);
        final Pair<String, String> expected = Pair.create(
                "(mime_type IN ( ?, ?, ? ) AND 1)",
                "1,(2)");
        assertEquals(expected, recoverAbusiveGroupBy(input));
    }

    @Test
    public void testRecoverAbusiveGroupBy_115340326() throws Exception {
        final Pair<String, String> input = Pair.create(
                "(1) GROUP BY bucket_id,(bucket_display_name)",
                null);
        final Pair<String, String> expected = Pair.create(
                "(1)",
                "bucket_id,(bucket_display_name)");
        assertEquals(expected, recoverAbusiveGroupBy(input));
    }

    @Test
    public void testRecoverAbusiveGroupBy_116845885() throws Exception {
        final Pair<String, String> input = Pair.create(
                "(title like 'C360%' or title like 'getInstance%') group by ((datetaken+19800000)/86400000)",
                null);
        final Pair<String, String> expected = Pair.create(
                "(title like 'C360%' or title like 'getInstance%')",
                "((datetaken+19800000)/86400000)");
        assertEquals(expected, recoverAbusiveGroupBy(input));
    }

    @Test
    public void testRecoverAbusiveSortOrder_146482076() throws Exception {
        args.putString(QUERY_ARG_SQL_SORT_ORDER, "_id DESC LIMIT 200");

        recoverAbusiveSortOrder(args);
        assertEquals("_id DESC", args.getString(QUERY_ARG_SQL_SORT_ORDER));
        assertEquals("200", args.getString(QUERY_ARG_SQL_LIMIT));
    }

    @Test
    public void testMaybeBalance() throws Exception {
        assertEquals(null, maybeBalance(null));
        assertEquals("", maybeBalance(""));

        assertEquals("()", maybeBalance(")"));
        assertEquals("()", maybeBalance("("));
        assertEquals("()", maybeBalance("()"));

        assertEquals("(1==1)", maybeBalance("1==1)"));
        assertEquals("((foo)bar)baz", maybeBalance("foo)bar)baz"));
        assertEquals("foo(bar(baz))", maybeBalance("foo(bar(baz"));

        assertEquals("IN '('", maybeBalance("IN '('"));
        assertEquals("IN ('(')", maybeBalance("IN ('('"));
        assertEquals("IN (\")\")", maybeBalance("IN (\")\""));
        assertEquals("IN ('\"(')", maybeBalance("IN ('\"('"));
    }

    @Test
    public void testBindList() {
        assertEquals("()", DatabaseUtils.bindList());
        assertEquals("( 'foo' )", DatabaseUtils.bindList("foo"));
        assertEquals("( 'foo' , 'bar' )", DatabaseUtils.bindList("foo", "bar"));
        assertEquals("( 'foo' , 'bar' , 'baz' )", DatabaseUtils.bindList("foo", "bar", "baz"));
        assertEquals("( 'foo' , NULL , 42 )", DatabaseUtils.bindList("foo", null, 42));
    }

    private static Pair<String, String> recoverAbusiveGroupBy(
            Pair<String, String> selectionAndGroupBy) {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(QUERY_ARG_SQL_SELECTION, selectionAndGroupBy.first);
        queryArgs.putString(QUERY_ARG_SQL_GROUP_BY, selectionAndGroupBy.second);
        DatabaseUtils.recoverAbusiveSelection(queryArgs);
        return Pair.create(queryArgs.getString(QUERY_ARG_SQL_SELECTION),
                queryArgs.getString(QUERY_ARG_SQL_GROUP_BY));
    }
}
