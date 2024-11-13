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

package com.android.providers.media.photopickersearch;

import static com.android.providers.media.photopickersearch.CloudMediaProviderSearch.MEDIA_PROJECTIONS;
import static com.android.providers.media.photopickersearch.CloudMediaProviderSearch.SEARCH_PROVIDER_FOR_PICKER_CLIENT_AUTHORITY;
import static com.android.providers.media.photopickersearch.CloudMediaProviderSearch.TEST_MEDIA_CATEGORY_ID;
import static com.android.providers.media.photopickersearch.CloudMediaProviderSearch.TEST_MEDIA_ID_FROM_SUGGESTED_SEARCH;
import static com.android.providers.media.photopickersearch.CloudMediaProviderSearch.TEST_MEDIA_ID_FROM_TEXT_SEARCH;
import static com.android.providers.media.photopickersearch.CloudMediaProviderSearch.TEST_MEDIA_ID_IN_MEDIA_SET;
import static com.android.providers.media.photopickersearch.CloudMediaProviderSearch.TEST_MEDIA_SET_ID;
import static com.android.providers.media.photopickersearch.CloudMediaProviderSearch.TEST_SEARCH_SUGGESTION_MEDIA_SET_ID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.CloudMediaProviderContract;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.sync.PickerSearchProviderClient;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RequiresFlagsEnabled(Flags.FLAG_CLOUD_MEDIA_PROVIDER_SEARCH)
@RunWith(AndroidJUnit4.class)
public class PickerSearchProviderClientTest {


    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private PickerSearchProviderClient mPickerSearchProviderClient;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mPickerSearchProviderClient =
                PickerSearchProviderClient.create(
                        mContext, SEARCH_PROVIDER_FOR_PICKER_CLIENT_AUTHORITY);
    }

    @Test
    public void testFetchSearchSuggestionsFromCmp() {
        Cursor cursor = mPickerSearchProviderClient.fetchSearchSuggestionsFromCmp("test",
                10,  null);
        cursor.moveToFirst();
        assertEquals(TEST_SEARCH_SUGGESTION_MEDIA_SET_ID, cursor.getString(cursor.getColumnIndex(
                CloudMediaProviderContract.SearchSuggestionColumns.MEDIA_SET_ID)));
        assertCursorColumns(cursor,
                CloudMediaProviderContract.SearchSuggestionColumns.ALL_PROJECTION);
    }

    @Test
    public void testFetchSuggestedSearchResultsFromCmp() {
        Cursor cursor = mPickerSearchProviderClient.fetchSearchResultsFromCmp(
                TEST_SEARCH_SUGGESTION_MEDIA_SET_ID, null, 1, 100,
                null, null);
        cursor.moveToFirst();
        assertEquals(TEST_MEDIA_ID_FROM_SUGGESTED_SEARCH, cursor.getString(cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.ID)));
        assertCursorColumns(cursor, MEDIA_PROJECTIONS);
    }

    @Test
    public void testFetchTextSearchResultsFromCmp() {
        Cursor cursor = mPickerSearchProviderClient.fetchSearchResultsFromCmp(
                null, "test", 1, 100,
                null, null);
        cursor.moveToFirst();
        assertEquals(TEST_MEDIA_ID_FROM_TEXT_SEARCH, cursor.getString(cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.ID)));
        assertCursorColumns(cursor, MEDIA_PROJECTIONS);
    }

    @Test
    public void testFetchMediasInMediaSetFromCmp() {
        Cursor cursor = mPickerSearchProviderClient.fetchMediasInMediaSetFromCmp(TEST_MEDIA_SET_ID,
                null);
        cursor.moveToFirst();
        assertEquals(TEST_MEDIA_ID_IN_MEDIA_SET, cursor.getString(cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.ID)));
        assertCursorColumns(cursor, MEDIA_PROJECTIONS);
    }


    @Test
    public void testFetchMediaCategoriesFromCmp() {
        Cursor cursor = mPickerSearchProviderClient.fetchMediaCategoriesFromCmp(null,
                null);
        cursor.moveToFirst();
        assertEquals(TEST_MEDIA_CATEGORY_ID, cursor.getString(
                cursor.getColumnIndex(CloudMediaProviderContract.MediaCategoryColumns.ID)));
        assertCursorColumns(cursor, CloudMediaProviderContract.MediaCategoryColumns.ALL_PROJECTION);
    }

    @Test
    public void testFetchMediaSetsFromCmp() {
        Cursor cursor = mPickerSearchProviderClient.fetchMediaSetsFromCmp(TEST_MEDIA_CATEGORY_ID,
                null);
        cursor.moveToFirst();
        assertEquals(TEST_MEDIA_SET_ID, cursor.getString(
                cursor.getColumnIndex(CloudMediaProviderContract.MediaSetColumns.ID)));
        assertCursorColumns(cursor, CloudMediaProviderContract.MediaSetColumns.ALL_PROJECTION);
    }

    private static void assertCursorColumns(Cursor cursor, String[] projections) {
        for (String columnName : projections) {
            assertTrue(cursor.getColumnIndex(columnName) >= 0);
        }
    }

}
