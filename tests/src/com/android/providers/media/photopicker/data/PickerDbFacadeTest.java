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

import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS;

import static com.android.providers.media.photopicker.util.PickerDbTestUtils.ALBUM_ID;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_3;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_4;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_PROVIDER;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.DATE_TAKEN_MS;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.GENERATION_MODIFIED;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.GIF_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.IMAGE_MIME_TYPES_QUERY;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.JPEG_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_3;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_4;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_PROVIDER;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.M4V_VIDEO_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.MP4_VIDEO_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.MPEG_VIDEO_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.PNG_IMAGE_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.SIZE_BYTES;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.STANDARD_MIME_TYPE_EXTENSION;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.VIDEO_MIME_TYPES_QUERY;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.WEBM_VIDEO_MIME_TYPE;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAddAlbumMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAddMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAllMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertCloudAlbumCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertCloudMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertMediaStoreCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertRemoveMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertResetAlbumMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertResetMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertWriteOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getAlbumMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getCloudMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getDeletedMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getLocalMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.queryAlbumMedia;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.queryMediaAll;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.provider.CloudMediaProviderContract.MediaColumns;
import android.provider.Column;
import android.provider.ExportedSince;
import android.provider.MediaStore.PickerMediaColumns;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.ProjectionHelper;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.sync.SyncTracker;
import com.android.providers.media.photopicker.sync.SyncTrackerRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

@RunWith(AndroidJUnit4.class)
public class PickerDbFacadeTest {
    private PickerDbFacade mFacade;
    private Context mContext;
    private ProjectionHelper mProjectionHelper;

    @Mock
    private SyncTracker mMockLocalSyncTracker;
    @Mock
    private SyncTracker mMockCloudSyncTracker;

    @Before
    public void setUp() {
        initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        mFacade = new PickerDbFacade(mContext, new PickerSyncLockManager(), LOCAL_PROVIDER);
        mFacade.setCloudProvider(CLOUD_PROVIDER);
        mProjectionHelper = new ProjectionHelper(Column.class, ExportedSince.class);


        // Inject mock trackers
        SyncTrackerRegistry.setLocalSyncTracker(mMockLocalSyncTracker);
        SyncTrackerRegistry.setCloudSyncTracker(mMockCloudSyncTracker);
    }

    @After
    public void tearDown() {
        if (mFacade != null) {
            mFacade.setCloudProvider(null);
        }

        // Reset mock trackers
        SyncTrackerRegistry.setLocalSyncTracker(new SyncTracker());
        SyncTrackerRegistry.setCloudSyncTracker(new SyncTracker());
    }

