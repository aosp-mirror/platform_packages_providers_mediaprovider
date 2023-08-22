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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.os.Process;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.CloudMediaProviderContract.MediaColumns;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.PickerProviderMediaGenerator;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.photopicker.data.CloudProviderInfo;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class PickerSyncControllerTest {
    private static final String LOCAL_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.local";
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

    private static final String LOCAL_ID_1 = "1";
    private static final String LOCAL_ID_2 = "2";

    private static final String CLOUD_ID_1 = "1";
    private static final String CLOUD_ID_2 = "2";
    private static final String CLOUD_ID_3 = "3";

    private static final String ALBUM_ID_1 = "1";
    private static final String ALBUM_ID_2 = "2";

    private static final Pair<String, String> LOCAL_ONLY_1 = Pair.create(LOCAL_ID_1, null);
    private static final Pair<String, String> LOCAL_ONLY_2 = Pair.create(LOCAL_ID_2, null);
    private static final Pair<String, String> CLOUD_ONLY_1 = Pair.create(null, CLOUD_ID_1);
    private static final Pair<String, String> CLOUD_ONLY_2 = Pair.create(null, CLOUD_ID_2);
    private static final Pair<String, String> CLOUD_ONLY_3 = Pair.create(null, CLOUD_ID_3);
    private static final Pair<String, String> CLOUD_AND_LOCAL_1
            = Pair.create(LOCAL_ID_1, CLOUD_ID_1);

    private static final String COLLECTION_1 = "1";
    private static final String COLLECTION_2 = "2";

    private static final int SYNC_DELAY_MS = 1000;

    private static final int DB_VERSION_1 = 1;
    private static final int DB_VERSION_2 = 2;
    private static final String DB_NAME = "test_db";

    private Context mContext;
    private TestConfigStore mConfigStore;
    private PickerDbFacade mFacade;
    private PickerSyncController mController;

    @Before
    public void setUp() {
        mLocalMediaGenerator.resetAll();
        mCloudPrimaryMediaGenerator.resetAll();
        mCloudSecondaryMediaGenerator.resetAll();

        mLocalMediaGenerator.setMediaCollectionId(COLLECTION_1);
        mCloudPrimaryMediaGenerator.setMediaCollectionId(COLLECTION_1);
        mCloudSecondaryMediaGenerator.setMediaCollectionId(COLLECTION_1);

        mContext = InstrumentationRegistry.getTargetContext();

        // Delete db so it's recreated on next access and previous test state is cleared
        final File dbPath = mContext.getDatabasePath(DB_NAME);
        dbPath.delete();

        PickerDatabaseHelper dbHelper = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        mFacade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY, dbHelper);

        mConfigStore = new TestConfigStore();
        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);
        mConfigStore.setPickerSyncDelayMs(0);

        mController = new PickerSyncController(
                mContext, mFacade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

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
    public void testSyncAllMediaNoCloud() {
        // 1. Do nothing
        mController.syncAllMedia();
        assertEmptyCursorFromMediaQuery();

        // 2. Add local only media
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2);

        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 3. Delete one local-only media
        deleteMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
        }

        // 4. Reset media without version bump
        mLocalMediaGenerator.resetAll();
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

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
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 3. Syncs only given album's media
        addAlbumMedia(mLocalMediaGenerator, LOCAL_ONLY_1.first, LOCAL_ONLY_1.second, ALBUM_ID_2);

        mController.syncAlbumMedia(ALBUM_ID_1, true);

        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, true)) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 4. Syncing and querying another Album, gets you only items from that album
        mController.syncAlbumMedia(ALBUM_ID_2, true);

        try (Cursor cr = queryAlbumMedia(ALBUM_ID_2, true)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 5. Reset media without version bump, still resets as we always do a full sync for albums.
        mLocalMediaGenerator.resetAll();
        mController.syncAlbumMedia(ALBUM_ID_1, true);

        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, true);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_2, true)) {
            assertThat(cr.getCount()).isEqualTo(1);

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
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 4. Set secondary cloud provider again
        setCloudProviderAndSyncAllMedia(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
        assertEmptyCursorFromMediaQuery();

        // 5. Set primary cloud provider once again
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(2);

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
            assertThat(cr.getCount()).isEqualTo(1);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 2. Add local only media
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2);

        // 3. Add another media in primary cloud provider
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);

        mController.syncAllMediaFromLocalProvider();
        // Verify that the sync only synced local items
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(3);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testSyncAllMediaResetsAlbumMedia() {
        // 1. Set primary cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);

        // 2. Add album_media
        addAlbumMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1.first, CLOUD_ONLY_1.second,
                ALBUM_ID_1);
        addAlbumMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2.first, CLOUD_ONLY_2.second,
                ALBUM_ID_1);
        mController.syncAlbumMedia(ALBUM_ID_1, false);

        // 3. Assert non-empty album_media
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, false)) {
            assertThat(cr.getCount()).isEqualTo(2);
        }

        // 4. Sync all media and assert empty album_media
        mController.syncAllMedia();
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);
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
            assertThat(cr.getCount()).isEqualTo(2);

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
            assertThat(cr.getCount()).isEqualTo(2);

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
            assertThat(cr.getCount()).isEqualTo(1);

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
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 4b. Sync the second album
        mController.syncAlbumMedia(ALBUM_ID_2, true);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_2, true)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_2, LOCAL_PROVIDER_AUTHORITY);
        }

        // 5. Sync and query cloud albums
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, false)) {
            assertThat(cr.getCount()).isEqualTo(1);

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
            assertThat(cr.getCount()).isEqualTo(1);

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
            assertThat(cr.getCount()).isEqualTo(1);

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
            assertThat(cr.getCount()).isEqualTo(1);

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
            assertThat(cr.getCount()).isEqualTo(1);

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
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 3. Delete local-only item
        deleteMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 4. Re-add local-only item
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // 5. Delete cloud+local item
        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_AND_LOCAL_1);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

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
        assertThat(mController.getLocalProvider()).isEqualTo(LOCAL_PROVIDER_AUTHORITY);

        // Assert that no cloud provider set on facade
        assertThat(mFacade.getCloudProvider()).isNull();

        // 2. Can set cloud provider
        assertThat(mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertThat(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert that setting cloud provider clears facade cloud provider
        // And after syncing, the latest provider is set on the facade
        assertThat(mFacade.getCloudProvider()).isNull();
        mController.syncAllMedia();
        assertThat(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 3. Can clear cloud provider
        assertThat(setCloudProviderAndSyncAllMedia(null)).isTrue();
        assertThat(mController.getCloudProvider()).isNull();

        // Assert that setting cloud provider clears facade cloud provider
        // And after syncing, the latest provider is set on the facade
        assertThat(mFacade.getCloudProvider()).isNull();
        mController.syncAllMedia();
        assertThat(mFacade.getCloudProvider()).isNull();

        // 4. Can set cloud proivder
        assertThat(mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertThat(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert that setting cloud provider clears facade cloud provider
        // And after syncing, the latest provider is set on the facade
        assertThat(mFacade.getCloudProvider()).isNull();
        mController.syncAllMedia();
        assertThat(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Invalid cloud provider is ignored
        assertThat(setCloudProviderAndSyncAllMedia(LOCAL_PROVIDER_AUTHORITY)).isFalse();
        assertThat(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert that unsuccessfully setting cloud provider doesn't clear facade cloud provider
        // And after syncing, nothing changes
        assertThat(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncAllMedia();
        assertThat(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
    }

    @Test
    public void testGetSupportedCloudProviders() {
        List<CloudProviderInfo> providers = mController.getAvailableCloudProviders();

        CloudProviderInfo primaryInfo = new CloudProviderInfo(CLOUD_PRIMARY_PROVIDER_AUTHORITY,
                PACKAGE_NAME,
                Process.myUid());
        CloudProviderInfo secondaryInfo = new CloudProviderInfo(CLOUD_SECONDARY_PROVIDER_AUTHORITY,
                PACKAGE_NAME,
                Process.myUid());

        assertThat(providers).containsExactly(primaryInfo, secondaryInfo);
    }

    @Test
    public void testGetSupportedCloudProviders_useAllowList() {
        final CloudProviderInfo primaryInfo = new CloudProviderInfo(
                CLOUD_PRIMARY_PROVIDER_AUTHORITY, PACKAGE_NAME, Process.myUid());
        final CloudProviderInfo secondaryInfo = new CloudProviderInfo(
                CLOUD_SECONDARY_PROVIDER_AUTHORITY, PACKAGE_NAME, Process.myUid());

        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);
        final PickerSyncController controller = new PickerSyncController(
                mContext, mFacade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);
        final List<CloudProviderInfo> providers = controller.getAvailableCloudProviders();
        assertThat(providers).containsExactly(primaryInfo, secondaryInfo);
    }

    @Test
    public void testNotifyPackageRemoval_NoDefaultCloudProviderPackage() {
        mConfigStore.clearDefaultCloudProviderPackage();

        assertThat(mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertThat(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert passing wrong package name doesn't clear the current cloud provider
        mController.notifyPackageRemoval(PACKAGE_NAME + "invalid");
        assertThat(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert passing the current cloud provider package name clears the current cloud provider
        mController.notifyPackageRemoval(PACKAGE_NAME);
        assertThat(mController.getCloudProvider()).isNull();

        // Assert that the cloud provider state was not UNSET after the last cloud provider removal
        mConfigStore.setDefaultCloudProviderPackage(PACKAGE_NAME);

        mController =
                new PickerSyncController(mContext, mFacade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        assertThat(mController.getCurrentCloudProviderInfo().packageName).isEqualTo(PACKAGE_NAME);
    }

    // TODO(b/278687585): Add test for PickerSyncController#notifyPackageRemoval with a different
    //  non-null default cloud provider package

    @Test
    public void testSelectDefaultCloudProvider_NoDefaultAuthority() {
        PickerSyncController controller = createControllerWithDefaultProvider(null);
        assertThat(controller.getCloudProvider()).isNull();
    }

    @Test
    public void testSelectDefaultCloudProvider_defaultAuthoritySet() {
        PickerSyncController controller = createControllerWithDefaultProvider(PACKAGE_NAME);
        assertThat(controller.getCurrentCloudProviderInfo().packageName).isEqualTo(PACKAGE_NAME);
    }

    @Test
    public void testIsProviderAuthorityEnabled() {
        assertThat(mController.isProviderEnabled(LOCAL_PROVIDER_AUTHORITY)).isTrue();
        assertThat(mController.isProviderEnabled(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isFalse();
        assertThat(mController.isProviderEnabled(CLOUD_SECONDARY_PROVIDER_AUTHORITY)).isFalse();

        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        assertThat(mController.isProviderEnabled(LOCAL_PROVIDER_AUTHORITY)).isTrue();
        assertThat(mController.isProviderEnabled(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertThat(mController.isProviderEnabled(CLOUD_SECONDARY_PROVIDER_AUTHORITY)).isFalse();
    }

    @Test
    public void testIsProviderUidEnabled() {
        assertThat(mController.isProviderEnabled(LOCAL_PROVIDER_AUTHORITY, Process.myUid()))
                .isTrue();
        assertThat(mController.isProviderEnabled(LOCAL_PROVIDER_AUTHORITY, 1000)).isFalse();
    }

    @Test
    public void testNotifyMediaEvent() {
        mConfigStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();
        mConfigStore.setPickerSyncDelayMs(SYNC_DELAY_MS);

        final PickerSyncController controller = new PickerSyncController(
                mContext, mFacade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        // 1. Add media and notify
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        controller.notifyMediaEvent();
        waitForIdle();
        assertEmptyCursorFromMediaQuery();

        // 2. Sleep for delay
        SystemClock.sleep(SYNC_DELAY_MS);
        waitForIdle();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testSyncAfterDbCreate() {
        mConfigStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();
        mConfigStore.setPickerSyncDelayMs(0);

        final PickerDatabaseHelper dbHelper = new PickerDatabaseHelper(
                mContext, DB_NAME, DB_VERSION_1);
        PickerDbFacade facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelper);
        PickerSyncController controller = new PickerSyncController(
                mContext, facade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        controller.syncAllMedia();

        try (Cursor cr = queryMedia(facade)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        dbHelper.close();

        // Delete db so it's recreated on next access
        final File dbPath = mContext.getDatabasePath(DB_NAME);
        dbPath.delete();

        facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY, dbHelper);
        controller = new PickerSyncController(
                mContext, facade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        // Initially empty db
        try (Cursor cr = queryMedia(facade)) {
            assertThat(cr.getCount()).isEqualTo(0);
        }

        controller.syncAllMedia();

        // Fully synced db
        try (Cursor cr = queryMedia(facade)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testSyncAfterDbUpgrade() {
        mConfigStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();
        mConfigStore.setPickerSyncDelayMs(SYNC_DELAY_MS);

        PickerDatabaseHelper dbHelperV1 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        PickerDbFacade facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV1);
        PickerSyncController controller = new PickerSyncController(
                mContext, facade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        controller.syncAllMedia();

        try (Cursor cr = queryMedia(facade)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // Upgrade db version
        dbHelperV1.close();
        PickerDatabaseHelper dbHelperV2 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_2);
        facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY, dbHelperV2);
        controller = new PickerSyncController(
                mContext, facade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        // Initially empty db
        try (Cursor cr = queryMedia(facade)) {
            assertThat(cr.getCount()).isEqualTo(0);
        }

        controller.syncAllMedia();

        // Fully synced db
        try (Cursor cr = queryMedia(facade)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testSyncAfterDbDowngrade() {
        mConfigStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();
        mConfigStore.setPickerSyncDelayMs(SYNC_DELAY_MS);

        PickerDatabaseHelper dbHelperV2 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_2);
        PickerDbFacade facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV2);
        PickerSyncController controller = new PickerSyncController(
                mContext, facade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        controller.syncAllMedia();

        try (Cursor cr = queryMedia(facade)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }

        // Downgrade db version
        dbHelperV2.close();
        PickerDatabaseHelper dbHelperV1 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV1);
        controller = new PickerSyncController(
                mContext, facade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        // Initially empty db
        try (Cursor cr = queryMedia(facade)) {
            assertThat(cr.getCount()).isEqualTo(0);
        }

        controller.syncAllMedia();

        // Fully synced db
        try (Cursor cr = queryMedia(facade)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ID_1, LOCAL_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testAllMediaSyncValidationFailure_incorrectMediaCollectionId() {
        // 1. Set cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 2. Force the next 2 syncs (including retry) to have correct extra_media_collection_id
        mCloudPrimaryMediaGenerator.setNextCursorExtras(2, COLLECTION_1,
                /* honoredSyncGeneration */ true, /* honoredAlbumId */ false);

        // 4. Add cloud media
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);

        // 5. Sync and verify media
        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 6. Force the next sync (without retry) to have incorrect extra_media_collection_id
        mCloudPrimaryMediaGenerator.setNextCursorExtras(1, COLLECTION_2,
                /* honoredSyncGeneration */ true, /* honoredAlbumId */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);

        // 7. Sync and verify media after retry succeeded
        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 8. Force the next 2 syncs (including retry) to have incorrect extra_media_collection_id
        mCloudPrimaryMediaGenerator.setNextCursorExtras(2, COLLECTION_2,
                /* honoredSyncGeneration */ true, /* honoredAlbumId */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_3);

        // 9. Sync and verify media was reset
        mController.syncAllMedia();
        assertEmptyCursorFromMediaQuery();
    }

    @Test
    public void testAllMediaSyncValidationRecovery_missingSyncGenerationHonoredArg() {
        // 1. Set cloud provider
        setCloudProviderAndSyncAllMedia(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 2. Force the next 2 syncs (including retry) to have correct extra_media_collection_id
        mCloudPrimaryMediaGenerator.setNextCursorExtras(2, COLLECTION_1,
                /* honoredSyncGeneration */ true, /* honoredAlbumId */ false);

        // 3. Add cloud media
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);

        // 4. Sync and verify media
        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 5. Force the next sync (without retry) to have incorrect extra_honored_args
        mCloudPrimaryMediaGenerator.setNextCursorExtras(1, COLLECTION_1,
                /* honoredSyncGeneration */ false, /* honoredAlbumId */ false);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);

        // 6. Sync and verify media after retry succeeded
        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(2);

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
                /* honoredSyncGeneration */ false, /* honoredAlbumId */ true);

        // 3. Add cloud album_media
        addAlbumMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1.first, CLOUD_ONLY_1.second,
                ALBUM_ID_1);

        // 4. Sync and verify album_media
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        try (Cursor cr = queryAlbumMedia(ALBUM_ID_1, false)) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 5. Force the next sync to have incorrect extra_album_id
        mCloudPrimaryMediaGenerator.setNextCursorExtras(1, COLLECTION_1,
                /* honoredSyncGeneration */ false, /* honoredAlbumId */ false);

        // 6. Sync and verify album_media is empty
        mController.syncAlbumMedia(ALBUM_ID_1, false);
        assertEmptyCursorFromAlbumMediaQuery(ALBUM_ID_1, false);
    }

    @Test
    public void testUserPrefsAfterDbUpgrade() {
        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);
        mConfigStore.setPickerSyncDelayMs(SYNC_DELAY_MS);

        PickerDatabaseHelper dbHelperV1 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        PickerDbFacade facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV1);
        PickerSyncController controller =
                new PickerSyncController(mContext, facade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        controller.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        assertThat(controller.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Downgrade db version
        dbHelperV1.close();
        PickerDatabaseHelper dbHelperV2 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_2);
        facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV2);
        controller = new PickerSyncController(
                mContext, facade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        assertThat(controller.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
    }

    @Test
    public void testCloudProviderUnset() {
        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(PACKAGE_NAME);
        mConfigStore.setDefaultCloudProviderPackage(PACKAGE_NAME);

        // Test the default NOT_SET state
        mController =
                new PickerSyncController(mContext, mFacade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        assertThat(mController.getCurrentCloudProviderInfo().packageName).isEqualTo(PACKAGE_NAME);

        // Set and test the UNSET state
        mController.setCloudProvider(/* authority */ null);

        mController =
                new PickerSyncController(mContext, mFacade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        assertThat(mController.getCloudProvider()).isNull();

        // Set and test the SET state
        mController.setCloudProvider(CLOUD_SECONDARY_PROVIDER_AUTHORITY);

        mController =
                new PickerSyncController(mContext, mFacade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);

        assertThat(mController.getCloudProvider()).isEqualTo(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
    }

    @Test
    public void testAvailableCloudProviders_CloudFeatureDisabled() {
        assertThat(mController.getAvailableCloudProviders()).isNotEmpty();
        mConfigStore.disableCloudMediaFeature();
        assertThat(mController.getAvailableCloudProviders()).isEmpty();
    }

    private static void waitForIdle() {
        final CountDownLatch latch = new CountDownLatch(1);
        BackgroundThread.getExecutor().execute(latch::countDown);
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
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
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    private void assertEmptyCursorFromAlbumMediaQuery(String albumId, boolean isLocal) {
        try (Cursor cr = queryAlbumMedia(albumId, isLocal)) {
            assertThat(cr.getCount()).isEqualTo(0);
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
        mConfigStore.setPickerSyncDelayMs(SYNC_DELAY_MS);

        return new PickerSyncController(
                mockContext, mFacade, mConfigStore, LOCAL_PROVIDER_AUTHORITY);
    }

    private static void assertCursor(Cursor cursor, String id, String expectedAuthority) {
        cursor.moveToNext();
        assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.ID)))
                .isEqualTo(id);
        assertThat(cursor.getString(cursor.getColumnIndex( MediaColumns.AUTHORITY)))
                .isEqualTo(expectedAuthority);
    }
}
