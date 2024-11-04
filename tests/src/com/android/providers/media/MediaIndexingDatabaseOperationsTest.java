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

package com.android.providers.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.search.SearchUtilConstants;
import com.android.providers.media.search.exceptions.SqliteCheckedException;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class MediaIndexingDatabaseOperationsTest {

    private Context mContext;
    private IsolatedContext mIsolatedContext;
    private MediaIndexingDatabaseOperationsTestExtension mIndexingDatabaseOperations;
    private DatabaseHelper mDatabaseHelper;
    private SearchTestingUtils mSearchTestingUtils;
    private SearchUtilConstants mSearchUtilConstants;

    @BeforeClass
    public static void setUpBeforeClass() {
        // Permissions needed to insert files via the content resolver
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                        Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.WRITE_MEDIA_STORAGE);
    }

    @AfterClass
    public static void tearDownAfterClass() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mIsolatedContext = new IsolatedContext(mContext,
                /* tag */"MediaItemsIndexingDatabaseHelperTest", /*asFuseThread*/ false);
        mIndexingDatabaseOperations = new MediaIndexingDatabaseOperationsTestExtension(
                mIsolatedContext);
        mDatabaseHelper = mIsolatedContext.getExternalDatabase();
        mSearchTestingUtils = new SearchTestingUtils(mDatabaseHelper);
        mSearchUtilConstants = new SearchUtilConstants();
    }

    @Test
    public void testInsertMediaItemIntoMediaIndexingTable()
            throws SqliteCheckedException {
        String mediaIdToInsert = "1000";

        // Insert the mediaItemId into the status table
        ContentValues insertValues = new ContentValues();
        insertValues.put(mSearchUtilConstants.MEDIA_ID_COLUMN, mediaIdToInsert);

        mIndexingDatabaseOperations.insertIntoMediaStatusTable(
                mDatabaseHelper, List.of(insertValues));

        // Assert that the value was inserted and its processing status to be 0
        Cursor cursor = mSearchTestingUtils.getMediaItemDataFromStatusTable(
                Long.parseLong(mediaIdToInsert));
        if (cursor.moveToFirst()) {
            int metadataStatusIndex = cursor.getColumnIndexOrThrow(
                    mSearchUtilConstants.METADATA_PROCESSING_STATUS_COLUMN);
            int labelStatusIndex = cursor.getColumnIndexOrThrow(
                    mSearchUtilConstants.LABEL_PROCESSING_STATUS_COLUMN);
            int ocrStatusIndex = cursor.getColumnIndexOrThrow(
                    mSearchUtilConstants.OCR_PROCESSING_STATUS_COLUMN);
            int locationStatusIndex = cursor.getColumnIndexOrThrow(
                    mSearchUtilConstants.LOCATION_PROCESSING_STATUS_COLUMN);
            int metadataStatus = cursor.getInt(metadataStatusIndex);
            int locationStatus = cursor.getInt(locationStatusIndex);
            int ocrStatus = cursor.getInt(ocrStatusIndex);
            int labelStatus = cursor.getInt(labelStatusIndex);
            assertEquals(/* expectedProcessingStatus */ 0, metadataStatus);
            assertEquals(/* expectedProcessingStatus */ 0, locationStatus);
            assertEquals(/* expectedProcessingStatus */ 0, ocrStatus);
            assertEquals(/* expectedProcessingStatus */ 0, labelStatus);
        }
    }

    @Test
    public void testQueryExternalDatabaseTable() throws SqliteCheckedException {
        String mediaIdToQuery = "1000";
        ContentValues insertValues = new ContentValues();
        insertValues.put(mSearchUtilConstants.MEDIA_ID_COLUMN, mediaIdToQuery);

        String testSelection = mSearchUtilConstants.MEDIA_ID_COLUMN + " = ?";
        String[] testSelectionArgs = new String[]{ mediaIdToQuery };
        String[] columns = new String[] { mSearchUtilConstants.METADATA_PROCESSING_STATUS_COLUMN };
        // Insert the mediaItemId into the status table
        mIndexingDatabaseOperations.insertIntoMediaStatusTable(
                mDatabaseHelper, List.of(insertValues));

        // Query media indexing table
        Cursor cursor = mIndexingDatabaseOperations.queryExternalDatabaseTable(
                mDatabaseHelper, /* tableName */ mSearchUtilConstants.MEDIA_STATUS_TABLE,
                columns, testSelection, testSelectionArgs, null, null);

        // Assert on the retrieved cursor
        assertNotNull("Cursor was found to be null", cursor);
        assertEquals("Expected cursor size did not match",
                /* expected cursorSize */ 1, cursor.getCount());
        // Assert the status to be 0
        if (cursor.moveToFirst()) {
            assertEquals("testId processing status was expected to be 0",
                    /* expected processingStatus */ 0, cursor.getInt(0));
        }
    }

    @Test
    public void testUpdateStatusTableValues() throws SqliteCheckedException {
        String mediaIdToUpdate = "1000";
        ContentValues insertValues = new ContentValues();
        insertValues.put(mSearchUtilConstants.MEDIA_ID_COLUMN, mediaIdToUpdate);

        // Insert the mediaItemId into the status table
        mIndexingDatabaseOperations.insertIntoMediaStatusTable(
                mDatabaseHelper, List.of(insertValues));

        // Update the metadata indexing status column
        ContentValues updateValues = new ContentValues();
        updateValues.put(mSearchUtilConstants.METADATA_PROCESSING_STATUS_COLUMN, 1);
        String[] updateIds = new String[] { mediaIdToUpdate };
        mIndexingDatabaseOperations.updateStatusTableValues(
                mDatabaseHelper, updateIds, updateValues);

        // Assert that the value was updated to 1
        Cursor cursor = mSearchTestingUtils.getMediaItemDataFromStatusTable(
                Long.parseLong(mediaIdToUpdate));
        if (cursor.moveToFirst()) {
            int metadataStatusIndex = cursor.getColumnIndexOrThrow(
                    mSearchUtilConstants.METADATA_PROCESSING_STATUS_COLUMN);
            int metadataStatus = cursor.getInt(metadataStatusIndex);
            assertEquals(/* expected value after update */ 1, metadataStatus);
        }
    }

    @Test
    public void testDeleteMediaItemFromMediaIndexingTable()
            throws SqliteCheckedException {
        String mediaItemToDelete = "1000";
        String testSelection = mSearchUtilConstants.MEDIA_ID_COLUMN + " = ?";
        String[] testSelectionArgs = new String[]{ mediaItemToDelete };
        String[] columns = new String[] { mSearchUtilConstants.METADATA_PROCESSING_STATUS_COLUMN };
        ContentValues insertValues = new ContentValues();
        insertValues.put(mSearchUtilConstants.MEDIA_ID_COLUMN, mediaItemToDelete);

        // Insert the mediaItemId into the status table
        mIndexingDatabaseOperations.insertIntoMediaStatusTable(
                mDatabaseHelper, List.of(insertValues));

        // Delete the item from the indexing table
        mIndexingDatabaseOperations.deleteMediaItemFromMediaStatusTable(
                mDatabaseHelper, mediaItemToDelete);

        // Assert that the item does not exist in the indexing table
        Cursor cursor = mIndexingDatabaseOperations.queryExternalDatabaseTable(
                mDatabaseHelper, /* tableName */ mSearchUtilConstants.MEDIA_STATUS_TABLE,
                columns, testSelection, testSelectionArgs, null, null);
        assertEquals(/* expected cursorSize */ 0, cursor.getCount());
    }

    @Test
    public void testGetDatabaseHelper() {
        DatabaseHelper testHelper = mIndexingDatabaseOperations.getDatabaseHelper(mContext);
        assertNotNull(testHelper);
        assertTrue(testHelper.isExternal());
    }


    private static class MediaIndexingDatabaseOperationsTestExtension extends
            MediaIndexingDatabaseOperations {
        IsolatedContext mIsolatedContext;

        MediaIndexingDatabaseOperationsTestExtension(IsolatedContext context) {
            mIsolatedContext = context;
        }

        @Override
        public DatabaseHelper getDatabaseHelper(Context context) {
            return mIsolatedContext.getExternalDatabase();
        }

    }
}
