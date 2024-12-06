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

package com.android.providers.media.photopicker.v2;

import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_PROVIDER;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAddMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getCloudMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getLocalMediaCursor;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.database.Cursor;

import androidx.test.InstrumentationRegistry;

import com.android.providers.media.cloudproviders.SearchProvider;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.v2.sqlite.MediaGroupCursorUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.List;
import java.util.Map;

public class MediaGroupCursorUtilsTest {
    @Mock
    private PickerSyncController mMockSyncController;
    private PickerDbFacade mFacade;
    private Context mContext;

    @Before
    public void setUp() {
        initMocks(this);
        PickerSyncController.setInstance(mMockSyncController);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        mFacade = new PickerDbFacade(mContext, new PickerSyncLockManager(), LOCAL_PROVIDER);
        mFacade.setCloudProvider(SearchProvider.AUTHORITY);

        doReturn(mFacade).when(mMockSyncController).getDbFacade();
        doReturn(LOCAL_PROVIDER).when(mMockSyncController).getLocalProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController)
                .getCloudProviderOrDefault(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());
    }

    @After
    public void tearDown() {
        if (mFacade != null) {
            mFacade.setCloudProvider(null);
        }
    }

    @Test
    public void testGetLocalIdForCloudUri() {
        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, SearchProvider.AUTHORITY, cursor2, 1);
        final Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        final List<String> mediaUris = List.of(
                "content://" + SearchProvider.AUTHORITY + "/" + CLOUD_ID_1,
                "content://" + LOCAL_PROVIDER + "/" + LOCAL_ID_1
        );

        final Map<String, String> result = MediaGroupCursorUtils.getLocalIds(mediaUris);

        assertWithMessage("Result map should not be null")
                .that(result)
                .isNotNull();
        assertWithMessage("Result map size is not as expected")
                .that(result.size())
                .isEqualTo(1);
        assertWithMessage("Result map should contain cloud id as key")
                .that(result.containsKey(CLOUD_ID_1))
                .isTrue();
        assertWithMessage("Mapped local id is incorrect")
                .that(result.get(CLOUD_ID_1))
                .isEqualTo(LOCAL_ID_1);
    }

    @Test
    public void testGetLocalIdForCloudUriNoMatch() {
        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_1, /* localId */ null, 0);
        assertAddMediaOperation(mFacade, SearchProvider.AUTHORITY, cursor2, 1);
        final Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        final List<String> mediaUris = List.of(
                "content://" + SearchProvider.AUTHORITY + "/" + CLOUD_ID_1
        );

        final Map<String, String> result = MediaGroupCursorUtils.getLocalIds(mediaUris);

        assertWithMessage("Result map should not be null")
                .that(result)
                .isNotNull();
        assertWithMessage("Result map size is not as expected")
                .that(result.size())
                .isEqualTo(0);
    }

    @Test
    public void testGetValidLocalIdForCloudUri() {
        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, SearchProvider.AUTHORITY, cursor2, 1);

        final List<String> mediaUris = List.of(
                "content://" + SearchProvider.AUTHORITY + "/" + CLOUD_ID_1
        );

        final Map<String, String> result = MediaGroupCursorUtils.getLocalIds(mediaUris);

        assertWithMessage("Result map should not be null")
                .that(result)
                .isNotNull();
        assertWithMessage("Result map size is not as expected")
                .that(result.size())
                .isEqualTo(0);
    }

    @Test
    public void testGetValidLocalIdForEmptyUriList() {
        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_1, /* localId */ null, 0);
        assertAddMediaOperation(mFacade, SearchProvider.AUTHORITY, cursor2, 1);
        final Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        final List<String> mediaUris = List.of();

        final Map<String, String> result = MediaGroupCursorUtils.getLocalIds(mediaUris);

        assertWithMessage("Result map should not be null")
                .that(result)
                .isNotNull();
        assertWithMessage("Result map size is not as expected")
                .that(result.size())
                .isEqualTo(0);
    }
}
