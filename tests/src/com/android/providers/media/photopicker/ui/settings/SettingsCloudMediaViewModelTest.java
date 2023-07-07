/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media.photopicker.ui.settings;

import static android.provider.CloudMediaProviderContract.MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION;
import static android.provider.MediaStore.GET_CLOUD_PROVIDER_CALL;
import static android.provider.MediaStore.GET_CLOUD_PROVIDER_RESULT;
import static android.provider.MediaStore.SET_CLOUD_PROVIDER_CALL;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.photopicker.data.model.UserId;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class SettingsCloudMediaViewModelTest {
    private static final List<String> sProviderAuthorities =
            List.of("cloud_provider_1", "cloud_provider_2");
    private static final List<ResolveInfo> sAvailableProviders = getAvailableProviders();

    @Mock
    private ConfigStore mConfigStore;
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ContentProviderClient mContentProviderClient;
    @NonNull
    private SettingsCloudMediaViewModel mCloudMediaViewModel;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mCloudMediaViewModel =
                Mockito.spy(new SettingsCloudMediaViewModel(mContext, UserId.CURRENT_USER));

        doReturn(mPackageManager).when(mContext).getPackageManager();
        doReturn(mResources).when(mContext).getResources();
        doReturn(mContentProviderClient).when(mCloudMediaViewModel).getContentProviderClient();
        doAnswer(i -> createProviderInfo(i.getArgument(/* index */ 0)))
                .when(mPackageManager).resolveContentProvider(any(), anyInt());

        doReturn(true).when(mConfigStore).isCloudMediaInPhotoPickerEnabled();
    }

    @Test
    public void testLoadDataWithMultipleProviders() throws RemoteException {
        final String expectedCloudProvider = sProviderAuthorities.get(0);
        setUpCurrentCloudProvider(expectedCloudProvider);
        setUpAvailableCloudProviders(sAvailableProviders);

        mCloudMediaViewModel.loadData(mConfigStore);

        // Verify cloud provider options
        final List<CloudMediaProviderOption> providerOptions =
                mCloudMediaViewModel.getProviderOptions();
        assertThat(providerOptions.size()).isEqualTo(sProviderAuthorities.size() + 1);
        for (int i = 0; i < sProviderAuthorities.size(); i++) {
            assertThat(providerOptions.get(i).getKey()).isEqualTo(sProviderAuthorities.get(i));
        }
        assertThat(providerOptions.get(providerOptions.size() - 1).getKey())
                .isEqualTo(SettingsCloudMediaViewModel.NONE_PREF_KEY);

        // Verify selected cloud provider
        final String resultCloudProvider =
                mCloudMediaViewModel.getSelectedProviderAuthority();
        assertThat(resultCloudProvider).isEqualTo(expectedCloudProvider);
    }

    @Test
    public void testLoadDataWithNoProvider() throws RemoteException {
        final String expectedCloudProvider = SettingsCloudMediaViewModel.NONE_PREF_KEY;
        setUpCurrentCloudProvider(expectedCloudProvider);
        setUpAvailableCloudProviders(new ArrayList<>());
        mCloudMediaViewModel.loadData(mConfigStore);

        // Verify cloud provider options
        final List<CloudMediaProviderOption> providerOptions =
                mCloudMediaViewModel.getProviderOptions();
        assertThat(providerOptions.size()).isEqualTo(1);
        assertThat(providerOptions.get(0).getKey())
                .isEqualTo(SettingsCloudMediaViewModel.NONE_PREF_KEY);

        // Verify selected cloud provider
        final String resultCloudProvider =
                mCloudMediaViewModel.getSelectedProviderAuthority();
        assertThat(resultCloudProvider).isEqualTo(expectedCloudProvider);
    }

    @Test
    public void testUpdateProvider() throws RemoteException {
        final String expectedCloudProvider = sProviderAuthorities.get(0);
        setUpCurrentCloudProvider(expectedCloudProvider);
        setUpAvailableCloudProviders(sAvailableProviders);

        mCloudMediaViewModel.loadData(mConfigStore);

        // Verify selected cloud provider
        final String resultCloudProvider =
                mCloudMediaViewModel.getSelectedProviderAuthority();
        assertThat(resultCloudProvider).isEqualTo(expectedCloudProvider);

        // Update cloud provider
        final String newCloudProvider = sProviderAuthorities.get(1);
        final boolean success = mCloudMediaViewModel.updateSelectedProvider(newCloudProvider);

        // Verify selected cloud provider
        assertThat(success).isTrue();
        final String resultNewCloudProvider =
                mCloudMediaViewModel.getSelectedProviderAuthority();
        assertThat(resultNewCloudProvider).isEqualTo(newCloudProvider);
        verify(mContentProviderClient, times(1))
                .call(eq(SET_CLOUD_PROVIDER_CALL), any(), any());
    }

    private void setUpAvailableCloudProviders(@NonNull List<ResolveInfo> availableProviders) {
        doReturn(availableProviders).when(mPackageManager)
                .queryIntentContentProvidersAsUser(any(), eq(0), any());
    }

    private void setUpCurrentCloudProvider(@Nullable String providerAuthority)
            throws RemoteException {
        final Bundle result = new Bundle();
        result.putString(GET_CLOUD_PROVIDER_RESULT, providerAuthority);
        doReturn(result).when(mContentProviderClient)
                .call(eq(GET_CLOUD_PROVIDER_CALL), any(), any());
    }

    @NonNull
    private static List<ResolveInfo> getAvailableProviders() {
        final List<ResolveInfo> availableProviders = new ArrayList<>();
        for (String authority : sProviderAuthorities) {
            availableProviders.add(createResolveInfo(authority));
        }
        return availableProviders;
    }

    @NonNull
    private static ResolveInfo createResolveInfo(@NonNull String authority) {
        final ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.providerInfo = createProviderInfo(authority);
        return resolveInfo;
    }

    @NonNull
    private static ProviderInfo createProviderInfo(@NonNull String authority) {
        final ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = authority;
        providerInfo.readPermission = MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION;
        providerInfo.applicationInfo = createApplicationInfo(authority);
        return providerInfo;
    }

    @NonNull
    private static ApplicationInfo createApplicationInfo(@NonNull String authority) {
        final ApplicationInfo applicationInfo =  new ApplicationInfo();
        applicationInfo.packageName = authority;
        applicationInfo.uid = 0;
        return applicationInfo;
    }
}