    @Test
    public void testAddLocalOnlyMedia() throws Exception {
        Cursor cursor1 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 2);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with cursor1 "
                            + "on LOCAL_PROVIDER.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 1);
        }

        // Test updating the same row
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after trying to update the same row with cursor2 "
                            + "on LOCAL_PROVIDER.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testAddCloudPlusLocal() throws Exception {
        Cursor cursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation on CLOUD_PROVIDER.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testAddCloudOnly() throws Exception {
        Cursor cursor1 = getCloudMediaCursor(CLOUD_ID, null, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getCloudMediaCursor(CLOUD_ID, null, DATE_TAKEN_MS + 2);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with cursor1 on "
                            + "CLOUD_PROVIDER.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 1);
        }

        // Test updating the same row
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after trying to update the same row with cursor2 "
                            + "on CLOUD_PROVIDER.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testAddLocalAndCloud_Dedupe() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 1);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with:\nlocalCursor having "
                            + "localId = " + LOCAL_ID + ", followed by\ncloudCursor having "
                            + "localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testAddCloudAndLocal_Dedupe() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with:\ncloudCursor having "
                            + "localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID + ", followed by"
                            + "\ncloudCursor having localId = " + LOCAL_ID)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 1);
        }
    }

    @Test
    public void testMediaSortOrder() {
        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_1, null, DATE_TAKEN_MS);
        final Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS + 1);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media on queryMediaAll() after adding 2 "
                            + "localMediaCursor and 1 cloudMediaCursor to "
                            + LOCAL_PROVIDER + " and " + CLOUD_PROVIDER + " respectively.")
                    .that(cr.getCount()).isEqualTo(/* expected= */ 3);

            cr.moveToFirst();
            // Latest items should show up first.
            assertCloudMediaCursor(cr, LOCAL_ID_2, DATE_TAKEN_MS + 1);

            cr.moveToNext();
            // If the date taken is the same for 2 or more items, they should be sorted in the order
            // of their insertion in the database with the latest row inserted first.
            assertCloudMediaCursor(cr, CLOUD_ID_1, DATE_TAKEN_MS);

            cr.moveToNext();
            assertCloudMediaCursor(cr, LOCAL_ID_1, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testAddLocalAlbumMedia() {
        Cursor cursor1 = getAlbumMediaCursor(LOCAL_ID, /* cloud id */ null, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getAlbumMediaCursor(LOCAL_ID, /* cloud id */ null, DATE_TAKEN_MS + 2);

        assertAddAlbumMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1, ALBUM_ID);

        try (Cursor cr = queryAlbumMedia(mFacade, ALBUM_ID, true)) {
            assertWithMessage(
                    "Unexpected number of albumMedia after adding albumMediaCursor having localId"
                            + " = "
                            + LOCAL_ID + " cloudId = " + null + " to " + LOCAL_PROVIDER)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 1);
        }

        // Test updating the same row. We always do a full sync for album media files.
        assertResetAlbumMediaOperation(mFacade, LOCAL_PROVIDER, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1, ALBUM_ID);

        try (Cursor cr = queryAlbumMedia(mFacade, ALBUM_ID, true)) {
            assertWithMessage(
                    "Unexpected number of albumMedia after resetting and updating the same row "
                            + "with albumMediaCursor having localId = "
                            + LOCAL_ID + " cloudId = " + null)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testAddCloudAlbumMedia() {
        Cursor cursor1 = getAlbumMediaCursor(/* local id */ null, CLOUD_ID, DATE_TAKEN_MS + 1);
        Cursor cursor2 = getAlbumMediaCursor(/* local id */ null, CLOUD_ID, DATE_TAKEN_MS + 2);

        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1, ALBUM_ID);

        try (Cursor cr = queryAlbumMedia(mFacade, ALBUM_ID, false)) {
            assertWithMessage(
                    "Unexpected number of albumMedia after adding albumMediaCursor having localId"
                            + " = "
                            + null + " cloudId = " + CLOUD_ID + " to " + CLOUD_PROVIDER)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 1);
        }

        // Test updating the same row. We always do a full sync for album media files.
        assertResetAlbumMediaOperation(mFacade, CLOUD_PROVIDER, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1, ALBUM_ID);

        try (Cursor cr = queryAlbumMedia(mFacade, ALBUM_ID, false)) {
            assertWithMessage(
                    "Unexpected number of albumMedia after resetting and updating the same row "
                    + "with albumMediaCursor having localId = "
                            + null + " cloudId = " + CLOUD_PROVIDER)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testAddCloudAlbumMediaWhileCloudSyncIsRunning() {


        doReturn(Collections.singletonList(new CompletableFuture<>()))
                .when(mMockCloudSyncTracker)
                .pendingSyncFutures();

        Cursor cursor1 = getAlbumMediaCursor(/* local id */ null, CLOUD_ID, DATE_TAKEN_MS + 1);

        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1, ALBUM_ID);

        try (Cursor cr = queryAlbumMedia(mFacade, ALBUM_ID, false)) {
            assertWithMessage(
                    "Unexpected number of albumMedia after adding albumMediaCursor having localId"
                    + " = "
                            + null + " cloudId = " + CLOUD_ID + " to " + CLOUD_PROVIDER)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 1);
        }

        // These files should also be in the media table since we're pretending that
        // we have a cloud sync running.
        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media on querying all media with cloud sync running.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 1);
        }
    }

    @Test
    public void testAddCloudAlbumMediaAvailableOnDevice() {
        // Add local row for a media item in media table.
        final Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);

        // Attempt to insert a media item available locally and on cloud in album_media table.
        final Cursor cloudCursor =
                getAlbumMediaCursor(LOCAL_ID, CLOUD_ID, DATE_TAKEN_MS + 1);
        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1, ALBUM_ID);

        // Assert that preference was given to the local media item over cloud media item at the
        // time of insertion in album_media table.
        try (Cursor albumCursor = queryAlbumMedia(mFacade, ALBUM_ID, false)) {
            assertWithMessage(
                    "Unexpected number of albumMedia on querying " + ALBUM_ID)
                    .that(albumCursor.getCount()).isEqualTo(1);
            albumCursor.moveToFirst();
            assertCloudMediaCursor(albumCursor, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testAddCloudAlbumMediaDeletedFromDevice() {
        // Attempt to insert a media item deleted from device and available on cloud in the
        // album_media table.
        final Cursor cloudCursor =
                getAlbumMediaCursor(LOCAL_ID, CLOUD_ID, DATE_TAKEN_MS);
        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1, ALBUM_ID);

        // Assert that cloud media metadata was inserted in the database as local_id points to a
        // deleted item.
        try (Cursor albumCursor = queryAlbumMedia(mFacade, ALBUM_ID, false)) {
            assertWithMessage(
                    "Unexpected number of albumMedia on querying " + ALBUM_ID)
                    .that(albumCursor.getCount()).isEqualTo(1);
            albumCursor.moveToFirst();
            assertCloudMediaCursor(albumCursor, CLOUD_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testAlbumMediaSortOrder() {
        final Cursor cursor1 = getAlbumMediaCursor(null, CLOUD_ID_1, DATE_TAKEN_MS);
        final Cursor cursor2 = getAlbumMediaCursor(LOCAL_ID_1, null, DATE_TAKEN_MS);
        final Cursor cursor3 = getAlbumMediaCursor(null, CLOUD_ID_2, DATE_TAKEN_MS + 1);

        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cursor1, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1, ALBUM_ID);

        try (Cursor cr = queryAlbumMedia(mFacade, ALBUM_ID, false)) {
            assertWithMessage(
                    "Unexpected number of media on queryMediaAll() after adding 2 "
                            + "cloudAlbumMediaCursor and 1 localAlbumMediaCursor to "
                            + CLOUD_PROVIDER + " and " + LOCAL_PROVIDER + " respectively.")
                    .that(cr.getCount()).isEqualTo(/* expected= */ 3);

            cr.moveToFirst();
            // Latest items should show up first.
            assertCloudMediaCursor(cr, CLOUD_ID_2, DATE_TAKEN_MS + 1);

            cr.moveToNext();
            // If the date taken is the same for 2 or more items, they should be sorted in the order
            // of their insertion in the database with the latest row inserted first.
            assertCloudMediaCursor(cr, LOCAL_ID_1, DATE_TAKEN_MS);

            cr.moveToNext();
            assertCloudMediaCursor(cr, CLOUD_ID_1, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testRemoveLocal() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with local media cursor "
                            + "localCursor.")
                    .that(cr.getCount()).isEqualTo(1);
        }

        assertRemoveMediaOperation(mFacade, LOCAL_PROVIDER, getDeletedMediaCursor(LOCAL_ID), 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after removeMediaOperation on local provider.")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testRemoveLocal_promote() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with one localCursor and "
                            + "one cloudCursor where "
                            + "\nlocalCursor has localId = " + LOCAL_ID
                            + "\ncloudCursor has localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertRemoveMediaOperation(mFacade, LOCAL_PROVIDER, getDeletedMediaCursor(LOCAL_ID), 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after removeMediaOperation on local provider.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testRemoveCloud() throws Exception {
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with cloud media cursor "
                            + "cloudCursor.")
                    .that(cr.getCount()).isEqualTo(1);
        }

        assertRemoveMediaOperation(mFacade, CLOUD_PROVIDER, getDeletedMediaCursor(CLOUD_ID), 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after removeMediaOperation on cloud provider.")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testRemoveCloud_promote() throws Exception {
        Cursor cloudCursor1 = getCloudMediaCursor(CLOUD_ID + "1", LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor2 = getCloudMediaCursor(CLOUD_ID + "2", LOCAL_ID, DATE_TAKEN_MS + 2);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with two cloudCursor where "
                            + "\ncloudCursor1 has localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID
                            + "1"
                            + "\ncloudCursor2 has localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID
                            + "2"
            )
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID + "1", DATE_TAKEN_MS + 1);
        }

        assertRemoveMediaOperation(mFacade, CLOUD_PROVIDER,
                getDeletedMediaCursor(CLOUD_ID + "1"), 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after removeMediaOperation on cloud provider.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID + "2", DATE_TAKEN_MS + 2);
        }
    }

    @Test
    public void testRemoveHidden() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with one localCursor and "
                            + "one cloudCursor where "
                            + "\nlocalCursor has localId = " + LOCAL_ID
                            + "\ncloudCursor has localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertRemoveMediaOperation(mFacade, CLOUD_PROVIDER, getDeletedMediaCursor(CLOUD_ID), 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after removeMediaOperation on cloud provider.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }
    }


    @Test
    public void testLocalUpdate() throws Exception {
        Cursor localCursor1 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor localCursor2 = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS + 2);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with two localCursor where "
                            + "\nlocalCursor1 has localId = " + LOCAL_ID
                            + "\nlocalCursor2 has localId = " + LOCAL_ID)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS + 2);
        }

        assertRemoveMediaOperation(mFacade, LOCAL_PROVIDER, getDeletedMediaCursor(LOCAL_ID), 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after removeMediaOperation on local provider.")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testCloudUpdate_withoutLocal() throws Exception {
        Cursor cloudCursor1 = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor2 = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 2);

        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor2, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with two cloudCursor where "
                            + "\ncloudCursor1 has localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID
                            + "\ncloudCursor2 has localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID
            )
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 2);
        }

        assertRemoveMediaOperation(mFacade, CLOUD_PROVIDER, getDeletedMediaCursor(CLOUD_ID), 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after removeMediaOperation on cloud provider.")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testCloudUpdate_withLocal() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor1 = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 1);
        Cursor cloudCursor2 = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 2);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor2, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with one localCursor and "
                            + "two cloudCursor, where \nlocalCursor has localId = "
                            + LOCAL_ID + "\ncloudCursor1 has localId = " + LOCAL_ID + ", cloudId = "
                            + CLOUD_ID + "\ncloudCursor1 has localId = " + LOCAL_ID + ", cloudId = "
                            + CLOUD_ID)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertRemoveMediaOperation(mFacade, LOCAL_PROVIDER, getDeletedMediaCursor(LOCAL_ID), 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after removeMediaOperation deleting media with "
                            + "localId ="
                            + LOCAL_ID + " from local provider.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS + 2);
        }

        assertRemoveMediaOperation(mFacade, CLOUD_PROVIDER, getDeletedMediaCursor(CLOUD_ID), 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after removeMediaOperation deleting media with "
                            + "cloudId ="
                            + CLOUD_ID + " from cloud provider.")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testRemoveMedia_withLatestDateTakenMillis() {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor1 = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS + 1);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor1, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with one localCursor and "
                            + "one cloudCursor where "
                            + "\nlocalCursor has localId = " + LOCAL_ID
                            + "\ncloudCursor1 has localId = " + LOCAL_ID + ", cloudId = "
                            + CLOUD_ID)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginRemoveMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, getDeletedMediaCursor(CLOUD_ID), /* writeCount */ 1);
            assertWithMessage(
                    "Unexpected value for the firstDateTakenMillis in the columns affected by DB "
                            + "write operation.")
                    .that(operation.getFirstDateTakenMillis()).isEqualTo(DATE_TAKEN_MS + 1);
            operation.setSuccess();
        }

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginRemoveMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, getDeletedMediaCursor(LOCAL_ID), /* writeCount */ 1);
            assertWithMessage(
                    "Unexpected value for the FirstDateTakenMillis in the columns affected by DB "
                            + "write operation.")
                    .that(operation.getFirstDateTakenMillis()).isEqualTo(DATE_TAKEN_MS);
            operation.setSuccess();
        }

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after removeMediaOperation on cloud provider then"
                            + " on local provider.")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testResetLocal() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        // Add two cloud_ids mapping to the same local_id to verify that
        // only one gets promoted
        Cursor cloudCursor1 = getCloudMediaCursor(CLOUD_ID + "1", LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor2 = getCloudMediaCursor(CLOUD_ID + "2", LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor2, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with one localCursor and "
                            + "two cloudCursor, where \nlocalCursor has localId = " + LOCAL_ID
                            + "\ncloudCursor1 has localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID
                            + "1"
                            + "\ncloudCursor1 has localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID
                            + "2")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertResetMediaOperation(mFacade, LOCAL_PROVIDER, null, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after resetMediaOperation on local provider.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();

            // Verify that local_id was deleted and either of cloudCursor1 or cloudCursor2
            // was promoted
            assertWithMessage("Failed to delete local_Id.")
                    .that(cr.getString(1)).isNotNull();
        }
    }

    @Test
    public void testResetCloud() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with one localCursor and "
                            + "one cloudCursor where "
                            + "\nlocalCursor has localId = " + LOCAL_ID
                            + "\ncloudCursor has localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID)
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        assertResetMediaOperation(mFacade, CLOUD_PROVIDER, null, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after resetMediaOperation on cloud provider.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testQueryWithDateTakenFilter() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, LOCAL_ID, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of media after addMediaOperation with one localCursor and "
                            + "one cloudCursor where "
                            + "\nlocalCursor has localId = " + LOCAL_ID
                            + "\ncloudCursor has localId = " + LOCAL_ID + ", cloudId = " + CLOUD_ID)
                    .that(cr.getCount()).isEqualTo(1);
        }

        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(5);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS - 1);
        qfbBefore.setId(5);
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of media with dateTakenBeforeMs set to DATE_TAKEN_MS - 1.")
                    .that(cr.getCount()).isEqualTo(0);
        }

        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(5);
        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS + 1);
        qfbAfter.setId(5);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage(
                    "Unexpected number of media with dateTakenAfterMs set to DATE_TAKEN_MS + 1.")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testQueryWithIdFilter() throws Exception {
        Cursor cursor1 = getLocalMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS);
        Cursor cursor2 = getLocalMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, cursor1, 1);
            assertWriteOperation(operation, cursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(5);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS);
        qfbBefore.setId(2);
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage("Unexpected number of media with Id set to 2.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + "1", DATE_TAKEN_MS);
        }

        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(5);
        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS);
        qfbAfter.setId(1);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage("Unexpected number of media with Id set to 1.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + "2", DATE_TAKEN_MS);
        }
    }

    @Test
    public void testQueryWithLimit() throws Exception {
        Cursor cursor1 = getLocalMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS);
        Cursor cursor2 = getCloudMediaCursor(CLOUD_ID + "2", null, DATE_TAKEN_MS);
        Cursor cursor3 = getLocalMediaCursor(LOCAL_ID + "3", DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(1);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of media with limit set to 1 and dateTakenBeforeMs set to "
                            + "DATE_TAKEN_MS + 1.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + "3", DATE_TAKEN_MS);
        }

        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(1);
        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage(
                    "Unexpected number of media with limit set to 1 and dateTakenAfterMs set to "
                            + "DATE_TAKEN_MS - 1.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + "3", DATE_TAKEN_MS);
        }

        try (Cursor cr = mFacade.queryMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1).build())) {
            assertWithMessage("Unexpected number of media with limit set to 1.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + "3", DATE_TAKEN_MS);
        }
    }

    @Test
    public void testQueryWithSizeFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);

        // Verify all
        PickerDbFacade.QueryFilterBuilder qfbAll = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAll.setSizeBytes(10);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertWithMessage("Unexpected number of media with sizeBytes set to 10.")
                    .that(cr.getCount()).isEqualTo(2);
        }

        qfbAll.setSizeBytes(1);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertWithMessage("Unexpected number of media with sizeBytes set to 1.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, MP4_VIDEO_MIME_TYPE);
        }

        // Verify after
        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setSizeBytes(10);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage(
                    "Unexpected number of media with sizeBytes set to 10 and dateTakenAfterMs set"
                            + " to DATE_TAKEN_MS - 1.")
                    .that(cr.getCount()).isEqualTo(2);
        }

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setSizeBytes(1);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage(
                    "Unexpected number of media with sizeBytes set to 1 and dateTakenAfterMs set "
                            + "to DATE_TAKEN_MS - 1.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, MP4_VIDEO_MIME_TYPE);
        }

        // Verify before
        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setSizeBytes(10);
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of media with sizeBytes set to 10 and dateTakenBeforeMs "
                            + "set to DATE_TAKEN_MS + 1.")
                    .that(cr.getCount()).isEqualTo(2);
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setSizeBytes(1);
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of media with sizeBytes set to 1 and dateTakenBeforeMs set"
                            + " to DATE_TAKEN_MS + 1.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, MP4_VIDEO_MIME_TYPE);
        }
    }

    @Test
    public void testQueryWithMimeTypesFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, WEBM_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor5 = getMediaCursor(CLOUD_ID_3, DATE_TAKEN_MS - 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor6 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS + 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor4, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor5, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor6, 1);

        // Verify all
        PickerDbFacade.QueryFilterBuilder qfbAll = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAll.setMimeTypes(new String[]{"*/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"*/*\"}")
                    .that(cr.getCount()).isEqualTo(6);
        }

        qfbAll.setMimeTypes(new String[]{"image/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"image/*\"}")
                    .that(cr.getCount()).isEqualTo(4);

            assertAllMediaCursor(cr,
                    new String[]{CLOUD_ID_2, CLOUD_ID_1, LOCAL_ID_2, CLOUD_ID_3},
                    new long[]{DATE_TAKEN_MS, DATE_TAKEN_MS, DATE_TAKEN_MS, DATE_TAKEN_MS - 1},
                    new String[]{GIF_IMAGE_MIME_TYPE, PNG_IMAGE_MIME_TYPE,
                            JPEG_IMAGE_MIME_TYPE, PNG_IMAGE_MIME_TYPE});
        }

        qfbAll.setMimeTypes(new String[]{"video/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"video/*\"}")
                    .that(cr.getCount()).isEqualTo(2);

            assertAllMediaCursor(cr,
                    new String[]{LOCAL_ID_3, LOCAL_ID_1},
                    new long[]{DATE_TAKEN_MS + 1, DATE_TAKEN_MS},
                    new String[]{MP4_VIDEO_MIME_TYPE, WEBM_VIDEO_MIME_TYPE});
        }

        // Verify after
        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{"image/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"image/*\"} "
                            + "and date taken after set to DATE_TAKEN_MS")
                    .that(cr.getCount()).isEqualTo(3);
        }

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{PNG_IMAGE_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to "
                            + "{PNG_IMAGE_MIME_TYPE} and date taken after set to DATE_TAKEN_MS - 1")
                    .that(cr.getCount()).isEqualTo(2);

            assertAllMediaCursor(cr,
                    new String[]{CLOUD_ID_1, CLOUD_ID_3},
                    new long[]{DATE_TAKEN_MS, DATE_TAKEN_MS - 1},
                    new String[]{PNG_IMAGE_MIME_TYPE, PNG_IMAGE_MIME_TYPE});
        }

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{"video/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"video/*\"} "
                            + "and date taken after set to DATE_TAKEN_MS - 1")
                    .that(cr.getCount()).isEqualTo(2);

            assertAllMediaCursor(cr,
                    new String[]{LOCAL_ID_3, LOCAL_ID_1},
                    new long[]{DATE_TAKEN_MS + 1, DATE_TAKEN_MS},
                    new String[]{MP4_VIDEO_MIME_TYPE, WEBM_VIDEO_MIME_TYPE});
        }

        // Verify before
        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{"*/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"*/*\"} and "
                            + "date taken before set to DATE_TAKEN_MS + 1")
                    .that(cr.getCount()).isEqualTo(5);
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{"video/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"video/*\"} "
                            + "and date taken before set to DATE_TAKEN_MS + 1")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID_1, DATE_TAKEN_MS, WEBM_VIDEO_MIME_TYPE);
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 2);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{"video/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"video/*\"} "
                            + "and date taken before set to DATE_TAKEN_MS + 2")
                    .that(cr.getCount()).isEqualTo(2);

            assertAllMediaCursor(cr,
                    new String[]{LOCAL_ID_3, LOCAL_ID_1},
                    new long[]{DATE_TAKEN_MS + 1, DATE_TAKEN_MS},
                    new String[]{MP4_VIDEO_MIME_TYPE, WEBM_VIDEO_MIME_TYPE});
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{PNG_IMAGE_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to "
                            + "{PNG_IMAGE_MIME_TYPE} and date taken before set to DATE_TAKEN_MS +"
                            + " 1")
                    .that(cr.getCount()).isEqualTo(2);

            assertAllMediaCursor(cr,
                    new String[]{CLOUD_ID_1, CLOUD_ID_3},
                    new long[]{DATE_TAKEN_MS, DATE_TAKEN_MS - 1},
                    new String[]{PNG_IMAGE_MIME_TYPE, PNG_IMAGE_MIME_TYPE});
        }
    }

    @Test
    public void testQueryWithMultipleMimeTypesFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, WEBM_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(LOCAL_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor3 = getMediaCursor(LOCAL_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor4 = getMediaCursor(CLOUD_ID_1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor5 = getMediaCursor(CLOUD_ID_2, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, GIF_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor6 = getMediaCursor(CLOUD_ID_3, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MPEG_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor7 = getMediaCursor(CLOUD_ID_4, DATE_TAKEN_MS - 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, PNG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor8 = getMediaCursor(LOCAL_ID_4, DATE_TAKEN_MS + 1, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor2, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor4, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor5, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor6, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor7, 1);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor8, 1);

        // Verify all
        PickerDbFacade.QueryFilterBuilder qfbAll = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAll.setMimeTypes(new String[]{"*/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"*/*\"}")
                    .that(cr.getCount()).isEqualTo(8);
        }

        qfbAll.setMimeTypes(new String[]{"image/*", PNG_IMAGE_MIME_TYPE, MP4_VIDEO_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"image/*\","
                            + "PNG_IMAGE_MIME_TYPE ,PNG_IMAGE_MIME_TYPE}")
                    .that(cr.getCount()).isEqualTo(6);
        }

        qfbAll.setMimeTypes(new String[]{GIF_IMAGE_MIME_TYPE, MPEG_VIDEO_MIME_TYPE,
                WEBM_VIDEO_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to "
                            + "{GIF_IMAGE_MIME_TYPE, MPEG_VIDEO_MIME_TYPE, WEBM_VIDEO_MIME_TYPE}")
                    .that(cr.getCount()).isEqualTo(3);

            assertAllMediaCursor(cr, new String[]{CLOUD_ID_3, CLOUD_ID_2, LOCAL_ID_1},
                    new long[]{DATE_TAKEN_MS, DATE_TAKEN_MS, DATE_TAKEN_MS}, new String[]{
                            MPEG_VIDEO_MIME_TYPE, GIF_IMAGE_MIME_TYPE, WEBM_VIDEO_MIME_TYPE});
        }

        // Verify after
        PickerDbFacade.QueryFilterBuilder qfbAfter = new PickerDbFacade.QueryFilterBuilder(1000);

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{"video/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"video/*\"} "
                            + "and date taken after set to DATE_TAKEN_MS - 1")
                    .that(cr.getCount()).isEqualTo(4);
        }

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{GIF_IMAGE_MIME_TYPE,
                MPEG_VIDEO_MIME_TYPE, WEBM_VIDEO_MIME_TYPE, M4V_VIDEO_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to "
                            + "{GIF_IMAGE_MIME_TYPE, MPEG_VIDEO_MIME_TYPE, WEBM_VIDEO_MIME_TYPE, "
                            + "M4V_VIDEO_MIME_TYPE} and date taken after set to DATE_TAKEN_MS - 1")
                    .that(cr.getCount()).isEqualTo(3);

            assertAllMediaCursor(cr, new String[]{CLOUD_ID_3, CLOUD_ID_2, LOCAL_ID_1},
                    new long[]{DATE_TAKEN_MS, DATE_TAKEN_MS, DATE_TAKEN_MS}, new String[]{
                            MPEG_VIDEO_MIME_TYPE, GIF_IMAGE_MIME_TYPE, WEBM_VIDEO_MIME_TYPE});
        }

        qfbAfter.setDateTakenAfterMs(DATE_TAKEN_MS - 1);
        qfbAfter.setId(0);
        qfbAfter.setMimeTypes(new String[]{GIF_IMAGE_MIME_TYPE, MP4_VIDEO_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbAfter.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to "
                            + "{GIF_IMAGE_MIME_TYPE, MP4_VIDEO_MIME_TYPE} and date taken after "
                            + "set to DATE_TAKEN_MS - 1")
                    .that(cr.getCount()).isEqualTo(3);
        }

        // Verify before
        PickerDbFacade.QueryFilterBuilder qfbBefore = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 1);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{"*/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"*/*\"} and "
                            + "date taken before set to DATE_TAKEN_MS + 1")
                    .that(cr.getCount()).isEqualTo(7);
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{"image/*"});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"image/*\"} "
                            + "and date taken before set to DATE_TAKEN_MS")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID_4, DATE_TAKEN_MS - 1, PNG_IMAGE_MIME_TYPE);
        }

        qfbBefore.setDateTakenBeforeMs(DATE_TAKEN_MS + 2);
        qfbBefore.setId(0);
        qfbBefore.setMimeTypes(new String[]{MP4_VIDEO_MIME_TYPE, GIF_IMAGE_MIME_TYPE});
        try (Cursor cr = mFacade.queryMediaForUi(qfbBefore.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to "
                            + "{MP4_VIDEO_MIME_TYPE, GIF_IMAGE_MIME_TYPE} and date taken before "
                            + "set to DATE_TAKEN_MS + 2")
                    .that(cr.getCount()).isEqualTo(3);

            assertAllMediaCursor(cr, new String[]{LOCAL_ID_4, CLOUD_ID_2, LOCAL_ID_3},
                    new long[]{DATE_TAKEN_MS + 1, DATE_TAKEN_MS, DATE_TAKEN_MS}, new String[]{
                            MP4_VIDEO_MIME_TYPE, GIF_IMAGE_MIME_TYPE, MP4_VIDEO_MIME_TYPE});
        }
    }

    @Test
    public void testQueryWithSizeAndMimeTypesFilter() throws Exception {
        Cursor cursor1 = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 2, WEBM_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cursor2 = getMediaCursor(CLOUD_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, /* sizeBytes */ 1, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cursor2, 1);

        // mime_type and size filter matches all
        PickerDbFacade.QueryFilterBuilder qfbAll = new PickerDbFacade.QueryFilterBuilder(1000);
        qfbAll.setMimeTypes(new String[]{"*/*"});
        qfbAll.setSizeBytes(10);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to {\"*/*\"} and size "
                            + "filter set to 10 bytes")
                    .that(cr.getCount()).isEqualTo(2);
        }

        // mime_type and size filter matches none
        qfbAll.setMimeTypes(new String[]{WEBM_VIDEO_MIME_TYPE});
        qfbAll.setSizeBytes(1);
        try (Cursor cr = mFacade.queryMediaForUi(qfbAll.build())) {
            assertWithMessage(
                    "Unexpected number of rows with mime_type filter set to "
                            + "{WEBM_VIDEO_MIME_TYPE} and size filter set to 1 byte")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testQueryMediaId() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, /* localId */ null, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1);

        // Assert all projection columns
        final String[] allProjection = mProjectionHelper.getProjectionMap(
                PickerMediaColumns.class).keySet().toArray(new String[0]);
        try (Cursor cr = mFacade.queryMediaIdForApps(PickerUriResolver.PICKER_SEGMENT,
                LOCAL_PROVIDER, LOCAL_ID, allProjection)) {
            assertWithMessage(
                    "Unexpected number of rows when asserting all projection columns with "
                            + "PickerUriResolver as PICKER_SEGMENT on local provider.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertMediaStoreCursor(cr, LOCAL_ID, DATE_TAKEN_MS, PickerUriResolver.PICKER_SEGMENT);
        }

        try (Cursor cr = mFacade.queryMediaIdForApps(PickerUriResolver.PICKER_GET_CONTENT_SEGMENT,
                LOCAL_PROVIDER, LOCAL_ID, allProjection)) {
            assertWithMessage(
                    "Unexpected number of rows when asserting all projection columns with "
                            + "PickerUriResolver as PICKER_GET_CONTENT_SEGMENT on local provider.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertMediaStoreCursor(cr, LOCAL_ID, DATE_TAKEN_MS,
                    PickerUriResolver.PICKER_GET_CONTENT_SEGMENT);
        }

        // Assert one projection column
        final String[] oneProjection = new String[]{PickerMediaColumns.DATE_TAKEN};

        try (Cursor cr = mFacade.queryMediaIdForApps(PickerUriResolver.PICKER_SEGMENT,
                CLOUD_PROVIDER, CLOUD_ID, oneProjection)) {
            assertWithMessage(
                    "Unexpected number of rows when asserting one projection column with cloud "
                            + "provider.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertWithMessage(
                    "Unexpected value of PickerMediaColumns.DATE_TAKEN with cloud provider.")
                    .that(cr.getLong(cr.getColumnIndexOrThrow(PickerMediaColumns.DATE_TAKEN)))
                    .isEqualTo(DATE_TAKEN_MS);
        }

        // Assert invalid projection column
        final String invalidColumn = "testInvalidColumn";
        final String[] invalidProjection = new String[]{
                PickerMediaColumns.DATE_TAKEN,
                invalidColumn
        };

        try (Cursor cr = mFacade.queryMediaIdForApps(PickerUriResolver.PICKER_SEGMENT,
                CLOUD_PROVIDER, CLOUD_ID, invalidProjection)) {
            assertWithMessage(
                    "Unexpected number of rows when asserting invalid projection column with "
                            + "cloud provider.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertWithMessage(
                    "Unexpected value of the invalidColumn with cloud provider.")
                    .that(cr.getLong(cr.getColumnIndexOrThrow(invalidColumn)))
                    .isEqualTo(0);
            assertWithMessage(
                    "Unexpected value of PickerMediaColumns.DATE_TAKEN with cloud provider.")
                    .that(cr.getLong(cr.getColumnIndexOrThrow(PickerMediaColumns.DATE_TAKEN)))
                    .isEqualTo(DATE_TAKEN_MS);
        }
    }

    /**
     * Tests {@link PickerDbFacade#queryMediaForUi(PickerDbFacade.QueryFilter)}
     * to ensure columns not required for the UI are not present.
     */
    @Test
    public void testQueryMediaForUi() throws Exception {

        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, /* localId */ null, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1);

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {

            assertWithMessage(
                    "Unexpected number of rows on queryMediaForUi.")
                    .that(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.WIDTH));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.HEIGHT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.ORIENTATION));

            cr.moveToNext();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.WIDTH));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.HEIGHT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.ORIENTATION));
        }
    }

    /**
     * Tests {@link PickerDbFacade#queryAlbumMediaForUi(PickerDbFacade.QueryFilter, String)} to
     * ensure columns not required for the UI are not present.
     */
    @Test
    public void testQueryAlbumMediaForUi() throws Exception {

        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, /* localId */ null, DATE_TAKEN_MS);

        assertAddAlbumMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1, ALBUM_ID);
        assertAddAlbumMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1, ALBUM_ID);

        PickerDbFacade.QueryFilterBuilder localQfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr =
                     mFacade.queryAlbumMediaForUi(
                             localQfb.setAlbumId(ALBUM_ID).build(), LOCAL_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on queryAlbumMediaForUi with local provider.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.WIDTH));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.HEIGHT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.ORIENTATION));
        }

        PickerDbFacade.QueryFilterBuilder cloudQfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr =
                     mFacade.queryAlbumMediaForUi(
                             cloudQfb.setAlbumId(ALBUM_ID).build(), CLOUD_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on queryAlbumMediaForUi with cloud provider.")
                    .that(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.WIDTH));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.HEIGHT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cr.getColumnIndexOrThrow(MediaColumns.ORIENTATION));
        }
    }

    @Test
    public void testSetCloudProvider() throws Exception {
        Cursor localCursor = getLocalMediaCursor(LOCAL_ID, DATE_TAKEN_MS);
        Cursor cloudCursor = getCloudMediaCursor(CLOUD_ID, null, DATE_TAKEN_MS);

        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, localCursor, 1);
        assertAddMediaOperation(mFacade, CLOUD_PROVIDER, cloudCursor, 1);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of rows on queryMediaAll with both local and cloud "
                            + "provider.")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS);

            cr.moveToNext();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        // Clearing the cloud provider hides cloud media
        mFacade.setCloudProvider(null);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of rows on queryMediaAll after hiding cloud provider.")
                    .that(cr.getCount()).isEqualTo(1);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }

        // Setting the cloud provider unhides cloud media
        mFacade.setCloudProvider(CLOUD_PROVIDER);

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage(
                    "Unexpected number of rows on queryMediaAll after un-hiding cloud provider.")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID, DATE_TAKEN_MS);

            cr.moveToNext();
            assertCloudMediaCursor(cr, LOCAL_ID, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testFavorites() throws Exception {
        Cursor localCursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor localCursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertWithMessage(
                    "Unexpected number of rows on queryMediaForUi with no filter.")
                    .that(cr.getCount()).isEqualTo(4);
        }

        qfb.setIsFavorite(true);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertWithMessage(
                    "Unexpected number of rows on queryMediaForUi with isFavorite filter set to "
                            + "true.")
                    .that(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, CLOUD_ID + 1, DATE_TAKEN_MS);

            cr.moveToNext();
            assertCloudMediaCursor(cr, LOCAL_ID + 1, DATE_TAKEN_MS);
        }
    }

    @Test
    public void testGetFavoritesAlbumWithoutFilter() throws Exception {
        Cursor localCursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor localCursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertWithMessage("Unexpected number of rows on queryMediaForUi without any filter.")
                    .that(cr.getCount()).isEqualTo(4);
        }

        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), CLOUD_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums without any filter for cloud "
                            + "provider.")
                    .that(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
            cr.moveToNext();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_VIDEOS,
                    ALBUM_ID_VIDEOS,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
        }
    }

    @Test
    public void testGetVideosAlbumWithMimeTypesFilter() throws Exception {
        Cursor localCursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor localCursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertWithMessage("Unexpected number of rows on queryMediaForUi without any filter.")
                    .that(cr.getCount()).isEqualTo(4);
        }

        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), CLOUD_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums without any filter for cloud "
                            + "provider.")
                    .that(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "2",
                    DATE_TAKEN_MS,
                    /* count */ 1);
            cr.moveToNext();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_VIDEOS,
                    ALBUM_ID_VIDEOS,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
        }

        qfb.setMimeTypes(new String[]{MP4_VIDEO_MIME_TYPE, JPEG_IMAGE_MIME_TYPE});
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), /* cloudProvider*/ CLOUD_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums without any filter for cloud "
                            + "provider.")
                    .that(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "2",
                    DATE_TAKEN_MS,
                    /* count */ 1);
            cr.moveToNext();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_VIDEOS,
                    ALBUM_ID_VIDEOS,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
        }

        qfb.setMimeTypes(new String[]{GIF_IMAGE_MIME_TYPE, JPEG_IMAGE_MIME_TYPE});
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), /* cloudProvider*/ CLOUD_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums with mime type filter set to "
                            + "{GIF_IMAGE_MIME_TYPE, JPEG_IMAGE_MIME_TYPE} for cloud provider.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "2",
                    DATE_TAKEN_MS,
                    /* count */ 1);
        }
    }

    @Test
    public void testGetFavoritesAlbumWithMimeTypesFilter() throws Exception {
        Cursor localCursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor localCursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertWithMessage("Unexpected number of rows on queryMediaForUi without any filter.")
                    .that(cr.getCount()).isEqualTo(4);
        }

        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), CLOUD_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums without any filter for cloud "
                            + "provider.")
                    .that(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
            cr.moveToNext();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_VIDEOS,
                    ALBUM_ID_VIDEOS,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
        }

        qfb.setMimeTypes(IMAGE_MIME_TYPES_QUERY);
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), /* cloudProvider*/ null)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums with mime type filter set to "
                            + "IMAGE_MIME_TYPES_QUERY and cloudProvider set to null.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    CLOUD_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 1);
        }

        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), CLOUD_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums with mime type filter set to "
                            + "{IMAGE_MIME_TYPES_QUERY} with cloudProvider.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    CLOUD_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 1);
        }

        qfb.setMimeTypes(VIDEO_MIME_TYPES_QUERY);
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), CLOUD_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums with mime type filter set to "
                            + "VIDEO_MIME_TYPES_QUERY with cloudProvider.")
                    .that(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 1);
            cr.moveToNext();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_VIDEOS,
                    ALBUM_ID_VIDEOS,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
        }

        qfb.setMimeTypes(new String[]{"foo"});
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), CLOUD_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums with mime type filter set to "
                            + "{\"foo\"} and not null cloudProvider.")
                    .that(cr.getCount()).isEqualTo(1);
        }
    }

    @Test
    public void testFetchLocalOnly() throws Exception {
        Cursor localCursor1 = getMediaCursor(LOCAL_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor localCursor2 = getMediaCursor(LOCAL_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor cloudCursor1 = getMediaCursor(CLOUD_ID + "1", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        Cursor cloudCursor2 = getMediaCursor(CLOUD_ID + "2", DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        // Item Info:
        // 2 items - local - one of them in favorite album
        // 2 items - cloud - one in favorite album, one in video album
        // Albums Info:
        // Videos     - Merged Album - 1 Video File (1 cloud)
        // Favorites  - Merged Album - 2 files (1 local + 1 cloud)

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertWriteOperation(operation, cloudCursor1, 1);
            assertWriteOperation(operation, cloudCursor2, 1);
            operation.setSuccess();
        }
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, localCursor1, 1);
            assertWriteOperation(operation, localCursor2, 1);
            operation.setSuccess();
        }

        PickerDbFacade.QueryFilterBuilder qfb =
                new PickerDbFacade.QueryFilterBuilder(/* limit */ 1000);
        // Verify that we see all(local + cloud) items.
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertWithMessage("Unexpected number of rows on queryMediaForUi without any filter.")
                    .that(cr.getCount()).isEqualTo(4);
        }

        // Verify that we only see local items with isLocalOnly=true
        qfb.setIsLocalOnly(true);
        try (Cursor cr = mFacade.queryMediaForUi(qfb.build())) {
            assertWithMessage(
                    "Unexpected number of rows on queryMediaForUi with isLocalOnly set to true.")
                    .that(cr.getCount()).isEqualTo(2);

            cr.moveToNext();
            assertWithMessage("Unexpected value of MediaColumns.ID at cursor.")
                    .that(cr.getString(cr.getColumnIndexOrThrow(MediaColumns.ID))).isEqualTo(
                            LOCAL_ID + "2");
            cr.moveToNext();
            assertWithMessage("Unexpected value of MediaColumns.ID at cursor.")
                    .that(cr.getString(cr.getColumnIndexOrThrow(MediaColumns.ID))).isEqualTo(
                            LOCAL_ID + "1");
        }

        // Verify that we see all available merged albums and their respective media count
        qfb.setIsLocalOnly(false);
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), CLOUD_PROVIDER)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums with isLocalOnly set to false.")
                    .that(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    CLOUD_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 2);
            cr.moveToNext();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_VIDEOS,
                    ALBUM_ID_VIDEOS,
                    CLOUD_ID + "2",
                    DATE_TAKEN_MS,
                    /* count */ 1);
        }

        qfb.setIsLocalOnly(true);
        // Verify that with isLocalOnly=true, we only see one album with only one local item.
        try (Cursor cr = mFacade.getMergedAlbums(qfb.build(), /* cloudProvider */ null)) {
            assertWithMessage(
                    "Unexpected number of rows on getMergedAlbums with isLocalOnly set to true "
                            + "and cloudProvider set to null.")
                    .that(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertCloudAlbumCursor(cr,
                    ALBUM_ID_FAVORITES,
                    ALBUM_ID_FAVORITES,
                    LOCAL_ID + "1",
                    DATE_TAKEN_MS,
                    /* count */ 1);
        }
    }

    @Test
    public void testDataColumn() throws Exception {
        Cursor imageCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, JPEG_IMAGE_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);
        Cursor videoCursor = getMediaCursor(LOCAL_ID + 1, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ false);

        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            assertWriteOperation(operation, imageCursor, 1);
            assertWriteOperation(operation, videoCursor, 1);
            operation.setSuccess();
        }

        try (Cursor cr = queryMediaAll(mFacade)) {
            assertWithMessage("Unexpected number of rows on queryMediaForUi.")
                    .that(cr.getCount()).isEqualTo(2);
            cr.moveToFirst();
            assertCloudMediaCursor(cr, LOCAL_ID + 1, MP4_VIDEO_MIME_TYPE);

            cr.moveToNext();
            assertCloudMediaCursor(cr, LOCAL_ID, JPEG_IMAGE_MIME_TYPE);
        }
    }

    @Test
    public void testAddMediaFailure() throws Exception {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(CLOUD_PROVIDER)) {
            assertThrows(Exception.class, () -> operation.execute(null /* cursor */));
        }
    }

    @Test
    public void testRemoveMediaFailure() throws Exception {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginRemoveMediaOperation(CLOUD_PROVIDER)) {
            assertThrows(Exception.class, () -> operation.execute(null /* cursor */));
        }
    }

    @Test
    public void testUpdateMediaSuccess() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            operation.execute(localCursor);
            operation.setSuccess();
        }

        try (PickerDbFacade.UpdateMediaOperation operation =
                     mFacade.beginUpdateMediaOperation(LOCAL_PROVIDER)) {
            ContentValues values = new ContentValues();
            values.put(PickerDbFacade.KEY_STANDARD_MIME_TYPE_EXTENSION,
                    MediaColumns.STANDARD_MIME_TYPE_EXTENSION_ANIMATED_WEBP);
            assertWithMessage("Failed to update media with LOCAL_ID.")
                    .that(operation.execute(LOCAL_ID, values)).isTrue();
            operation.setSuccess();
        }

        try (Cursor cursor = queryMediaAll(mFacade)) {
            assertWithMessage("Unexpected number of rows after update operation.")
                    .that(cursor.getCount()).isEqualTo(1);

            // Assert that STANDARD_MIME_TYPE_EXTENSION has been updated
            cursor.moveToFirst();
            assertWithMessage("Failed to update STANDARD_MIME_TYPE_EXTENSION.")
                    .that(cursor.getInt(cursor.getColumnIndexOrThrow(
                            MediaColumns.STANDARD_MIME_TYPE_EXTENSION)))
                    .isEqualTo(MediaColumns.STANDARD_MIME_TYPE_EXTENSION_ANIMATED_WEBP);
        }
    }

    @Test
    public void testUpdateMediaFailure() throws Exception {
        Cursor localCursor = getMediaCursor(LOCAL_ID, DATE_TAKEN_MS, GENERATION_MODIFIED,
                /* mediaStoreUri */ null, SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION, /* isFavorite */ true);
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(LOCAL_PROVIDER)) {
            operation.execute(localCursor);
            operation.setSuccess();
        }

        try (PickerDbFacade.UpdateMediaOperation operation =
                     mFacade.beginUpdateMediaOperation(LOCAL_PROVIDER)) {
            ContentValues values = new ContentValues();
            values.put(PickerDbFacade.KEY_STANDARD_MIME_TYPE_EXTENSION,
                    MediaColumns.STANDARD_MIME_TYPE_EXTENSION_ANIMATED_WEBP);
            assertWithMessage("Unexpected, should have failed to update media with CLOUD_ID.")
                    .that(operation.execute(CLOUD_ID, values)).isFalse();
            operation.setSuccess();
        }

        try (Cursor cursor = queryMediaAll(mFacade)) {
            assertWithMessage("Unexpected number of rows after update operation.")
                    .that(cursor.getCount()).isEqualTo(1);

            // Assert that STANDARD_MIME_TYPE_EXTENSION is same as before
            cursor.moveToFirst();
            assertWithMessage("Unexpected STANDARD_MIME_TYPE_EXTENSION, not same as before.")
                    .that(cursor.getInt(cursor.getColumnIndexOrThrow(
                            MediaColumns.STANDARD_MIME_TYPE_EXTENSION)))
                    .isEqualTo(STANDARD_MIME_TYPE_EXTENSION);
        }
    }
}
