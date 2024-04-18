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
import static android.provider.MediaStore.SET_CLOUD_PROVIDER_RESULT;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
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
import com.android.providers.media.cloudproviders.CloudProviderNoIntentFilter;
import com.android.providers.media.cloudproviders.CloudProviderSecondary;
import com.android.providers.media.photopicker.DataLoaderThread;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.viewmodel.InstantTaskExecutorRule;
import com.android.providers.media.util.ForegroundThread;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class SettingsCloudMediaViewModelTest {
    private static final String PKG1 = "com.providers.test1";
    private static final String PKG2 = "com.providers.test2";
    private static final List<String> sProviderAuthorities =
            List.of(PKG1 + ".cloud_provider_1", PKG2 + ".cloud_provider_2");
    private static final List<ResolveInfo> sAvailableProviders = getAvailableProviders();
    private static final List<String> sAllowlistedPackages = List.of(PKG1);

    @Rule
    public final InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

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

        final ContentResolver resolver =
                getInstrumentation().getTargetContext().getContentResolver();
        doReturn(resolver).when(mContext).getContentResolver();

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

        // Verify selected option preference key
        final String resultSelectedOptionPreferenceKey =
                mCloudMediaViewModel.getSelectedPreferenceKey();
        assertThat(resultSelectedOptionPreferenceKey).isEqualTo(expectedCloudProvider);
    }

    @Test
    public void testLoadDataWithNoProvider() throws RemoteException {
        setUpCurrentCloudProvider(/* providerAuthority= */ null);
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
        assertThat(resultCloudProvider).isNull();

        // Verify selected option preference key
        final String resultSelectedOptionPreferenceKey =
                mCloudMediaViewModel.getSelectedPreferenceKey();
        assertThat(resultSelectedOptionPreferenceKey)
                .isEqualTo(SettingsCloudMediaViewModel.NONE_PREF_KEY);
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
        updateSelectedProvider(newCloudProvider);

        final String resultNewCloudProvider =
                mCloudMediaViewModel.getSelectedProviderAuthority();
        assertThat(resultNewCloudProvider).isEqualTo(newCloudProvider);
        verify(mContentProviderClient, times(1))
                .call(eq(SET_CLOUD_PROVIDER_CALL), any(), any());
    }

    @Test
    public void testLoadDataWithAllowListedProviders() throws RemoteException {
        final String expectedCloudProvider = sProviderAuthorities.get(0);
        setUpCurrentCloudProvider(expectedCloudProvider);
        setUpAvailableCloudProviders(sAvailableProviders);
        setUpAllowedCloudPackages(sAllowlistedPackages);

        mCloudMediaViewModel.loadData(mConfigStore);

        // Verify cloud provider options
        final List<CloudMediaProviderOption> providerOptions =
                mCloudMediaViewModel.getProviderOptions();
        assertThat(providerOptions.size()).isEqualTo(sAllowlistedPackages.size() + 1);
        for (int i = 0; i < sAllowlistedPackages.size(); i++) {
            final int lastDotIndex = providerOptions.get(i).getKey().lastIndexOf('.');
            final String providerOptionsPackage = providerOptions.get(i).getKey().substring(0,
                    lastDotIndex);
            assertThat(sAllowlistedPackages).contains(providerOptionsPackage);
        }
        assertThat(providerOptions.get(providerOptions.size() - 1).getKey())
                .isEqualTo(SettingsCloudMediaViewModel.NONE_PREF_KEY);

        // Verify selected cloud provider
        final String resultCloudProvider =
                mCloudMediaViewModel.getSelectedProviderAuthority();
        assertThat(resultCloudProvider).isEqualTo(expectedCloudProvider);
    }

    @Test
    public void testLoadMediaCollectionInfo_NullProvider_MainThread() throws RemoteException {
        clearSelectedProvider();
        assertThat(mCloudMediaViewModel.getSelectedProviderAuthority()).isNull();

        // Load media collection info in the main thread.
        // {@link Instrumentation#runOnMainSync(Runnable)} waits for the runnable to complete.
        getInstrumentation().runOnMainSync(mCloudMediaViewModel::loadMediaCollectionInfoAsync);

        // Wait for any Foreground thread executions to complete before assertions.
        ForegroundThread.waitForIdle();

        assertThat(mCloudMediaViewModel.getCurrentProviderMediaCollectionInfo().getValue())
                .isNull();
    }

    @Test
    public void testLoadMediaCollectionInfo_NonNullProvider_FailingGetCollectionInfo_MainThread()
            throws RemoteException {
        final String cloudProvider = CloudProviderNoIntentFilter.AUTHORITY;
        updateSelectedProvider(cloudProvider);
        assertThat(mCloudMediaViewModel.getSelectedProviderAuthority()).isEqualTo(cloudProvider);

        // Load media collection info in the main thread.
        // {@link Instrumentation#runOnMainSync(Runnable)} waits for the runnable to complete.
        getInstrumentation().runOnMainSync(mCloudMediaViewModel::loadMediaCollectionInfoAsync);

        // Wait for any Foreground thread executions to complete before assertions.
        ForegroundThread.waitForIdle();

        final CloudProviderMediaCollectionInfo currentProviderMediaCollectionInfo =
                mCloudMediaViewModel.getCurrentProviderMediaCollectionInfo().getValue();
        assertThat(currentProviderMediaCollectionInfo).isNotNull();
        assertThat(currentProviderMediaCollectionInfo.getAuthority()).isEqualTo(cloudProvider);
        assertThat(currentProviderMediaCollectionInfo.getAccountName()).isNull();
        assertThat(currentProviderMediaCollectionInfo.getAccountConfigurationIntent()).isNull();
    }

    @Test
    public void testLoadMediaCollectionInfo_NonNullProvider_NonNullCollectionInfo_MainThread()
            throws RemoteException {
        final String cloudProvider = CloudProviderSecondary.AUTHORITY;
        updateSelectedProvider(cloudProvider);
        assertThat(mCloudMediaViewModel.getSelectedProviderAuthority()).isEqualTo(cloudProvider);

        // Load media collection info in the main thread.
        // {@link Instrumentation#runOnMainSync(Runnable)} waits for the runnable to complete.
        getInstrumentation().runOnMainSync(mCloudMediaViewModel::loadMediaCollectionInfoAsync);

        // Wait for any Foreground thread executions to complete before assertions.
        ForegroundThread.waitForIdle();

        final CloudProviderMediaCollectionInfo currentProviderMediaCollectionInfo =
                mCloudMediaViewModel.getCurrentProviderMediaCollectionInfo().getValue();
        assertThat(currentProviderMediaCollectionInfo).isNotNull();
        assertThat(currentProviderMediaCollectionInfo.getAuthority()).isEqualTo(cloudProvider);
        assertThat(currentProviderMediaCollectionInfo.getAccountName())
                .isEqualTo(CloudProviderSecondary.ACCOUNT_NAME);
        assertThat(currentProviderMediaCollectionInfo.getAccountConfigurationIntent()).isNotNull();
    }

    @Test
    public void testLoadMediaCollectionInfo_NonNullProvider_NonNullCollectionInfo_NonMainThread()
            throws RemoteException {
        final String cloudProvider = CloudProviderSecondary.AUTHORITY;
        updateSelectedProvider(cloudProvider);
        assertThat(mCloudMediaViewModel.getSelectedProviderAuthority()).isEqualTo(cloudProvider);

        // Load media collection info in a non-main thread.
        DataLoaderThread.getExecutor().execute(mCloudMediaViewModel::loadMediaCollectionInfoAsync);
        DataLoaderThread.waitForIdle();

        // Wait for any Foreground thread executions to complete before assertions.
        ForegroundThread.waitForIdle();

        assertThat(mCloudMediaViewModel.getCurrentProviderMediaCollectionInfo().getValue())
                .isNull();
    }

    private void clearSelectedProvider() throws RemoteException {
        // Mock the 'set cloud provider' call result
        final Bundle result = new Bundle();
        result.putBoolean(SET_CLOUD_PROVIDER_RESULT, true);
        doReturn(result).when(mContentProviderClient)
                .call(eq(SET_CLOUD_PROVIDER_CALL), any(), any());

        // Update the selected provider option to None
        final boolean isClearSuccessful = mCloudMediaViewModel.updateSelectedProvider(
                /* newPreferenceKey= */ SettingsCloudMediaViewModel.NONE_PREF_KEY);
        assertThat(isClearSuccessful).isTrue();
    }

    private void updateSelectedProvider(@NonNull String providerAuthority) throws RemoteException {
        // Mock the 'set cloud provider' call result
        final Bundle result = new Bundle();
        result.putBoolean(SET_CLOUD_PROVIDER_RESULT, true);
        doReturn(result).when(mContentProviderClient)
                .call(eq(SET_CLOUD_PROVIDER_CALL), any(), any());

        // Update the selected provider option to the given provider
        final boolean isUpdateSuccessful = mCloudMediaViewModel.updateSelectedProvider(
                /* newPreferenceKey= */ providerAuthority);
        assertThat(isUpdateSuccessful).isTrue();
    }

    private void setUpAvailableCloudProviders(@NonNull List<ResolveInfo> availableProviders) {
        doReturn(availableProviders).when(mPackageManager)
                .queryIntentContentProvidersAsUser(any(), eq(0), any());
    }

    private void setUpAllowedCloudPackages(@NonNull List<String> allowlistedPackages) {
        doReturn(true).when(mConfigStore).shouldEnforceCloudProviderAllowlist();
        doReturn(allowlistedPackages).when(mConfigStore).getAllowedCloudProviderPackages();
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
        final int lastDotIndex = authority.lastIndexOf('.');
        providerInfo.authority = authority;
        providerInfo.readPermission = MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION;
        providerInfo.packageName = authority.substring(0, lastDotIndex);
        providerInfo.applicationInfo = createApplicationInfo(authority);
        return providerInfo;
    }

    @NonNull
    private static ApplicationInfo createApplicationInfo(@NonNull String authority) {
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = authority;
        applicationInfo.uid = 0;
        return applicationInfo;
    }
}
