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
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_CLOUD_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_LOCAL_ID;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.PickerProviderMediaGenerator;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.util.BackgroundThread;

import java.util.List;

import org.junit.After;
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

    private static final Pair<String, String> LOCAL_ONLY_1 = Pair.create(LOCAL_ID_1, null);
    private static final Pair<String, String> LOCAL_ONLY_2 = Pair.create(LOCAL_ID_2, null);
    private static final Pair<String, String> CLOUD_ONLY_1 = Pair.create(null, CLOUD_ID_1);
    private static final Pair<String, String> CLOUD_ONLY_2 = Pair.create(null, CLOUD_ID_2);
    private static final Pair<String, String> CLOUD_AND_LOCAL_1
            = Pair.create(LOCAL_ID_1, CLOUD_ID_1);

    private static final String VERSION_1 = "1";
    private static final String VERSION_2 = "2";

    private static final long SYNC_DELAY_MS = 1000;

    private Context mContext;
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
        mFacade = new PickerDbFacade(mContext, LOCAL_PROVIDER_AUTHORITY);
        mController = new PickerSyncController(mContext, mFacade, LOCAL_PROVIDER_AUTHORITY,
                /* syncDelay */ 0);

        mFacade.resetMedia(LOCAL_PROVIDER_AUTHORITY);
        mFacade.resetMedia(null);
    }

    @After
    public void tearDown() {
        // Set cloud provider to null to avoid trying to sync it during other tests
        // that might be using an IsolatedContext
        mController.setCloudProvider(null);
    }

    @Test
    public void testSyncPickerLocalOnly() {
        // 1. Do nothing
        mController.syncPicker();
        assertEmptyCursor();

        // 2. Add local only media
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_2);

        mController.syncPicker();
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, LOCAL_ONLY_2);
            assertCursor(cr, LOCAL_ONLY_1);
        }

        // 3. Delete one local-only media
        deleteMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncPicker();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ONLY_2);
        }

        // 4. Reset media without version bump
        mLocalMediaGenerator.resetAll();
        mController.syncPicker();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ONLY_2);
        }

        // 5. Bump version
        mLocalMediaGenerator.setVersion(VERSION_2);
        mController.syncPicker();

        assertEmptyCursor();
    }


    @Test
    public void testSyncPickerCloudOnly() {
        // 1. Add media before setting primary cloud provider
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_2);
        mController.syncPicker();
        assertEmptyCursor();

        // 2. Set secondary cloud provider
        mController.setCloudProvider(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
        mController.syncPicker();
        assertEmptyCursor();

        // 3. Set primary cloud provider
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncPicker();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ONLY_2);
            assertCursor(cr, CLOUD_ONLY_1);
        }

        // 4. Set secondary cloud provider again
        mController.setCloudProvider(CLOUD_SECONDARY_PROVIDER_AUTHORITY);
        mController.syncPicker();
        assertEmptyCursor();

        // 5. Set primary cloud provider once again
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncPicker();
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(2);

            assertCursor(cr, CLOUD_ONLY_2);
            assertCursor(cr, CLOUD_ONLY_1);
        }

        // 6. Clear cloud provider
        mController.setCloudProvider(/* authority */ null);
        mController.syncPicker();
        assertEmptyCursor();
    }

    @Test
    public void testCloudResetSync() {
        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 1. Do nothing
        mController.syncPicker();
        assertEmptyCursor();

        // 2. Add cloud-only item
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_ONLY_1);

        mController.syncPicker();
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ONLY_1);
        }

        // 3. Set invalid cloud version
        mCloudPrimaryMediaGenerator.setVersion(/* version */ null);
        mController.syncPicker();
        assertEmptyCursor();

        // 4. Set valid cloud version
        mCloudPrimaryMediaGenerator.setVersion(VERSION_1);
        mController.syncPicker();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_ONLY_1);
        }
    }

    @Test
    public void testSyncPickerCloudAndLocal() {
        // 1. Do nothing
        assertEmptyCursor();

        // 2. Set primary cloud provider and add 2 items: cloud+local and local-only
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        addMedia(mCloudPrimaryMediaGenerator, CLOUD_AND_LOCAL_1);

        mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncPicker();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ONLY_1);
        }

        // 3. Delete local-only item
        deleteMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncPicker();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, CLOUD_AND_LOCAL_1);
        }

        // 4. Re-add local-only item
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncPicker();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ONLY_1);
        }

        // 5. Delete cloud+local item
        deleteMedia(mCloudPrimaryMediaGenerator, CLOUD_AND_LOCAL_1);
        mController.syncPicker();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ONLY_1);
        }

        // 6. Delete local-only item
        deleteMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        mController.syncPicker();

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
        mController.syncPicker();
        assertThat(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // 3. Can clear cloud provider
        assertThat(mController.setCloudProvider(null)).isTrue();
        assertThat(mController.getCloudProvider()).isNull();

        // Assert that setting cloud provider clears facade cloud provider
        // And after syncing, the latest provider is set on the facade
        assertThat(mFacade.getCloudProvider()).isNull();
        mController.syncPicker();
        assertThat(mFacade.getCloudProvider()).isNull();

        // 4. Can set cloud proivder
        assertThat(mController.setCloudProvider(CLOUD_PRIMARY_PROVIDER_AUTHORITY)).isTrue();
        assertThat(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert that setting cloud provider clears facade cloud provider
        // And after syncing, the latest provider is set on the facade
        assertThat(mFacade.getCloudProvider()).isNull();
        mController.syncPicker();
        assertThat(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Invalid cloud provider is ignored
        assertThat(mController.setCloudProvider(LOCAL_PROVIDER_AUTHORITY)).isFalse();
        assertThat(mController.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);

        // Assert that unsuccessfully setting cloud provider doesn't clear facade cloud provider
        // And after syncing, nothing changes
        assertThat(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
        mController.syncPicker();
        assertThat(mFacade.getCloudProvider()).isEqualTo(CLOUD_PRIMARY_PROVIDER_AUTHORITY);
    }

    @Test
    public void testGetSupportedCloudProviders() {
        List<String> providers = mController.getSupportedCloudProviders();

        assertThat(providers).containsExactly(CLOUD_PRIMARY_PROVIDER_AUTHORITY,
                CLOUD_SECONDARY_PROVIDER_AUTHORITY);
    }

    @Test
    public void testNotifyMediaEvent() {
        PickerSyncController controller = new PickerSyncController(mContext, mFacade,
                LOCAL_PROVIDER_AUTHORITY, SYNC_DELAY_MS);

        // 1. Add media and notify
        addMedia(mLocalMediaGenerator, LOCAL_ONLY_1);
        controller.notifyMediaEvent();
        BackgroundThread.waitForIdle();
        assertEmptyCursor();

        // 2. Sleep for delay
        SystemClock.sleep(SYNC_DELAY_MS);
        BackgroundThread.waitForIdle();

        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(1);

            assertCursor(cr, LOCAL_ONLY_1);
        }
    }

    private static void addMedia(MediaGenerator generator, Pair<String, String> media) {
        generator.addMedia(media.first, media.second);
    }

    private static void deleteMedia(MediaGenerator generator, Pair<String, String> media) {
        generator.deleteMedia(media.first, media.second);
    }

    private Cursor queryMedia() {
        return mFacade.queryMediaAll(/* limit */ 1000, /* mimeTypeFilter */ null,
                /* sizeBytesMax */ 0);
    }

    private void assertEmptyCursor() {
        try (Cursor cr = queryMedia()) {
            assertThat(cr.getCount()).isEqualTo(0);
        }
    }

    private static void assertCursor(Cursor cursor, Pair<String, String> media) {
        cursor.moveToNext();
        assertThat(cursor.getString(cursor.getColumnIndex(KEY_LOCAL_ID))).isEqualTo(media.first);
        assertThat(cursor.getString(cursor.getColumnIndex(KEY_CLOUD_ID))).isEqualTo(media.second);
    }
}
