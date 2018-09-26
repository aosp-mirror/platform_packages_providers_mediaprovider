/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.providers.media;

import static com.android.providers.media.MediaProvider.getPathOwnerPackageName;
import static com.android.providers.media.MediaProvider.recoverAbusiveGroupBy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.support.test.runner.AndroidJUnit4;
import android.util.Pair;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class MediaProviderTest {
    @Test
    public void testPathOwnerPackageName_None() throws Exception {
        assertEquals(null, getPathOwnerPackageName(null));
        assertEquals(null, getPathOwnerPackageName("/data/path"));
    }

    @Test
    public void testPathOwnerPackageName_Emulated() throws Exception {
        assertEquals(null, getPathOwnerPackageName("/storage/emulated/0/DCIM/foo.jpg"));
        assertEquals(null, getPathOwnerPackageName("/storage/emulated/0/Android/"));
        assertEquals(null, getPathOwnerPackageName("/storage/emulated/0/Android/data/"));

        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/data/com.example/"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/data/com.example/foo.jpg"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/obb/com.example/foo.jpg"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/media/com.example/foo.jpg"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/sandbox/com.example/foo.jpg"));
    }

    @Test
    public void testPathOwnerPackageName_Portable() throws Exception {
        assertEquals(null, getPathOwnerPackageName("/storage/0000-0000/DCIM/foo.jpg"));

        assertEquals("com.example",
                getPathOwnerPackageName("/storage/0000-0000/Android/data/com.example/foo.jpg"));
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
                "(1) GROUP BY 1,(2)",
                null);
        final Pair<String, String> expected = Pair.create(
                "(1)",
                "1,(2)");
        assertEquals(expected, recoverAbusiveGroupBy(input));
    }

    @Test
    public void testRecoverAbusiveGroupBy_115340326() throws Exception {
        final Pair<String, String> input = Pair.create(
                "(1) GROUP BY bucket_id,(bucket_display_name)",
                null);
        final Pair<String,String> expected = Pair.create(
                "(1)",
                "bucket_id,(bucket_display_name)");
        assertEquals(expected, recoverAbusiveGroupBy(input));
    }

    @Test
    public void testGreylist() throws Exception {
        assertFalse(isGreylistMatch(
                "SELECT secret FROM other_table"));

        assertTrue(isGreylistMatch(
                "case when case when (date_added >= 157680000 and date_added < 1892160000) then date_added * 1000 when (date_added >= 157680000000 and date_added < 1892160000000) then date_added when (date_added >= 157680000000000 and date_added < 1892160000000000) then date_added / 1000 else 0 end > case when (date_modified >= 157680000 and date_modified < 1892160000) then date_modified * 1000 when (date_modified >= 157680000000 and date_modified < 1892160000000) then date_modified when (date_modified >= 157680000000000 and date_modified < 1892160000000000) then date_modified / 1000 else 0 end then case when (date_added >= 157680000 and date_added < 1892160000) then date_added * 1000 when (date_added >= 157680000000 and date_added < 1892160000000) then date_added when (date_added >= 157680000000000 and date_added < 1892160000000000) then date_added / 1000 else 0 end else case when (date_modified >= 157680000 and date_modified < 1892160000) then date_modified * 1000 when (date_modified >= 157680000000 and date_modified < 1892160000000) then date_modified when (date_modified >= 157680000000000 and date_modified < 1892160000000000) then date_modified / 1000 else 0 end end as corrected_added_modified"));
        assertTrue(isGreylistMatch(
                "MAX(case when (datetaken >= 157680000 and datetaken < 1892160000) then datetaken * 1000 when (datetaken >= 157680000000 and datetaken < 1892160000000) then datetaken when (datetaken >= 157680000000000 and datetaken < 1892160000000000) then datetaken / 1000 else 0 end)"));
        assertTrue(isGreylistMatch(
                "0 as orientation"));
        assertTrue(isGreylistMatch(
                "\"content://media/internal/audio/media\""));
    }

    @Test
    public void testGreylist_115845887() {
        assertTrue(isGreylistMatch(
                "MAX(*)"));
        assertTrue(isGreylistMatch(
                "MAX(_id)"));

        assertTrue(isGreylistMatch(
                "sum(column_name)"));
        assertFalse(isGreylistMatch(
                "SUM(foo+bar)"));

        assertTrue(isGreylistMatch(
                "count(column_name)"));
        assertFalse(isGreylistMatch(
                "count(other_table.column_name)"));
    }

    @Test
    public void testGreylist_116489751_116135586_116117120_116084561_116074030_116062802() {
        assertTrue(isGreylistMatch(
                "MAX(case when (date_added >= 157680000 and date_added < 1892160000) then date_added * 1000 when (date_added >= 157680000000 and date_added < 1892160000000) then date_added when (date_added >= 157680000000000 and date_added < 1892160000000000) then date_added / 1000 else 0 end)"));
    }

    @Test
    public void testGreylist_116699470() {
        assertTrue(isGreylistMatch(
                "MAX(case when (date_modified >= 157680000 and date_modified < 1892160000) then date_modified * 1000 when (date_modified >= 157680000000 and date_modified < 1892160000000) then date_modified when (date_modified >= 157680000000000 and date_modified < 1892160000000000) then date_modified / 1000 else 0 end)"));
    }

    @Test
    public void testGreylist_116531759() {
        assertTrue(isGreylistMatch(
                "count(*)"));
        assertTrue(isGreylistMatch(
                "COUNT(*)"));
        assertFalse(isGreylistMatch(
                "xCOUNT(*)"));
        assertTrue(isGreylistMatch(
                "count(*) AS image_count"));
        assertTrue(isGreylistMatch(
                "count(_id)"));
        assertTrue(isGreylistMatch(
                "count(_id) AS image_count"));

        assertTrue(isGreylistMatch(
                "column_a AS column_b"));
        assertFalse(isGreylistMatch(
                "other_table.column_a AS column_b"));
    }

    private static boolean isGreylistMatch(String raw) {
        for (Pattern p : MediaProvider.sGreylist) {
            if (p.matcher(raw).matches()) {
                return true;
            }
        }
        return false;
    }
}
