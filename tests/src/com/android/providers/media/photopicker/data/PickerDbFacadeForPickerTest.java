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

package com.android.providers.media.photopicker.data;

import static com.android.providers.media.photopicker.data.PickerDbFacadeForPicker.KEY_CLOUD_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacadeForPicker.KEY_DATE_TAKEN_MS;
import static com.android.providers.media.photopicker.data.PickerDbFacadeForPicker.KEY_DURATION_MS;
import static com.android.providers.media.photopicker.data.PickerDbFacadeForPicker.KEY_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacadeForPicker.KEY_IS_VISIBLE;
import static com.android.providers.media.photopicker.data.PickerDbFacadeForPicker.KEY_LOCAL_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacadeForPicker.KEY_MIME_TYPE;
import static com.android.providers.media.photopicker.data.PickerDbFacadeForPicker.KEY_SIZE_BYTES;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PickerDbFacadeForPickerTest {
    private static final String TAG = "PickerDbFacadeForPickerTest";

    private static final String TEST_PICKER_DB = "test_picker";
    private static final String MEDIA_TABLE = "media";

    private static final long SIZE_BYTES = 7000;
    private static final long DATE_TAKEN_MS = 1623852851911L;
    private static final long DURATION_MS = 5;
    private static final String LOCAL_ID = "50";
    private static final String MEDIA_STORE_URI = "content://media/external/file/" + LOCAL_ID;
    private static final String CLOUD_ID = "asdfghjkl;";
    private static final String MIME_TYPE = "video/mp4";

    private static Context sIsolatedContext;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, TAG, /*asFuseThread*/ false);
    }

    @Test
    public void testAddLocalOnly() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
            Cursor cursor2 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 2);
            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(cursor1)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS + 1);
            }

            // Test updating the same row
            assertThat(facade.addLocalMedia(cursor2)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS + 2);
            }
        }
    }

    @Test
    public void testAddCloudPlusLocal() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);
            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addCloudMedia(cursor)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);
            }
        }
    }

    @Test
    public void testAddCloudOnly() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cursor1 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 1,
                    /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);
            Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 2,
                    /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addCloudMedia(cursor1)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, CLOUD_ID, /* localId */ null, DATE_TAKEN_MS + 1);
            }

            // Test updating the same row
            assertThat(facade.addCloudMedia(cursor2)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, CLOUD_ID, /* localId */ null, DATE_TAKEN_MS + 2);
            }
        }
    }

    @Test
    public void testAddLocalAndCloud_Dedupe() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
            Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 1);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(localCursor)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cloudCursor)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
            }
        }
    }

    @Test
    public void testAddCloudAndLocal_Dedupe() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
            Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addCloudMedia(cloudCursor)).isEqualTo(1);
            assertThat(facade.addLocalMedia(localCursor)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS + 1);
            }
        }
    }

    @Test
    public void testRemoveLocal() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(localCursor)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
            }

            assertThat(facade.removeLocalMedia(getDeletedMediaCursor(LOCAL_ID), 0))
                    .isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testRemoveLocal_promote() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
            Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(localCursor)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cloudCursor)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
                assertThat(cr.getString(1)).isNull();
            }

            assertThat(facade.removeLocalMedia(getDeletedMediaCursor(LOCAL_ID), 0))
                    .isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
                assertThat(cr.getString(1)).isEqualTo(CLOUD_ID);
            }
        }
    }

    @Test
    public void testRemoveCloud() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addCloudMedia(cloudCursor)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
            }

            assertThat(facade.removeCloudMedia(getDeletedMediaCursor(CLOUD_ID), 0))
                    .isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testRemoveCloud_promote() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS);
            Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addCloudMedia(cloudCursor1)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cloudCursor2)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
                assertThat(cr.getString(1)).isEqualTo(CLOUD_ID + 1);
            }

            assertThat(facade.removeCloudMedia(getDeletedMediaCursor(CLOUD_ID + "1"), 0))
                    .isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
                assertThat(cr.getString(1)).isEqualTo(CLOUD_ID + "2");
            }
        }
    }

    @Test
    public void testRemoveHidden() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
            Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addCloudMedia(cloudCursor)).isEqualTo(1);
            assertThat(facade.addLocalMedia(localCursor)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
                assertThat(cr.getString(1)).isNull();
            }

            assertThat(facade.removeCloudMedia(getDeletedMediaCursor(CLOUD_ID), 0))
                    .isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
                assertThat(cr.getString(1)).isNull();
            }
        }
    }


    @Test
    public void testLocalUpdate() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor localCursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
            Cursor localCursor2 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 2);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(localCursor1)).isEqualTo(1);
            assertThat(facade.addLocalMedia(localCursor2)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS + 2);
            }

            assertThat(facade.removeLocalMedia(getDeletedMediaCursor(LOCAL_ID), 0))
                    .isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testCloudUpdate_withoutLocal() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cloudCursor1 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 1);
            Cursor cloudCursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 2);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addCloudMedia(cloudCursor1)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cloudCursor2)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 2);
            }

            assertThat(facade.removeCloudMedia(getDeletedMediaCursor(CLOUD_ID), 0))
                    .isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testCloudUpdate_withLocal() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
            Cursor cloudCursor1 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 1);
            Cursor cloudCursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS + 2);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(localCursor)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cloudCursor1)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cloudCursor2)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
            }

            assertThat(facade.removeLocalMedia(getDeletedMediaCursor(LOCAL_ID), 0))
                    .isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 2);
            }

            assertThat(facade.removeCloudMedia(getDeletedMediaCursor(CLOUD_ID), 0))
                    .isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testResetLocal() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
            // Add two cloud_ids mapping to the same local_id to verify that
            // only one gets promoted
            Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS);
            Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(localCursor)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cloudCursor1)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cloudCursor2)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
            }

            assertThat(facade.resetMedia(/* isLocal */ true)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();

                // Verify that local_id was deleted and either of cloudCursor1 or cloudCursor2
                // was promoted
                assertThat(cr.getString(1)).isNotNull();
            }
        }
    }

    @Test
    public void testResetCloud() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
            Cursor cloudCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(localCursor)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cloudCursor)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
            }

            assertThat(facade.resetMedia(/* isLocal */ false)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
                cr.moveToFirst();
                assertCursor(cr, /* cloudId */ null, LOCAL_ID, DATE_TAKEN_MS);
            }
        }
    }

    @Test
    public void testQueryWithDateTakenFilter() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
            Cursor cloudCursor = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(localCursor)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cloudCursor)).isEqualTo(1);

            try (Cursor cr = queryMediaAll(facade)) {
                assertThat(cr.getCount()).isEqualTo(1);
            }

            try (Cursor cr = facade.queryMediaBefore(DATE_TAKEN_MS - 1,
                            /* id */ 5, /* limit */ 5, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(0);
            }

            try (Cursor cr = facade.queryMediaAfter(DATE_TAKEN_MS + 1,
                            /* id */ 5, /* limit */ 5, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryWithIdFilter() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);
            Cursor cursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(cursor1)).isEqualTo(1);
            assertThat(facade.addLocalMedia(cursor2)).isEqualTo(1);

            try (Cursor cr = facade.queryMediaBefore(DATE_TAKEN_MS,
                            /* id */ 2, /* limit */ 5, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID + "1");
            }

            try (Cursor cr = facade.queryMediaAfter(DATE_TAKEN_MS,
                            /* id */ 1, /* limit */ 5, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID + "2");
            }
        }
    }

    @Test
    public void testQueryWithLimit() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);
            Cursor cursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);
            Cursor cursor3 = getMediaCursor(LOCAL_ID + "3", DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, SIZE_BYTES, MIME_TYPE);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(cursor1)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cursor2)).isEqualTo(1);
            assertThat(facade.addLocalMedia(cursor3)).isEqualTo(1);

            try (Cursor cr = facade.queryMediaBefore(DATE_TAKEN_MS + 1, /* id */ 0,
                            /* limit */ 1, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID + "3");
            }

            try (Cursor cr = facade.queryMediaAfter(DATE_TAKEN_MS - 1, /* id */ 0,
                            /* limit */ 1, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID + "3");
            }

            try (Cursor cr = facade.queryMediaAll(/* limit */ 1, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID + "3");
            }
        }
    }

    @Test
    public void testQueryWithSizeFilter() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, /* sizeBytes */ 1, MIME_TYPE);
            Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, /* sizeBytes */ 2, MIME_TYPE);

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(cursor1)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cursor2)).isEqualTo(1);

            // Verify all
            try (Cursor cr = facade.queryMediaAll(/* limit */ 1000, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 10)) {
                assertThat(cr.getCount()).isEqualTo(2);
            }
            try (Cursor cr = facade.queryMediaAll(/* limit */ 1000, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 1)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
            }

            // Verify after
            try (Cursor cr = facade.queryMediaAfter(DATE_TAKEN_MS - 1, /* id */ 0,
                            /* limit */ 1000, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 10)) {
                assertThat(cr.getCount()).isEqualTo(2);
            }
            try (Cursor cr = facade.queryMediaAfter(DATE_TAKEN_MS - 1, /* id */ 0,
                            /* limit */ 1000, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 1)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
            }

            // Verify before
            try (Cursor cr = facade.queryMediaBefore(DATE_TAKEN_MS + 1, /* id */ 0,
                            /* limit */ 1000, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 10)) {
                assertThat(cr.getCount()).isEqualTo(2);
            }
            try (Cursor cr = facade.queryMediaBefore(DATE_TAKEN_MS + 1, /* id */ 0,
                            /* limit */ 1000, /* mimeTypeFilter */ null,
                            /* sizeBytesMax */ 1)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
            }
        }
    }

    @Test
    public void testQueryWithMimeTypeFilter() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, SIZE_BYTES, "video/webm");
            Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, SIZE_BYTES, "video/mp4");

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(cursor1)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cursor2)).isEqualTo(1);

            // Verify all
            try (Cursor cr = facade.queryMediaAll(/* limit */ 1000, "*/*",
                            /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(2);
            }
            try (Cursor cr = facade.queryMediaAll(/* limit */ 1000, "video/mp4",
                            /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(1)).isEqualTo(CLOUD_ID);
            }

            // Verify after
            try (Cursor cr = facade.queryMediaAfter(DATE_TAKEN_MS - 1, /* id */ 0,
                            /* limit */ 1000, "video/*", /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(2);
            }
            try (Cursor cr = facade.queryMediaAfter(DATE_TAKEN_MS - 1, /* id */ 0,
                            /* limit */ 1000, "video/webm", /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(0)).isEqualTo(LOCAL_ID);
            }

            // Verify before
            try (Cursor cr = facade.queryMediaBefore(DATE_TAKEN_MS + 1, /* id */ 0,
                            /* limit */ 1000, "video/*", /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(2);
            }
            try (Cursor cr = facade.queryMediaBefore(DATE_TAKEN_MS + 1, /* id */ 0,
                            /* limit */ 1000, "video/mp4", /* sizeBytesMax */ 0)) {
                assertThat(cr.getCount()).isEqualTo(1);

                cr.moveToFirst();
                assertThat(cr.getString(1)).isEqualTo(CLOUD_ID);
            }
        }
    }

    @Test
    public void testQueryWithSizeAndMimeTypeFilter() throws Exception {
        try (PickerDatabaseHelper helper = new PickerDatabaseHelper(sIsolatedContext)) {
            Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, /* sizeBytes */ 2, "video/webm");
            Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS,
                    /* mediaStoreUri */ null, /* sizeBytes */ 1, "video/mp4");

            PickerDbFacadeForPicker facade =
                    new PickerDbFacadeForPicker(helper.getWritableDatabase());

            assertThat(facade.addLocalMedia(cursor1)).isEqualTo(1);
            assertThat(facade.addCloudMedia(cursor2)).isEqualTo(1);

            // mime_type and size filter matches all
            try (Cursor cr = facade.queryMediaAll(/* limit */ 1000, "*/*",
                            /* sizeBytesMax */ 10)) {
                assertThat(cr.getCount()).isEqualTo(2);
            }

            // mime_type and size filter matches none
            try (Cursor cr = facade.queryMediaAll(/* limit */ 1000, "video/webm",
                            /* sizeBytesMax */ 1)) {
                assertThat(cr.getCount()).isEqualTo(0);
            }
        }
    }

    private static Cursor queryMediaAll(PickerDbFacadeForPicker facade) {
        return facade.queryMediaAll(/* limit */ 1000, /* mimeTypeFilter */ null,
                /* sizeBytesMax */ 0);
    }

    // TODO(b/190713331): s/id/CloudMediaProviderContract#MediaColumns#ID/
    private static Cursor getDeletedMediaCursor(String id) {
        MatrixCursor c =
                new MatrixCursor(new String[] {"id"});
        c.addRow(new String[] {id});
        return c;
    }

    // TODO(b/190713331): Use CloudMediaProviderContract#MediaColumns
    private static Cursor getMediaCursor(String id, long dateTakenMs, String mediaStoreUri,
            long sizeBytes, String mimeType) {
        String[] projectionKey = new String[] {
            "id",
            "media_store_uri",
            "date_taken_ms",
            "size_bytes",
            "mime_type",
            "duration_ms"
        };

        String[] projectionValue = new String[] {
            id,
            mediaStoreUri,
            String.valueOf(dateTakenMs),
            String.valueOf(sizeBytes),
            mimeType,
            String.valueOf(DURATION_MS)
        };

        MatrixCursor c = new MatrixCursor(projectionKey);
        c.addRow(projectionValue);
        return c;
    }

    private static Cursor getMediaCursor(String id, long dateTakenMs) {
        return getMediaCursor(id, dateTakenMs, MEDIA_STORE_URI, SIZE_BYTES, MIME_TYPE);
    }

    private static void assertCursor(Cursor cursor, String cloudId, String localId,
            long dateTakenMs) {
        assertThat(cursor.getString(cursor.getColumnIndex(KEY_LOCAL_ID))).isEqualTo(localId);
        assertThat(cursor.getString(cursor.getColumnIndex(KEY_CLOUD_ID))).isEqualTo(cloudId);
        assertThat(cursor.getLong(cursor.getColumnIndex(KEY_DATE_TAKEN_MS))).isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(cursor.getColumnIndex(KEY_SIZE_BYTES))).isEqualTo(SIZE_BYTES);
        assertThat(cursor.getLong(cursor.getColumnIndex(KEY_DURATION_MS))).isEqualTo(DURATION_MS);
        assertThat(cursor.getString(cursor.getColumnIndex(KEY_MIME_TYPE))).isEqualTo(MIME_TYPE);
    }
}
