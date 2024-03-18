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

import static org.junit.Assert.assertEquals;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.os.Process;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.v2.model.MediaSource;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class PickerDataLayerTest {
    @Mock
    private PickerSyncController mMockSyncController;
    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mMockPackageManager;

    @Before
    public void setup() {
        initMocks(this);
        PickerSyncController.setInstance(mMockSyncController);
    }

    @Test
    public void testAvailableProvidersNoCloudProvider() {
        final String localProviderAuthority = "local.provider.authority";

        doReturn(localProviderAuthority)
                .when(mMockSyncController).getLocalProvider();
        doReturn(/* cloudProviderAuthority */ null)
                .when(mMockSyncController).getCloudProvider();

        try (Cursor availableProviders = PickerDataLayerV2.queryAvailableProviders(mMockContext)) {
            availableProviders.moveToFirst();

            assertEquals(
                    "Only local provider should be available when cloud provider is null",
                    /* expected */ 1,
                    availableProviders.getCount()
            );

            assertEquals(
                    "Available provider should serve local media",
                    /* expected */ MediaSource.LOCAL,
                    MediaSource.valueOf(availableProviders.getString(
                            availableProviders.getColumnIndex(
                                    PickerDataLayerV2.AVAILABLE_PROVIDERS_MEDIA_SOURCE_COLUMN)))
            );

            assertEquals(
                    "Local provider authority is not correct",
                    /* expected */ localProviderAuthority,
                    availableProviders.getString(
                            availableProviders.getColumnIndex(
                                    PickerDataLayerV2.AVAILABLE_PROVIDER_AUTHORITY_COLUMN))
            );

            assertEquals(
                    "Local provider UID is not correct",
                    /* expected */ Process.myUid(),
                    availableProviders.getInt(
                            availableProviders.getColumnIndex(
                                    PickerDataLayerV2.AVAILABLE_PROVIDERS_UID_COLUMN))
            );
        }
    }

    @Test
    public void testAvailableProvidersWithCloudProvider() throws
            PackageManager.NameNotFoundException {
        final String localProviderAuthority = "local.provider.authority";
        final String cloudProviderAuthority = "cloud.provider.authority";
        final int cloudUID = Integer.MAX_VALUE;
        final ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.packageName = cloudProviderAuthority;

        doReturn(localProviderAuthority)
                .when(mMockSyncController).getLocalProvider();
        doReturn(cloudProviderAuthority)
                .when(mMockSyncController).getCloudProvider();
        doReturn(mMockPackageManager)
                .when(mMockContext).getPackageManager();
        doReturn(cloudUID)
                .when(mMockPackageManager)
                .getPackageUid(cloudProviderAuthority, 0);
        doReturn(providerInfo)
                .when(mMockPackageManager)
                .resolveContentProvider(cloudProviderAuthority, 0);

        try (Cursor availableProviders = PickerDataLayerV2.queryAvailableProviders(mMockContext)) {
            availableProviders.moveToFirst();

            assertEquals(
                    "Both local and cloud providers should be available",
                    /* expected */ 2,
                    availableProviders.getCount()
            );

            assertEquals(
                    "Available provider should serve local media",
                    /* expected */ MediaSource.LOCAL,
                    MediaSource.valueOf(availableProviders.getString(
                            availableProviders.getColumnIndex(
                                    PickerDataLayerV2.AVAILABLE_PROVIDERS_MEDIA_SOURCE_COLUMN)))
            );

            assertEquals(
                    "Local provider authority is not correct",
                    /* expected */ localProviderAuthority,
                    availableProviders.getString(
                            availableProviders.getColumnIndex(
                                    PickerDataLayerV2.AVAILABLE_PROVIDER_AUTHORITY_COLUMN))
            );

            assertEquals(
                    "Local provider UID is not correct",
                    /* expected */ Process.myUid(),
                    availableProviders.getInt(
                            availableProviders.getColumnIndex(
                                    PickerDataLayerV2.AVAILABLE_PROVIDERS_UID_COLUMN))
            );

            availableProviders.moveToNext();

            assertEquals(
                    "Available provider should serve remote media",
                    /* expected */ MediaSource.REMOTE,
                    MediaSource.valueOf(availableProviders.getString(
                            availableProviders.getColumnIndex(
                                    PickerDataLayerV2.AVAILABLE_PROVIDERS_MEDIA_SOURCE_COLUMN)))
            );

            assertEquals(
                    "Cloud provider authority is not correct",
                    /* expected */ cloudProviderAuthority,
                    availableProviders.getString(
                            availableProviders.getColumnIndex(
                                    PickerDataLayerV2.AVAILABLE_PROVIDER_AUTHORITY_COLUMN))
            );

            assertEquals(
                    "Cloud provider UID is not correct",
                    /* expected */ cloudUID,
                    availableProviders.getInt(
                            availableProviders.getColumnIndex(
                                    PickerDataLayerV2.AVAILABLE_PROVIDERS_UID_COLUMN))
            );
        }
    }
}
