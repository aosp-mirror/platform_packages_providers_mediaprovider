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

import static com.android.providers.media.PickerProviderMediaGenerator.ALBUM_COLUMN_TYPE_CLOUD;
import static com.android.providers.media.PickerProviderMediaGenerator.MediaGenerator;
import static com.android.providers.media.photopicker.PickerSyncController.CloudProviderInfo;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LONG_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_DEFAULT;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.provider.CloudMediaProviderContract.MediaColumns;
import android.provider.MediaStore;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.PickerProviderMediaGenerator;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PickerSyncControllerTest {
    private static final String TAG = "PickerSyncControllerTest";

    private static final String LOCAL_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.local";
    private static final String CLOUD_PRIMARY_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.cloud_primary";
    private static final String CLOUD_SECONDARY_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker.tests.cloud_secondary";

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

    private static final String ALBUM_ID_1 = "1";
    private static final String ALBUM_ID_2 = "2";

    private static final String MIME_TYPE_DEFAULT = STRING_DEFAULT;
    private static final long SIZE_BYTES_DEFAULT = LONG_DEFAULT;

    private static final Pair<String, String> LOCAL_ONLY_1 = Pair.create(LOCAL_ID_1, null);
    private static final Pair<String, String> LOCAL_ONLY_2 = Pair.create(LOCAL_ID_2, null);
    private static final Pair<String, String> CLOUD_ONLY_1 = Pair.create(null, CLOUD_ID_1);
    private static final Pair<String, String> CLOUD_ONLY_2 = Pair.create(null, CLOUD_ID_2);
    private static final Pair<String, String> CLOUD_AND_LOCAL_1
            = Pair.create(LOCAL_ID_1, CLOUD_ID_1);

    private static final String VERSION_1 = "1";
    private static final String VERSION_2 = "2";

    private static final String IMAGE_MIME_TYPE = "image/jpeg";
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final long SIZE_BYTES = 50;

    private static final long SYNC_DELAY_MS = 1000;

    private static final int DB_VERSION_1 = 1;
    private static final int DB_VERSION_2 = 2;
    private static final String DB_NAME = "test_db";

    private Context mContext;
    private PickerDatabaseHelper mDbHelper;
    private PickerDbFacade mFacade;
    private PickerSyncController mController;

    @Before
    public void setUp() {
        mLocalMediaGenerator.resetAll();
        mCloudPrimaryMediaGenerator.resetAll();
        mCloudSecondaryMediaGenerator.resetAll();

        mLocalMediaGenerator.setVersion(VERSION_1);
        mCloudPrimaryMediaGenerator.setVersion(VERSION_1);
        mCloudSecondaryMediaGenerator.setVersion(VERSION_1);

        mContext = InstrumentationRegistry.getTargetContext();

        // Delete db so it's recreated on next access and previous test state is cleared
        final File dbPath = mContext.getDatabasePath(DB_NAME);
        dbPath.delete();

        mDbHelper = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        mFacade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY, mDbHelper);
        mController = new PickerSyncController(mContext, mFacade, LOCAL_PROVIDER_AUTHORITY,
                /* syncDelay */ 0);

        // Set cloud provider to null to avoid trying to sync it during other tests
        // that might be using an IsolatedContext
        mController.setCloudProvider(null);

        Assume.assumeTrue(PickerDbFacade.isPickerDbEnabled());
    }

    @Test
    public void testSyncAllMediaLocalOnly() {
        // 1. Do nothing
        mController.syncAllMedia();
        assertEmptyCursor();

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
        mLocalMediaGenerator.setVersion(VERSION_2);
        mController.syncAllMedia();

        assertEmptyCursor();
    }


    @Test
    public void testSyncAllMediaCloudOnly() {
        // 1. Add media before setting primary cloud provider
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);
        mController.syncAllMedia();
        assertEmptyCursor();

        // 2. Set secondary cloud provider
        mController.setCloudProvider(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
        mController.syncAllMedia();
        assertEmptyCursor();

        // 3. Set primary cloud provider
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 4. Set secondary cloud provider again
        mController.setCloudProvider(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
        mController.syncAllMedia();
        assertEmptyCursor();

        // 5. Set primary cloud provider once again
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ID_2, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 6. Clear cloud provider
        mController.setCloudProvider(/* authority */ null);
        mController.syncAllMedia();
        assertEmptyCursor();
    }

    @Test
    public void testCloudResetSync() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 1. Do nothing
        mController.syncAllMedia();
        assertEmptyCursor();

        // 2. Add cloud-only item
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);

        mController.syncAllMedia();
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        // 3. Set invalid cloud version
        mCloudPrimaryMediaGenerator.setVersion(/* version */ null);
        mController.syncAllMedia();
        assertEmptyCursor();

        // 4. Set valid cloud version
        mCloudPrimaryMediaGenerator.setVersion(VERSION_1);
        mController.syncAllMedia();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ID_1, CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }
    }

    @Test
    public void testSyncAllMediaCloudAndLocal() {
        // 1. Do nothing
        assertEmptyCursor();

        // 2. Set primary cloud provider and add 2 items: cloud+local and local-only
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_AND_LOCAL_1);

        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncAllMedia();

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

        assertEmptyCursor();
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
        assertThat(mController.setCloudProvider(null)).isTrue();
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
        assertThat(mController.setCloudProvider(LOCAL_PROVIDER_AUTHORITY)).isFalse();
        assertThat(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert that unsuccessfully setting cloud provider doesn't clear facade cloud provider
        // And after syncing, nothing changes
        assertThat(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncAllMedia();
        assertThat(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
    }

    @Test
    public void testGetSupportedCloudProviders() {
        List<CloudProviderInfo> providers = mController.getSupportedCloudProviders();

        CloudProviderInfo primaryInfo = new CloudProviderInfo(CLOUD_PRIMARY_PROVIDER_AUTHORITY,
                Process.myUid());
        CloudProviderInfo secondaryInfo = new CloudProviderInfo(CLOUD_SECONDARY_PROVIDER_AUTHORITY,
                Process.myUid());

        assertThat(providers).containsExactly(primaryInfo, secondaryInfo);
    }

    @Test
    public void testIsProviderAuthorityEnabled() {
        assertThat(mController.isProviderEnabled(LOCAL_PROVIDER_AUTHORITY)).isTrue();
        assertThat(mController.isProviderEnabled(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isFalse();
        assertThat(mController.isProviderEnabled(CLOUD_SECONDARY_PROVIDER_AUTHORITY)).isFalse();

        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        assertThat(mController.isProviderEnabled(LOCAL_PROVIDER_AUTHORITY)).isTrue();
        assertThat(mController.isProviderEnabled(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertThat(mController.isProviderEnabled(CLOUD_SECONDARY_PROVIDER_AUTHORITY)).isFalse();
    }

    @Test
    public void testIsProviderUidEnabled() {
        assertThat(mController.isProviderEnabled(Process.myUid())).isTrue();
        assertThat(mController.isProviderEnabled(1000)).isFalse();
    }

    @Test
    public void testNotifyMediaEvent() {
        PickerSyncController controller = new PickerSyncController(mContext, mFacade,
                LOCAL_PROVIDER_AUTHORITY, SYNC_DELAY_MS);

        // 1. Add media and notify
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        controller.notifyMediaEvent();
        waitForIdle();
        assertEmptyCursor();

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
        PickerDatabaseHelper dbHelper = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        PickerDbFacade facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelper);
        PickerSyncController controller = new PickerSyncController(mContext, facade,
                LOCAL_PROVIDER_AUTHORITY, /* syncDelay */ 0);

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
        controller = new PickerSyncController(mContext, facade,
                LOCAL_PROVIDER_AUTHORITY, /* syncDelay */ 0);

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
        PickerDatabaseHelper dbHelperV1 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        PickerDbFacade facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV1);
        PickerSyncController controller = new PickerSyncController(mContext, facade,
                LOCAL_PROVIDER_AUTHORITY, SYNC_DELAY_MS);

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
        controller = new PickerSyncController(mContext, facade,
                LOCAL_PROVIDER_AUTHORITY, SYNC_DELAY_MS);

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
        PickerDatabaseHelper dbHelperV2 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_2);
        PickerDbFacade facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV2);
        PickerSyncController controller = new PickerSyncController(mContext, facade,
                LOCAL_PROVIDER_AUTHORITY, SYNC_DELAY_MS);

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
        controller = new PickerSyncController(mContext, facade,
                LOCAL_PROVIDER_AUTHORITY, SYNC_DELAY_MS);

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
    public void testUserPrefsAfterDbUpgrade() {
        PickerDatabaseHelper dbHelperV1 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_1);
        PickerDbFacade facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV1);
        PickerSyncController controller = new PickerSyncController(mContext, facade,
                LOCAL_PROVIDER_AUTHORITY, SYNC_DELAY_MS);

        controller.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        assertThat(controller.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Downgrade db version
        dbHelperV1.close();
        PickerDatabaseHelper dbHelperV2 = new PickerDatabaseHelper(mContext, DB_NAME, DB_VERSION_2);
        facade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY,
                dbHelperV2);
        controller = new PickerSyncController(mContext, facade,
                LOCAL_PROVIDER_AUTHORITY, SYNC_DELAY_MS);

        assertThat(controller.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
    }

    private static void waitForIdle() {
        final CountDownLatch latch = new CountDownLatch(1);
        BackgroundThread.getExecutor().execute(() -> {
            latch.countDown();
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }

    }

    private static Bundle buildQueryArgs(String mimeType, long sizeBytes) {
        final Bundle queryArgs = new Bundle();

        queryArgs.putString(MediaStore.QUERY_ARG_MIME_TYPE, mimeType);
        queryArgs.putLong(MediaStore.QUERY_ARG_SIZE_BYTES, sizeBytes);

        return queryArgs;
    }

    private static Bundle buildQueryArgs(String albumId, String albumType, String mimeType,
            long sizeBytes) {
        final Bundle queryArgs = buildQueryArgs(mimeType, sizeBytes);

        queryArgs.putString(MediaStore.QUERY_ARG_ALBUM_ID, albumId);
        queryArgs.putString(MediaStore.QUERY_ARG_ALBUM_TYPE, albumType);

        if (Objects.equals(albumType, ALBUM_COLUMN_TYPE_CLOUD)) {
            queryArgs.putString(MediaStore.EXTRA_CLOUD_PROVIDER,
                    CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        }

        return queryArgs;
    }

    private static void addMedia(MediaGenerator generator, Pair<String, String> media) {
        generator.addMedia(media.first, media.second);
    }

    private static void addMedia(MediaGenerator generator, Pair<String, String> media,
            String albumId, String mimeType, int standardMimeTypeExtension, long sizeBytes,
            boolean isFavorite) {
        generator.addMedia(media.first, media.second, albumId, mimeType, standardMimeTypeExtension,
                sizeBytes, isFavorite);
    }

    private static void deleteMedia(MediaGenerator generator, Pair<String, String> media) {
        generator.deleteMedia(media.first, media.second);
    }

    private static Cursor queryMedia(PickerDbFacade facade) {
        return facade.queryMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).build());
    }

    private Cursor queryMedia() {
        return mFacade.queryMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).build());
    }

    private void assertEmptyCursor() {
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    private static void assertCursor(Cursor cursor, String id, String expectedAuthority) {
        cursor.moveToNext();
        assertThat(cursor.getString(cursor.getColumnIndex(MediaColumns.ID)))
                .isEqualTo(id);

        final int authorityIdx = cursor.getColumnIndex(MediaColumns.AUTHORITY);
        final String authority;
        if (authorityIdx >= 0) {
            // Cursor from picker db has authority as a column
            authority = cursor.getString(authorityIdx);
        } else {
            // Cursor from provider directly doesn't have an authority column but will
            // have the authority set as an extra
            final Bundle bundle = cursor.getExtras();
            authority = bundle.getString(MediaColumns.AUTHORITY);
        }
        assertThat(authority).isEqualTo(expectedAuthority);
    }
}
