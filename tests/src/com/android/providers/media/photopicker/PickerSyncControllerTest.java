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

package com.android.providers.media.photopicker;

import static com.android.providers.media.PickerProviderMediaGenerator.MediaGenerator;
import static com.android.providers.media.PickerUriResolver.REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI;
import static com.android.providers.media.photopicker.NotificationContentObserver.MEDIA;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Process;
import android.os.storage.StorageManager;
import android.provider.CloudMediaProviderContract.MediaColumns;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.PickerProviderMediaGenerator;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.photopicker.data.CloudProviderInfo;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.util.exceptions.UnableToAcquireLockException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class PickerSyncControllerTest {
    private static final String LOCAL_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.local";
    private static final String FLAKY_CLOUD_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.cloud_flaky";
    private static final String CLOUD_PRIMARY_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.cloud_primary";
    private static final String CLOUD_SECONDARY_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.cloud_secondary";
    private static final String PACKAGE_NAME = "com.android.providers.media.tests";

    private final MediaGenerator mLocalMediaGenerator =
            PickerProviderMediaGenerator.getMediaGenerator(LOCAL_PROVIDER_AUTHORITY);
    private final MediaGenerator mCloudPrimaryMediaGenerator =
            PickerProviderMediaGenerator.getMediaGenerator(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
    private final MediaGenerator mCloudSecondaryMediaGenerator =
            PickerProviderMediaGenerator.getMediaGenerator(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
    private final MediaGenerator mCloudFlakyMediaGenerator =
            PickerProviderMediaGenerator.getMediaGenerator(FLAKY_CLOUD_PROVIDER_AUTHORITY);

    private static final String LOCAL_ID_1 = "1";
    private static final String LOCAL_ID_2 = "2";

    private static final String CLOUD_ID_1 = "1";
    private static final String CLOUD_ID_2 = "2";
    private static final String CLOUD_ID_3 = "3";
    private static final String CLOUD_ID_4 = "4";
    private static final String CLOUD_ID_5 = "5";
    private static final String CLOUD_ID_6 = "6";
    private static final String CLOUD_ID_7 = "7";
    private static final String CLOUD_ID_8 = "8";
    private static final String CLOUD_ID_9 = "9";
    private static final String CLOUD_ID_10 = "10";
    private static final String CLOUD_ID_11 = "11";
    private static final String CLOUD_ID_12 = "12";
    private static final String CLOUD_ID_13 = "13";
    private static final String CLOUD_ID_14 = "14";

    private static final String ALBUM_ID_1 = "1";
    private static final String ALBUM_ID_2 = "2";

    private static final Pair<String, String> LOCAL_ONLY_1 = Pair.create(LOCAL_ID_1, null);
    private static final Pair<String, String> LOCAL_ONLY_2 = Pair.create(LOCAL_ID_2, null);
    private static final Pair<String, String> CLOUD_ONLY_1 = Pair.create(null, CLOUD_ID_1);
    private static final Pair<String, String> CLOUD_ONLY_2 = Pair.create(null, CLOUD_ID_2);
    private static final Pair<String, String> CLOUD_ONLY_3 = Pair.create(null, CLOUD_ID_3);
    private static final Pair<String, String> CLOUD_ONLY_4 = Pair.create(null, CLOUD_ID_4);
    private static final Pair<String, String> CLOUD_ONLY_5 = Pair.create(null, CLOUD_ID_5);
    private static final Pair<String, String> CLOUD_ONLY_6 = Pair.create(null, CLOUD_ID_6);
    private static final Pair<String, String> CLOUD_ONLY_7 = Pair.create(null, CLOUD_ID_7);
    private static final Pair<String, String> CLOUD_ONLY_8 = Pair.create(null, CLOUD_ID_8);
    private static final Pair<String, String> CLOUD_ONLY_9 = Pair.create(null, CLOUD_ID_9);
    private static final Pair<String, String> CLOUD_ONLY_10 = Pair.create(null, CLOUD_ID_10);
    private static final Pair<String, String> CLOUD_ONLY_11 = Pair.create(null, CLOUD_ID_11);
    private static final Pair<String, String> CLOUD_ONLY_12 = Pair.create(null, CLOUD_ID_12);
    private static final Pair<String, String> CLOUD_ONLY_13 = Pair.create(null, CLOUD_ID_13);
    private static final Pair<String, String> CLOUD_ONLY_14 = Pair.create(null, CLOUD_ID_14);
    private static final Pair<String, String> CLOUD_AND_LOCAL_1
            = Pair.create(LOCAL_ID_1, CLOUD_ID_1);

    private static final String COLLECTION_1 = "1";
    private static final String COLLECTION_2 = "2";

    private static final int DB_VERSION_1 = 1;
    private static final int DB_VERSION_2 = 2;
    private static final String DB_NAME = "test_db";

    private Context mContext;
    private TestConfigStore mConfigStore;
    private PickerDbFacade mFacade;
    private PickerSyncController mController;
    private PickerSyncLockManager mLockManager;

    @Before
    public void setUp() {
        mLocalMediaGenerator.resetAll();
        mCloudPrimaryMediaGenerator.resetAll();
        mCloudSecondaryMediaGenerator.resetAll();
        mCloudFlakyMediaGenerator.resetAll();

        mLocalMediaGenerator.setMediaCollectionId(COLLECTION_1);
        mCloudPrimaryMediaGenerator.setMediaCollectionId(COLLECTION_1);
        mCloudSecondaryMediaGenerator.setMediaCollectionId(COLLECTION_1);
        mCloudFlakyMediaGenerator.setMediaCollectionId(COLLECTION_1);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Delete db so it's recreated on next access and previous test state is cleared
        final File dbPath = mContext.getDatabasePath(DB_NAME);
        dbPath.delete();

        mLockManager = new PickerSyncLockManager();

        PickerDatabaseHelper dbHelper = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        mFacade = new PickerDbFacade(mContext, mLockManager, LOCAL_PROVIDER_AUTHORITY, dbHelper);

        mConfigStore = new TestConfigStore();
        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);

        mController = PickerSyncController.initialize(
                mContext, mFacade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        // Set cloud provider to null to avoid trying to sync it during other tests
        // that might be using an IsolatedContext
        setCloudProviderAndSyncAllMedia(null);

        // The above method sets the cloud provider state as UNSET.
        // Set the state as NOT_SET instead by clearing the persisted cloud provider authority.
        mController.clearPersistedCloudProviderAuthority();
    }

    @After
    public void tearDown() {
        if (mConfigStore != null) {
            mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);
        }
        if (mController != null) {
            // Reset the cloud provider state to default
            mController.setCloudProvider(/* authority */ null);
            mController.clearPersistedCloudProviderAuthority();
        }
    }

    @Test
    public void testInitCloudProviderOnDeviceConfigChange() {

        TestConfigStore configStore = new TestConfigStore();
        configStore.disableCloudMediaFeature();

        PickerSyncController controller =
                PickerSyncController.initialize(mContext, mFacade, configStore, mLockManager);
        assertWithMessage(
                "CloudProviderInfo should have been EMPTY when CloudMediaFeature is disabled.")
                .that(controller.getCurrentCloudProviderInfo()).isEqualTo(CloudProviderInfo.EMPTY);
        configStore.setDefaultCloudProviderPackage(PACKAGE_NAME);
        configStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);

        // Ensure the cloud provider is set to something. (The test package name here actually
        // has multiple cloud providers in it, so just ensure something got set.)
        assertWithMessage("Failed to set cloud provider on config change.")
                .that(controller.getCurrentCloudProviderInfo().authority).isNotNull();

        configStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();

        // Ensure the cloud provider is correctly nulled out when the config changes again.
        assertWithMessage("Failed to nullify cloud provider on config change.")
                .that(controller.getCurrentCloudProviderInfo().authority).isNull();
    }

    @Test
    public void testSyncIsCancelledIfCloudProviderIsChanged() throws UnableToAcquireLockException {

        PickerSyncController controller = spy(mController);

        // Ensure we return the appropriate authority until we actually enter the sync process,
        // and then return a different authority than what the sync was started with to simulate
        // a cloud provider changing.
        doReturn(CLOUD_PRIMARY_PROVIDER_AUTHORITY,
                CLOUD_SECONDARY_PROVIDER_AUTHORITY)
                .when(controller)
                .getCloudProviderWithTimeout();

        // Add local only media, we expect these to be successfully sync'd from the local provider.
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2);
        mLocalMediaGenerator.setNextCursorExtras(
                /* queryCount */ 2,
                /* mediaCollectionId */ COLLECTION_1,
                /* honoredSyncGeneration */ true,
                /* honoredAlbumId */ false,
                /* honoredPageSize */ true);

        // Add cloud media, we should try to sync these, but not actually commit them since the
        // cloud provider will be changed before the transaction can be committed.
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);
        mCloudPrimaryMediaGenerator.setNextCursorExtras(
                /* queryCount */ 2,
                /* mediaCollectionId */ COLLECTION_1,
                /* honoredSyncGeneration */ true,
                /* honoredAlbumId */ false,
                /* honoredPageSize */ true);

        controller.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        controller.syncAllMedia();

        // The cursor should only contain the items from the local provider. (Even though we've
        // added a total of 4 items to the linked providers.)
        try (Cursor cr = queryMedia()) {
            assertWithMessage("Cursor should only contain the items from the local provider.")
                    .that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

    }

    @Test
    public void testSyncAllMediaNoCloud() {
        // 1. Do nothing
        mController.syncAllMedia();
        assertEmptyCursorFromMediaQuery();

        // 2. Add local only media
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2);

        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after adding two local only media.")
                    .that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 3. Delete one local-only media
        deleteMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after deleting one local-only "
                            + "media.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
        }

        // 4. Reset media without version bump
        mLocalMediaGenerator.resetAll();
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after resetting media without "
                            + "version bump.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
        }

        // 5. Bump version
        mLocalMediaGenerator.setMediaCollectionId(COLLECTION_2);
        mController.syncAllMedia();

        assertEmptyCursorFromMediaQuery();
    }

    @Test
    public void testSyncAllAlbumMediaNoCloud() {
        // 1. Do nothing
        mController.syncAlbumMedia(ALBUM_ID_1, true);
        assertEmptyCursorFromMediaQuery();

        // 2. Add local only media
        addAlbumMedia(mLocalMediaGenerator, LOCAL_ONLY_1.first, LOCAL_ONLY_1.second, ALBUM_ID_1);
        addAlbumMedia(mLocalMediaGenerator, LOCAL_ONLY_2.first, LOCAL_ONLY_2.second, ALBUM_ID_1);

        mController.syncAlbumMedia(ALBUM_ID_1, true);

        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, true)) {
            assertWithMessage(
                    "Unexpected number of album medias in album albumId = "
                            + ALBUM_ID_1)
                    .that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 3. Syncs only given album's media
        addAlbumMedia(mLocalMediaGenerator, LOCAL_ONLY_1.first, LOCAL_ONLY_1.second, ALBUM_ID_2);

        mController.syncAlbumMedia(ALBUM_ID_1, true);

        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, true)) {
            assertWithMessage(
                    "Unexpected number of album medias in album albumId = "
                            + ALBUM_ID_1)
                    .that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 4. Syncing and querying another Album, gets you only items from that album
        mController.syncAlbumMedia(ALBUM_ID_2, true);

        try (Cursor cr = queryAlbumMedia(ALBUM_ID_2, true)) {
            assertWithMessage(
                    "Unexpected number of album medias in album albumId = "
                            + ALBUM_ID_2)
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 5. Reset media without version bump, still resets as we always do a full sync for albums.
        mLocalMediaGenerator.resetAll();
        mController.syncAlbumMedia(ALBUM_ID_1, true);

        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, true);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_2, true)) {
            assertWithMessage(
                    "Unexpected number of album medias in album albumId = "
                            + ALBUM_ID_2)
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 6. Sync another album after reset and check that is empty too.
        mController.syncAlbumMedia(ALBUM_ID_2, true);

        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_2, true);
    }

    @Test
    public void testSyncAllMediaCloudOnly() {
        // 1. Add media before setting primary cloud provider
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);
        mController.syncAllMedia();
        assertEmptyCursorFromMediaQuery();

        // 2. Set secondary cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_SECONDARY_PROVIDER_AUTHORITY);

        // 3. Set primary cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after syncing all media")
                    .that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 4. Set secondary cloud provider again
        setCloudProviderAndSyncAllMedia(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
        assertEmptyCursorFromMediaQuery();

        // 5. Set primary cloud provider once again
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after second sync of all media.")
                    .that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 6. Clear cloud provider
        setCloudProviderAndSyncAllMedia(/* authority */ null);
        assertEmptyCursorFromMediaQuery();
    }

    @Test
    public void testSyncAllMediaLocal() {
        // 1. Set primary cloud provider
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after syncing all media.")
                    .that(cr.getCount()).isEqualTo(1);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 2. Add local only media
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2);

        // 3. Add another media in primary cloud provider
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);

        mController.syncAllMediaFromLocalProvider(/* cancellationSignal=*/ null);
        // Verify that the sync only synced local items
        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after local sync")
                    .that(cr.getCount()).isEqualTo(3);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testSyncAllAlbumMediaCloudOnly() {
        // 1. Add media before setting primary cloud provider
        addAlbumMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1.first, CLOUD_ONLY_1.second,
                ALBUM_ID_1);
        addAlbumMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2.first, CLOUD_ONLY_2.second,
                ALBUM_ID_1);
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);

        // 2. Set secondary cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);

        // 3. Set primary cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncAlbumMedia(ALBUM_ID_1, false);

        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, false)) {
            assertWithMessage(
                    "Unexpected number of album medias on queryAlbumMedia() after setting cloud "
                            + "providers and syncing cloud album media")
                    .that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 4. Set secondary cloud provider again
        setCloudProviderAndSyncAllMedia(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);

        // 5. Set primary cloud provider once again
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, false)) {
            assertWithMessage(
                    "Unexpected number of album medias on queryAlbumMedia() after setting cloud "
                            + "providers and syncing cloud album media for the second time")
                    .that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 6. Clear cloud provider
        setCloudProviderAndSyncAllMedia(/* authority */ null);
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);
    }

    @Test
    public void testSyncAllAlbumMediaCloudAndLocal() {
        // 1. Add media before setting primary cloud provider
        addAlbumMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1.first, CLOUD_ONLY_1.second,
                ALBUM_ID_1);
        addAlbumMedia(mLocalMediaGenerator, LOCAL_ONLY_1.first, LOCAL_ONLY_1.second,
                ALBUM_ID_1);
        addAlbumMedia(mLocalMediaGenerator, LOCAL_ONLY_2.first, LOCAL_ONLY_2.second,
                ALBUM_ID_2);

        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);

        // 2. Set secondary cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);

        // 3. Set primary cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        mController.syncAlbumMedia(ALBUM_ID_1, false);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, false)) {
            assertWithMessage(
                    "Unexpected number of album media on queryAlbumMedia() after syncing first "
                            + "album from cloud provider")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 4. Set secondary cloud provider again
        setCloudProviderAndSyncAllMedia(CLOUD_SECONDARY_PROVIDER_AUTHORITY);

        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);

        // 4. Set primary cloud provider again
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 4a. Sync the first album and query local albums
        mController.syncAlbumMedia(ALBUM_ID_1, true);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, true)) {
            assertWithMessage(
                    "Unexpected number of album media on queryAlbumMedia() after syncing first "
                            + "album from local provider")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 4b. Sync the second album
        mController.syncAlbumMedia(ALBUM_ID_2, true);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_2, true)) {
            assertWithMessage(
                    "Unexpected number of album media on queryAlbumMedia() after syncing second "
                            + "album from local provider")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
        }

        // 5. Sync and query cloud albums
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, false)) {
            assertWithMessage(
                    "Unexpected number of album media on queryAlbumMedia() after syncing first "
                            + "album from cloud provider")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 6. Clear cloud provider
        setCloudProviderAndSyncAllMedia(/* authority */ null);
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);
    }

    @Test
    public void testCloudResetSync() {
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 1. Do nothing
        assertEmptyCursorFromMediaQuery();

        // 2. Add cloud-only item
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);

        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after syncing all media")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 3. Set invalid cloud version
        mCloudPrimaryMediaGenerator.setMediaCollectionId(/* version */ null);
        mController.syncAllMedia();
        assertEmptyCursorFromMediaQuery();

        // 4. Set valid cloud version
        mCloudPrimaryMediaGenerator.setMediaCollectionId(COLLECTION_1);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after setting valid cloud version"
                            + " and syncing all media.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }


    @Test
    public void testCloudResetAlbumMediaSync() {
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 1. Do nothing
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);

        // 2. Add cloud-only item
        addAlbumMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1.first, CLOUD_ONLY_1.second,
                ALBUM_ID_1);

        mController.syncAlbumMedia(ALBUM_ID_1, false);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, false)) {
            assertWithMessage(
                    "Unexpected number of album media on queryAlbumMedia() after syncing album "
                            + "from cloud provider")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 3. Reset Cloud provider
        mCloudPrimaryMediaGenerator.resetAll();
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);

        // 4. Add cloud-only item
        addAlbumMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1.first, CLOUD_ONLY_1.second,
                ALBUM_ID_1);

        mController.syncAlbumMedia(ALBUM_ID_1, false);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, false)) {
            assertWithMessage(
                    "Unexpected number of album media on queryAlbumMedia() after cloud provider "
                            + "reset and syncing album from cloud provider")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 5. Unset Cloud provider
        setCloudProviderAndSyncAllMedia(null);
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);
    }

    @Test
    public void testSyncAllMediaCloudAndLocal() {
        // 1. Do nothing
        assertEmptyCursorFromMediaQuery();

        // 2. Set primary cloud provider and add 2 items: cloud+local and local-only
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_AND_LOCAL_1);

        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after syncing all media")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 3. Delete local-only item
        deleteMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after deleting local-only item.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 4. Re-add local-only item
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after re-adding local-only item.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 5. Delete cloud+local item
        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_AND_LOCAL_1);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after deleting cloud+local item.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 6. Delete local-only item
        deleteMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncAllMedia();

        assertEmptyCursorFromMediaQuery();
    }

    @Test
    public void testSetCloudProvider() {
        //1. Get local provider assertion out of the way
        assertWithMessage("Unexpected local provider.")
                .that(mController.getLocalProvider()).isEqualTo(LOCAL_PROVIDER_AUTHORITY);

        // Assert that no cloud provider set on facade
        assertWithMessage("Facade cloud provider should have been null.")
                .that(mFacade.getCloudProvider()).isNull();

        // 2. Can set cloud provider
        assertWithMessage("Failed to set cloud provider. ")
                .that(mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertWithMessage("Unexpected cloud provider.")
                .that(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert that setting cloud provider clears facade cloud provider
        // And after syncing, the latest provider is set on the facade
        assertWithMessage("Setting cloud provider failed to clear facade cloud provider.")
                .that(mFacade.getCloudProvider()).isNull();
        mController.syncAllMedia();
        assertWithMessage("Failed to set latest provider on the facade post sync.")
                .that(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 3. Can clear cloud provider
        assertWithMessage("Failed to clear cloud provider.")
                .that(setCloudProviderAndSyncAllMedia(null)).isTrue();
        assertWithMessage("Cloud provider should have been null.")
                .that(mController.getCloudProvider()).isNull();

        // Assert that setting cloud provider clears facade cloud provider
        // And after syncing, the latest provider is set on the facade
        assertWithMessage("Setting cloud provider failed to clear facade cloud provider.")
                .that(mFacade.getCloudProvider()).isNull();
        mController.syncAllMedia();
        assertWithMessage("Facade Cloud provider should have been null post sync.")
                .that(mFacade.getCloudProvider()).isNull();

        // 4. Can set cloud proivder
        assertWithMessage("Failed to set cloud provider. ")
                .that(mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertWithMessage("Unexpected cloud provider.")
                .that(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert that setting cloud provider clears facade cloud provider
        // And after syncing, the latest provider is set on the facade
        assertWithMessage("Setting cloud provider failed to clear facade cloud provider.")
                .that(mFacade.getCloudProvider()).isNull();
        mController.syncAllMedia();
        assertWithMessage("Failed to set latest provider on the facade post sync.")
                .that(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Invalid cloud provider is ignored
        assertWithMessage("Setting invalid cloud provider should have failed.")
                .that(setCloudProviderAndSyncAllMedia(LOCAL_PROVIDER_AUTHORITY)).isFalse();
        assertWithMessage("Unexpected cloud provider.")
                .that(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert that unsuccessfully setting cloud provider doesn't clear facade cloud provider
        // And after syncing, nothing changes
        assertWithMessage(
                "Unsuccessfully setting cloud provider should have failed to clear facade cloud "
                        + "provider.")
                .that(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncAllMedia();
        assertWithMessage("Unexpected facade cloud provider post sync.")
                .that(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
    }

    @Test
    public void testEnableCloudQueriesAfterMPRestart() {
        //1. Get local provider assertion out of the way
        assertWithMessage("Unexpected local provider.")
                .that(mController.getLocalProvider()).isEqualTo(LOCAL_PROVIDER_AUTHORITY);

        // Assert that no cloud provider set on facade
        assertWithMessage("Facade cloud provider should have been null.")
                .that(mFacade.getCloudProvider()).isNull();

        // 2. Can set cloud provider
        assertWithMessage("Failed to set cloud provider.")
                .that(mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertWithMessage("Unexpected cloud provider.")
                .that(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert that setting cloud provider clears facade cloud provider
        // And after syncing, the latest provider is set on the facade
        assertWithMessage("Setting cloud provider failed to clear facade cloud provider.")
                .that(mFacade.getCloudProvider()).isNull();
        mController.syncAllMedia();
        assertWithMessage("Unexpected facade cloud provider post sync.")
                .that(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 3. Clear facade cloud provider to simulate MP restart.
        mFacade.setCloudProvider(null);

        // 4. Assert that latest provider is set in the facade after sync even when no sync was
        // required.
        mController.syncAllMedia();
        assertWithMessage("Failed to set latest provider in the facade after MP restart.")
                .that(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
    }


    @Test
    public void testGetSupportedCloudProviders() {
        List<CloudProviderInfo> providers = mController.getAvailableCloudProviders();

        final CloudProviderInfo primaryInfo =
                new CloudProviderInfo(
                        CLOUD_PRIMARY_PROVIDER_AUTHORITY, PACKAGE_NAME, Process.myUid());
        final CloudProviderInfo secondaryInfo =
                new CloudProviderInfo(
                        CLOUD_SECONDARY_PROVIDER_AUTHORITY, PACKAGE_NAME, Process.myUid());
        final CloudProviderInfo flakyInfo =
                new CloudProviderInfo(
                        FLAKY_CLOUD_PROVIDER_AUTHORITY, PACKAGE_NAME, Process.myUid());

        assertWithMessage(
                "Unexpected cloud provider in the list returned by getAvailableCloudProviders().")
                .that(providers).containsExactly(primaryInfo, secondaryInfo, flakyInfo);
    }

    @Test
    public void testGetSupportedCloudProviders_useAllowList() {
        final CloudProviderInfo primaryInfo = new CloudProviderInfo(
                CLOUD_PRIMARY_PROVIDER_AUTHORITY, PACKAGE_NAME, Process.myUid());
        final CloudProviderInfo secondaryInfo = new CloudProviderInfo(
                CLOUD_SECONDARY_PROVIDER_AUTHORITY, PACKAGE_NAME, Process.myUid());
        final CloudProviderInfo flakyInfo = new CloudProviderInfo(FLAKY_CLOUD_PROVIDER_AUTHORITY,
                PACKAGE_NAME,
                Process.myUid());

        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);
        final PickerSyncController controller = PickerSyncController.initialize(
                mContext, mFacade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);
        final List<CloudProviderInfo> providers = controller.getAvailableCloudProviders();
        assertWithMessage(
                "Unexpected cloud provider in the list returned by getAvailableCloudProviders() "
                        + "when using allowList.")
                .that(providers).containsExactly(primaryInfo, secondaryInfo, flakyInfo);
    }

    @Test
    public void testNotifyPackageRemoval_NoDefaultCloudProviderPackage() {
        mConfigStore.clearDefaultCloudProviderPackage();

        assertWithMessage("Failed to set cloud provider.")
                .that(mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertWithMessage("Unexpected cloud provider.")
                .that(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert passing wrong package name doesn't clear the current cloud provider
        mController.notifyPackageRemoval(PACKAGE_NAME + "invalid");
        assertWithMessage(
                "Unexpected cloud provider, passing wrong package shouldn't have cleared the "
                        + "current cloud provider.")
                .that(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert passing the current cloud provider package name clears the current cloud provider
        mController.notifyPackageRemoval(PACKAGE_NAME);
        assertWithMessage(
                "Unexpected cloud provider, passing current package should have cleared the "
                        + "current cloud provider.")
                .that(mController.getCloudProvider()).isNull();

        // Assert that the cloud provider state was not UNSET after the last cloud provider removal
        mConfigStore.setDefaultCloudProviderPackage(PACKAGE_NAME);

        mController = PickerSyncController.initialize(mContext, mFacade, mConfigStore,
                mLockManager, LOCAL_PROVIDER_AUTHORITY);

        assertWithMessage(
                "Unexpected cloud provider, cloud provider state got UNSET after the last cloud "
                        + "provider removal")
                .that(mController.getCurrentCloudProviderInfo().packageName).isEqualTo(
                        PACKAGE_NAME);
    }

    // TODO(b/278687585): Add test for PickerSyncController#notifyPackageRemoval with a different
    //  non-null default cloud provider package

    @Test
    public void testSelectDefaultCloudProvider_NoDefaultAuthority() {
        PickerSyncController controller = createControllerWithDefaultProvider(null);
        assertWithMessage("Default provider was set to null.")
                .that(controller.getCloudProvider()).isNull();
    }

    @Test
    public void testSelectDefaultCloudProvider_defaultAuthoritySet() {
        PickerSyncController controller = createControllerWithDefaultProvider(PACKAGE_NAME);
        assertWithMessage("Default provider was set to " + PACKAGE_NAME)
                .that(controller.getCurrentCloudProviderInfo().packageName).isEqualTo(PACKAGE_NAME);
    }

    @Test
    public void testIsProviderAuthorityEnabled() {
        assertWithMessage("Expected " + LOCAL_PROVIDER_AUTHORITY + " to be enabled.")
                .that(mController.isProviderEnabled(LOCAL_PROVIDER_AUTHORITY)).isTrue();
        assertWithMessage("Expected " + CLOUD_PRIMARY_PROVIDER_AUTHORITY + " to be disabled")
                .that(mController.isProviderEnabled(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isFalse();
        assertWithMessage("Expected " + CLOUD_SECONDARY_PROVIDER_AUTHORITY + " to be disabled")
                .that(mController.isProviderEnabled(CLOUD_SECONDARY_PROVIDER_AUTHORITY)).isFalse();

        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        assertWithMessage("Expected " + LOCAL_PROVIDER_AUTHORITY + " to be enabled.")
                .that(mController.isProviderEnabled(LOCAL_PROVIDER_AUTHORITY)).isTrue();
        assertWithMessage("Expected " + CLOUD_PRIMARY_PROVIDER_AUTHORITY + " to be enabled.")
                .that(mController.isProviderEnabled(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertWithMessage("Expected " + CLOUD_SECONDARY_PROVIDER_AUTHORITY + " to be disabled.")
                .that(mController.isProviderEnabled(CLOUD_SECONDARY_PROVIDER_AUTHORITY)).isFalse();
    }

    @Test
    public void testIsProviderUidEnabled() {
        assertWithMessage("Expected " + LOCAL_PROVIDER_AUTHORITY + " uid = " + Process.myUid()
                + " to be enabled.")
                .that(mController.isProviderEnabled(LOCAL_PROVIDER_AUTHORITY, Process.myUid()))
                .isTrue();
        assertWithMessage(
                "Expected " + LOCAL_PROVIDER_AUTHORITY + " uid = 1000" + " to be disabled.")
                .that(mController.isProviderEnabled(LOCAL_PROVIDER_AUTHORITY, 1000)).isFalse();
    }

    @Test
    public void testSyncAfterDbCreate() {
        mConfigStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();

        final PickerDatabaseHelper dbHelper = new PickerDatabaseHelper(
                mContext, DB_NAME, DB_VERSION_1);
        PickerDbFacade facade = new PickerDbFacade(mContext, mLockManager, LOCAL_PROVIDER_AUTHORITY,
                dbHelper);
        PickerSyncController controller = PickerSyncController.initialize(
                mContext, facade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        controller.syncAllMedia();

        try (Cursor cr = queryMedia(facade)) {
            assertWithMessage("Unexpected number of media after adding one local-only media.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        dbHelper.close();

        // Delete db so it's recreated on next access
        final File dbPath = mContext.getDatabasePath(DB_NAME);
        dbPath.delete();

        facade = new PickerDbFacade(mContext, mLockManager, LOCAL_PROVIDER_AUTHORITY, dbHelper);
        controller = PickerSyncController.initialize(
                mContext, facade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        // Initially empty db
        try (Cursor cr = queryMedia(facade)) {
            assertWithMessage("Unexpected number of media after deleting and recreating the db.")
                    .that(cr.getCount()).isEqualTo(0);
        }

        controller.syncAllMedia();

        // Fully synced db
        try (Cursor cr = queryMedia(facade)) {
            assertWithMessage("Unexpected number of media after fully syncing the recreated db.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testSyncAfterDbUpgrade() {
        mConfigStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();

        PickerDatabaseHelper dbHelperV1 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        PickerDbFacade facade = new PickerDbFacade(mContext, mLockManager, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV1);
        PickerSyncController controller = PickerSyncController.initialize(
                mContext, facade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        controller.syncAllMedia();

        try (Cursor cr = queryMedia(facade)) {
            assertWithMessage("Unexpected number of media after adding one local-only media.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // Upgrade db version
        dbHelperV1.close();
        PickerDatabaseHelper dbHelperV2 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_2);
        facade = new PickerDbFacade(mContext, mLockManager, LOCAL_PROVIDER_AUTHORITY, dbHelperV2);
        controller = PickerSyncController.initialize(
                mContext, facade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        // Initially empty db
        try (Cursor cr = queryMedia(facade)) {
            assertWithMessage("Unexpected number of media after upgrading the db version.")
                    .that(cr.getCount()).isEqualTo(0);
        }

        controller.syncAllMedia();

        // Fully synced db
        try (Cursor cr = queryMedia(facade)) {
            assertWithMessage("Unexpected number of media after fully syncing the upgraded db.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testSyncAfterDbDowngrade() {
        mConfigStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();

        PickerDatabaseHelper dbHelperV2 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_2);
        PickerDbFacade facade = new PickerDbFacade(mContext, mLockManager, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV2);
        PickerSyncController controller = PickerSyncController.initialize(
                mContext, facade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        controller.syncAllMedia();

        try (Cursor cr = queryMedia(facade)) {
            assertWithMessage("Unexpected number of media after adding one local-only media.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // Downgrade db version
        dbHelperV2.close();
        PickerDatabaseHelper dbHelperV1 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        facade = new PickerDbFacade(mContext, mLockManager, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV1);
        controller = PickerSyncController.initialize(
                mContext, facade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        // Initially empty db
        try (Cursor cr = queryMedia(facade)) {
            assertWithMessage("Unexpected number of media after downgrading the db version.")
                    .that(cr.getCount()).isEqualTo(0);
        }

        controller.syncAllMedia();

        // Fully synced db
        try (Cursor cr = queryMedia(facade)) {
            assertWithMessage("Unexpected number of media after fully syncing the downgraded db.")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testAllMediaSyncValidationFailure_incorrectMediaCollectionId() {
        // 1. Set cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 2. Force the next 2 syncs (including retry) to have correct extra_media_collection_id
        mCloudPrimaryMediaGenerator.setNextCursorExtras(2, COLLECTION_1,
                /* honoredSyncGeneration */ true, /* honoredAlbumId */ false, true);

        // 4. Add cloud media
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);

        // 5. Sync and verify media
        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on syncing all media with correct "
                            + "extra_media_collection_id")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 6. Force the next sync (without retry) to have incorrect extra_media_collection_id
        mCloudPrimaryMediaGenerator.setNextCursorExtras(1, COLLECTION_2,
                /* honoredSyncGeneration */ true, /* honoredAlbumId */ false, true);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);

        // 7. Sync and verify media after retry succeeded
        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on syncing all media with incorrect "
                            + "extra_media_collection_id")
                    .that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 8. Force the next 2 syncs (including retry) to have incorrect extra_media_collection_id
        mCloudPrimaryMediaGenerator.setNextCursorExtras(2, COLLECTION_2,
                /* honoredSyncGeneration */ true, /* honoredAlbumId */ false, true);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_3);

        // 9. Sync and verify media was reset
        mController.syncAllMedia();
        assertEmptyCursorFromMediaQuery();
    }

    @Test
    public void testAllMediaSyncValidationRecovery_missingSyncGenerationHonoredArg() {
        // 1. Set cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 2. Force the next 2 syncs (including retry) to have correct extra_honored_args
        mCloudPrimaryMediaGenerator.setNextCursorExtras(2, COLLECTION_1,
                /* honoredSyncGeneration */ true, /* honoredAlbumId */ false, true);

        // 3. Add cloud media
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);

        // 4. Sync and verify media
        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on syncing all media with correct "
                            + "extra_honored_args")
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 5. Force the next sync (without retry) to have incorrect extra_honored_args
        mCloudPrimaryMediaGenerator.setNextCursorExtras(1, COLLECTION_1,
                /* honoredSyncGeneration */ false, /* honoredAlbumId */ false, true);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);

        // 6. Sync and verify media after retry succeeded
        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on syncing all media with incorrect "
                            + "extra_honored_args")
                    .that(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testSyncAllMedia_missingOptionalHonoredArgs_displaysCloud() {
        // 1. Set cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 2. Add media before syncing again with the cloud provider
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);

        // 3. Force next sync to not honour page size
        mCloudPrimaryMediaGenerator.setNextCursorExtras(2, COLLECTION_1,
                /* honoredSyncGeneration */ true, /* honoredAlbumId */ false, false);

        // 4. Sync and verify media
        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertWithMessage("Unexpected number of media")
                    .that(cr.getCount()).isEqualTo(/* expected= */ 2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testAlbumMediaSyncValidationFailure_missingAlbumIdHonoredArg() {
        // 1. Set cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 2. Force the next sync to have correct extra_media_collection_id
        mCloudPrimaryMediaGenerator.setNextCursorExtras(1, COLLECTION_1,
                /* honoredSyncGeneration */ false, /* honoredAlbumId */ true, true);

        // 3. Add cloud album_media
        addAlbumMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1.first, CLOUD_ONLY_1.second,
                ALBUM_ID_1);

        // 4. Sync and verify album_media
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, false)) {
            assertWithMessage(
                    "Unexpected number of album media from album with albumId = " + ALBUM_ID_1)
                    .that(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 5. Force the next sync to have incorrect extra_album_id
        mCloudPrimaryMediaGenerator.setNextCursorExtras(1, COLLECTION_1,
                /* honoredSyncGeneration */ false, /* honoredAlbumId */ false, true);

        // 6. Sync and verify album_media is empty
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);
    }

    @Test
    public void testUserPrefsAfterDbUpgrade() {
        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);

        PickerDatabaseHelper dbHelperV1 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        PickerDbFacade facade = new PickerDbFacade(mContext, mLockManager, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV1);
        PickerSyncController controller = PickerSyncController.initialize(
                mContext, facade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        controller.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        assertWithMessage("Unexpected cloud provider on db set up.")
                .that(controller.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Downgrade db version
        dbHelperV1.close();
        PickerDatabaseHelper dbHelperV2 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_2);
        facade = new PickerDbFacade(mContext, mLockManager, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV2);
        controller = PickerSyncController.initialize(
                mContext, facade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        assertWithMessage("Unexpected cloud provider after db version downgrade.")
                .that(controller.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
    }

    @Test
    public void testCloudProviderUnset() {
        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);
        mConfigStore.setDefaultCloudProviderPackage(PACKAGE_NAME);

        // Test the default NOT_SET state
        mController =
                PickerSyncController.initialize(
                        mContext, mFacade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        assertWithMessage("Unexpected cloud provider on testing the default NOT_SET state.")
                .that(mController.getCurrentCloudProviderInfo().packageName).isEqualTo(
                        PACKAGE_NAME);

        // Set and test the UNSET state
        mController.setCloudProvider(/* authority */ null);

        mController =
                PickerSyncController.initialize(
                        mContext, mFacade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        assertWithMessage("Unexpected cloud provider on setting and testing the NOT_SET state.")
                .that(mController.getCloudProvider()).isNull();

        // Set and test the SET state
        mController.setCloudProvider(CLOUD_SECONDARY_PROVIDER_AUTHORITY);

        mController =
                PickerSyncController.initialize(
                        mContext, mFacade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);

        assertWithMessage("Unexpected cloud provider on setting and testing the SET state.")
                .that(mController.getCloudProvider()).isEqualTo(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
    }

    @Test
    public void testAvailableCloudProviders_CloudFeatureDisabled() {
        assertWithMessage("Empty list returned by getAvailableCloudProviders().")
                .that(mController.getAvailableCloudProviders()).isNotEmpty();
        mConfigStore.disableCloudMediaFeature();
        assertWithMessage(
                "Non-empty list returned by getAvailableCloudProviders() after disabling the "
                        + "cloud media feature.")
                .that(mController.getAvailableCloudProviders()).isEmpty();
    }

    @Test
    public void testSyncWithMultiplePages() {

        // First Page
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_3);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_4);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_5);
        // Second Page
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_6);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_7);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_8);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_9);

        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after adding 9 cloud-only media.")
                    .that(cr.getCount()).isEqualTo(9);
            assertCursor(cr, CLOUD_ID_9, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_8, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_7, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_6, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_5, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_4, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_3, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testSyncDeletedItemsWithMultiplePages() {

        // First Page
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_3);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_4);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_5);
        // Second Page
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_6);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_7);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_8);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_9);

        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after adding 9 cloud-only media.")
                    .that(cr.getCount()).isEqualTo(9);
            assertCursor(cr, CLOUD_ID_9, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_8, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_7, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_6, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_5, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_4, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_3, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);
        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_3);
        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_4);
        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_5);
        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_6);
        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_7);
        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_8);

        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after deleting 8 out the 9 "
                            + "cloud-only media.")
                    .that(cr.getCount()).isEqualTo(1);
            assertCursor(cr, CLOUD_ID_9, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

    }

    @Test
    public void testResumableIncrementalSyncOperation() {
        // First Page
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_2);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_3);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_4);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_5);

        // Complete a full sync since it hasn't synced before.
        setCloudProviderAndSyncAllMedia(FLAKY_CLOUD_PROVIDER_AUTHORITY);
        mController.syncAllMedia();
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            // Should only have the first page since the sync is flaky
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after adding 5 cloud-only media.")
                    .that(cr.getCount()).isEqualTo(5);
            assertCursor(cr, CLOUD_ID_5, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_4, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_3, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_2, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, FLAKY_CLOUD_PROVIDER_AUTHORITY);
        }

        // Add some more data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_6);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_7);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_8);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_9);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_10);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_11);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_12);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_13);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_14);

        // FlakyCloudMediaProvider will throw errors on 2 out of 3 requests, so we need to sync
        // a few times to ensure we resume the mid-sync failure.
        mController.syncAllMedia();
        mController.syncAllMedia();
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            // Should have all pages now
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after adding 9 cloud-only media, "
                            + "in addition to previously added 5 cloud-only media.")
                    .that(cr.getCount()).isEqualTo(14);
            assertCursor(cr, CLOUD_ID_14, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_13, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_12, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_11, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_10, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_9, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_8, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_7, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_6, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_5, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_4, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_3, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_2, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, FLAKY_CLOUD_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testResumableFullSyncOperation() {
        // First Page of data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_2);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_3);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_4);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_5);
        // Second Page of data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_6);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_7);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_8);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_9);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_10);
        // Third Page of data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_11);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_12);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_13);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_14);

        mController.setCloudProvider(FLAKY_CLOUD_PROVIDER_AUTHORITY);
        try (Cursor cr = queryMedia()) {
            // Db should be empty since we haven't synced yet.
            assertWithMessage(
                    "Unexpected number of media on queryMedia() before sync.")
                    .that(cr.getCount()).isEqualTo(0);
        }

        // FlakyCloudMediaProvider will throw errors on 2 out of 3 requests, if we sync once, it
        // should not be able to complete the sync.
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            // Assert that the sync is not complete.
            assertWithMessage(
                    "Unexpected number of media on queryMedia().")
                    .that(cr.getCount()).isLessThan(14);
        }

        // Resume sync and complete it. It will take a few sync calls to complete the sync.
        mController.syncAllMedia();
        mController.syncAllMedia();
        mController.syncAllMedia();
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            // Should have all pages now
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after adding 14 cloud-only media.")
                    .that(cr.getCount()).isEqualTo(14);
            assertCursor(cr, CLOUD_ID_14, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_13, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_12, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_11, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_10, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_9, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_8, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_7, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_6, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_5, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_4, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_3, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_2, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, FLAKY_CLOUD_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFullSyncWithCollectionIdChange() {
        mController.setCloudProvider(FLAKY_CLOUD_PROVIDER_AUTHORITY);
        mCloudFlakyMediaGenerator.setMediaCollectionId(COLLECTION_1);

        // First Page of data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_2);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_3);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_4);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_5);
        // Second Page of data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_6);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_7);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_8);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_9);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_10);
        // Third Page of data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_11);

        // FlakyCloudMediaProvider will throw errors on 2 out of 3 requests, if we sync once, it
        // should not be able to complete the sync.
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            // Assert that the sync is not complete.
            assertWithMessage(
                    "Unexpected number of media on queryMedia().")
                    .that(cr.getCount()).isLessThan(11);
        }

        // Reset data and change collection id.
        mCloudFlakyMediaGenerator.resetAll();
        mCloudFlakyMediaGenerator.setMediaCollectionId(COLLECTION_2);

        // First Page of data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_12);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_13);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_14);

        // FlakyCloudMediaProvider will throw errors on 2 out of 3 requests. It will take a few
        // tries to complete the sync.
        mController.syncAllMedia();
        mController.syncAllMedia();
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            // Db should be empty since we haven't synced yet.
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after adding 3 cloud-only media.")
                    .that(cr.getCount()).isEqualTo(3);
            assertCursor(cr, CLOUD_ID_14, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_13, FLAKY_CLOUD_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_12, FLAKY_CLOUD_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testFullSyncWithCloudProviderChange() {
        mController.setCloudProvider(FLAKY_CLOUD_PROVIDER_AUTHORITY);

        // First Page of data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_2);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_3);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_4);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_5);
        // Second Page of data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_6);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_7);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_8);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_9);
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_10);
        // Third Page of data
        addMedia(mCloudFlakyMediaGenerator, CLOUD_ONLY_11);

        // FlakyCloudMediaProvider will throw errors on 2 out of 3 requests, if we sync once, it
        // should not be able to complete the sync.
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            // Assert that the sync is not complete.
            assertWithMessage(
                    "Unexpected number of media on queryMedia().")
                    .that(cr.getCount()).isLessThan(11);
        }

        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // First Page of data
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_12);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_13);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_14);

        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            // Db should be empty since we haven't synced yet.
            assertWithMessage(
                    "Unexpected number of media on queryMedia() after adding 3 cloud-only media.")
                    .that(cr.getCount()).isEqualTo(3);
            assertCursor(cr, CLOUD_ID_14, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_13, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_12, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testContentAddNotifications() throws Exception {
        NotificationContentObserver observer = new NotificationContentObserver(null);
        observer.register(mContext.getContentResolver());

        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mCloudPrimaryMediaGenerator.setMediaCollectionId(COLLECTION_1);

        final CountDownLatch latch = new CountDownLatch(1);
        final NotificationContentObserver.ContentObserverCallback callback =
                spy(new TestableContentObserverCallback(latch));
        observer.registerKeysToObserverCallback(Arrays.asList(MEDIA), callback);

        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_3);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_4);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_5);
        mCloudPrimaryMediaGenerator.setMediaCollectionId(COLLECTION_2);

        mController.syncAllMedia();

        // Wait until the callback has received the notification.
        latch.await(5, TimeUnit.SECONDS);

        try (Cursor cr = queryMedia()) {
            cr.moveToFirst();
            verify(callback)
                    .onNotificationReceived(
                            cr.getString(cr.getColumnIndex(MediaColumns.DATE_TAKEN_MILLIS)), null);
        } finally {
            observer.unregister(mContext.getContentResolver());
        }
    }

    @Test
    public void testContentDeleteNotifications() throws Exception {
        NotificationContentObserver observer = new NotificationContentObserver(null);
        observer.register(mContext.getContentResolver());

        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        CountDownLatch latch = new CountDownLatch(1);
        NotificationContentObserver.ContentObserverCallback callback =
                spy(new TestableContentObserverCallback(latch));
        observer.registerKeysToObserverCallback(Arrays.asList(MEDIA), callback);

        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        mCloudPrimaryMediaGenerator.setMediaCollectionId(COLLECTION_1);
        mController.syncAllMedia();
        latch.await(2, TimeUnit.SECONDS);
        verify(callback).onNotificationReceived(any(), any());

        latch = new CountDownLatch(1);
        callback = spy(new TestableContentObserverCallback(latch));
        observer.registerKeysToObserverCallback(Arrays.asList(MEDIA), callback);

        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        mController.syncAllMedia();
        latch.await(2, TimeUnit.SECONDS);
        verify(callback).onNotificationReceived(any(), any());

        observer.unregister(mContext.getContentResolver());
    }

    @Test
    public void testCollectionIdChangeResetsUi() throws InterruptedException {
        final ContentResolver contentResolver = mContext.getContentResolver();
        final TestContentObserver refreshUiNotificationObserver = new TestContentObserver(null);
        try {
            setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            mCloudPrimaryMediaGenerator.setMediaCollectionId(COLLECTION_1);

            // Simulate a UI session begins listening.
            contentResolver.registerContentObserver(REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI,
                    /* notifyForDescendants */ false, refreshUiNotificationObserver);

            mCloudPrimaryMediaGenerator.setMediaCollectionId(COLLECTION_2);

            mController.syncAllMedia();

            assertWithMessage("Refresh ui notification should have been received.")
                    .that(refreshUiNotificationObserver.mNotificationReceived).isTrue();
        } finally {
            contentResolver.unregisterContentObserver(refreshUiNotificationObserver);
        }
    }

    @Test
    public void testRefreshUiNotifications() throws InterruptedException {
        final ContentResolver contentResolver = mContext.getContentResolver();
        final TestContentObserver refreshUiNotificationObserver = new TestContentObserver(null);
        try {
            contentResolver.registerContentObserver(REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI,
                    /* notifyForDescendants */ false, refreshUiNotificationObserver);

            assertWithMessage("Refresh ui notification should have not been received.")
                    .that(refreshUiNotificationObserver.mNotificationReceived).isFalse();

            mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);
            mConfigStore.setDefaultCloudProviderPackage(PACKAGE_NAME);

            // The cloud provider is changed on PickerSyncController construction
            mController = PickerSyncController
                    .initialize(mContext, mFacade, mConfigStore, mLockManager);
            TimeUnit.MILLISECONDS.sleep(100);
            assertWithMessage(
                    "Failed to receive refresh ui notification on change in cloud provider.")
                    .that(refreshUiNotificationObserver.mNotificationReceived).isTrue();

            refreshUiNotificationObserver.mNotificationReceived = false;

            // The SET_CLOUD_PROVIDER is called using a different cloud provider from before
            mController.setCloudProvider(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
            TimeUnit.MILLISECONDS.sleep(100);
            assertWithMessage(
                    "Failed to receive refresh ui notification on change in cloud provider.")
                    .that(refreshUiNotificationObserver.mNotificationReceived).isTrue();

            refreshUiNotificationObserver.mNotificationReceived = false;

            // The cloud provider remains unchanged on PickerSyncController construction
            mController = PickerSyncController
                    .initialize(mContext, mFacade, mConfigStore, mLockManager);
            TimeUnit.MILLISECONDS.sleep(100);
            assertWithMessage(
                    "Refresh ui notification should have not been received when cloud provider "
                            + "remains unchanged.")
                    .that(refreshUiNotificationObserver.mNotificationReceived).isFalse();

            // The SET_CLOUD_PROVIDER is called using the same cloud provider as before
            mController.setCloudProvider(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
            TimeUnit.MILLISECONDS.sleep(100);
            assertWithMessage(
                    "Refresh ui notification should have not been received when setCloudProvider "
                            + "is called using the same cloud provider as before.")
                    .that(refreshUiNotificationObserver.mNotificationReceived).isFalse();
        } finally {
            contentResolver.unregisterContentObserver(refreshUiNotificationObserver);
        }
    }

    private static void addMedia(MediaGenerator generator, Pair<String, String> media) {
        generator.addMedia(media.first, media.second);
    }

    private static void addAlbumMedia(MediaGenerator generator, String localId, String cloudId,
            String albumId) {
        generator.addAlbumMedia(localId, cloudId, albumId);
    }

    private static void deleteMedia(MediaGenerator generator, Pair<String, String> media) {
        generator.deleteMedia(media.first, media.second);
    }

    private static Cursor queryMedia(PickerDbFacade facade) {
        return facade.queryMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).build());
    }

    private boolean setCloudProviderAndSyncAllMedia(String authority) {
        final boolean res = mController.setCloudProvider(authority);
        mController.syncAllMedia();

        return res;
    }

    private Cursor queryMedia() {
        return mFacade.queryMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).build());
    }

    private Cursor queryAlbumMedia(String albumId, boolean isLocal) {
        final String authority = isLocal ? LOCAL_PROVIDER_AUTHORITY
                : CLOUD_PRIMARY_PROVIDER_AUTHORITY;
        return mFacade.queryAlbumMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).setAlbumId(albumId).build(), authority);
    }

    private void assertEmptyCursorFromMediaQuery() {
        try (Cursor cr = queryMedia()) {
            assertWithMessage("Cursor should have been empty.")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    private void assertEmptyCursorFromAlbumMediaQuery(String albumId, boolean isLocal) {
        try (Cursor cr = queryAlbumMedia(albumId, isLocal)) {
            assertWithMessage("Cursor from queryAlbumMedia should have been empty.")
                    .that(cr.getCount()).isEqualTo(0);
        }
    }

    private @NonNull PickerSyncController createControllerWithDefaultProvider(
            @Nullable String defaultProviderPackage) {
        Context mockContext = mock(Context.class);
        Resources mockResources = mock(Resources.class);

        when(mockContext.getResources()).thenReturn(mockResources);
        when(mockContext.getPackageManager()).thenReturn(mContext.getPackageManager());
        when(mockContext.getSystemServiceName(StorageManager.class)).thenReturn(
                mContext.getSystemServiceName(StorageManager.class));
        when(mockContext.getSystemService(StorageManager.class)).thenReturn(
                mContext.getSystemService(StorageManager.class));
        when(mockContext.getSharedPreferences(anyString(), anyInt())).thenAnswer(
                i -> mContext.getSharedPreferences((String) i.getArgument(0), i.getArgument(1)));

        if (defaultProviderPackage != null) {
            mConfigStore.setDefaultCloudProviderPackage(defaultProviderPackage);
        } else {
            mConfigStore.clearDefaultCloudProviderPackage();
        }

        return PickerSyncController.initialize(
                mockContext, mFacade, mConfigStore, mLockManager, LOCAL_PROVIDER_AUTHORITY);
    }

    private static void assertCursor(Cursor cursor, String id, String expectedAuthority) {
        cursor.moveToNext();
        assertWithMessage("Unexpected value of MediaColumns.ID in the cursor.")
                .that(cursor.getString(cursor.getColumnIndex(MediaColumns.ID)))
                .isEqualTo(id);
        assertWithMessage("Unexpected value of MediaColumns.AUTHORITY in the cursor.")
                .that(cursor.getString(cursor.getColumnIndex(MediaColumns.AUTHORITY)))
                .isEqualTo(expectedAuthority);
    }

    private static class TestContentObserver extends ContentObserver {
        boolean mNotificationReceived;

        TestContentObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            mNotificationReceived = true;
        }
    }
}
