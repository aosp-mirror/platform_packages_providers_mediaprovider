/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.provider.MediaStore.EXTRA_CLOUD_PROVIDER;
import static android.provider.MediaStore.GET_CLOUD_PROVIDER_CALL;
import static android.provider.MediaStore.GET_CLOUD_PROVIDER_RESULT;
import static android.provider.MediaStore.SET_CLOUD_PROVIDER_CALL;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerSettingsActivity;
import com.android.providers.media.photopicker.data.CloudProviderInfo;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.util.CloudProviderUtils;
import com.android.settingslib.widget.SelectorWithWidgetPreference;

import java.util.ArrayList;
import java.util.List;

/**
 * This fragment will display a list of available cloud providers for the profile selected.
 * Users can view or change the preferred cloud provider media app.
 */
public class SettingsCloudMediaSelectFragment extends PreferenceFragmentCompat
        implements SelectorWithWidgetPreference.OnClickListener {
    public static final String EXTRA_TAB_USER_ID = "user_id";
    private static final String NONE_PREF_KEY = "none";
    private static final String TAG = "SettingsCMSelectFgmt";

    @NonNull private final List<CloudMediaProviderOption> mProviderOptions = new ArrayList<>();

    @NonNull private UserId mUserId;
    @NonNull private Context mContext;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mContext = context;
        mUserId = getUserId();
    }

    @Override
    public void onRadioButtonClicked(@NonNull SelectorWithWidgetPreference selectedPref) {
        final String selectedProviderKey = selectedPref.getKey();
        final boolean success =
                persistSelectedProvider(getProviderAuthority(selectedProviderKey));
        if (success) {
            updateSelectedRadioButton(selectedProviderKey);
        } else {
            Toast.makeText(getContext(), R.string.picker_settings_toast_error, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.addPreferencesFromResource(R.xml.pref_screen_picker_settings);
        updateProviderOptions();
    }

    private void updateProviderOptions() {
        refreshProviderOptions(getProviderOptions());
        updateScreenPreferences();
        updateSelectedRadioButton(getProviderAuthority());
    }

    @VisibleForTesting
    private void updateScreenPreferences() {
        final PreferenceScreen screen = getPreferenceScreen();
        resetPreferenceScreen(screen);

        screen.addPreference(buildTitlePreference());
        for (CloudMediaProviderOption provider : mProviderOptions) {
            screen.addPreference(buildOptionPreferenceForProvider(provider));
        }
    }

    @VisibleForTesting
    private void updateSelectedRadioButton(@Nullable String selectedKey) {
        for (CloudMediaProviderOption providerOption : mProviderOptions) {
            final Preference pref = findPreference(providerOption.getKey());
            if (pref instanceof SelectorWithWidgetPreference) {
                final SelectorWithWidgetPreference providerPref =
                        (SelectorWithWidgetPreference) pref;
                final boolean newSelectionState =
                        TextUtils.equals(providerPref.getKey(), selectedKey);
                providerPref.setChecked(newSelectionState);
            }
        }
    }

    @NonNull
    private Preference buildOptionPreferenceForProvider(
            @NonNull CloudMediaProviderOption provider) {
        final SelectorWithWidgetPreference pref =
                new SelectorWithWidgetPreference(getPrefContext());

        // Preferences are stored in SharedPreferences by default. This feature is disabled
        // as Cloud picker preferences are stored in SharedPreferences separately.
        pref.setPersistent(false);
        pref.setTitle(provider.getLabel());
        pref.setIcon(provider.getIcon());
        pref.setKey(provider.getKey());
        pref.setOnClickListener(this);
        return pref;
    }

    @NonNull
    private Preference buildTitlePreference() {
        final Preference titlePref = new Preference(getPrefContext());
        titlePref.setTitle(R.string.picker_settings_selection_message);
        titlePref.setSelectable(false);
        titlePref.setPersistent(false);
        titlePref.setLayoutResource(R.layout.pref_settings_title);
        return titlePref;
    }

    @NonNull
    private CloudMediaProviderOption getNoneProviderOption() {
        final Drawable nonePrefIcon = AppCompatResources
                .getDrawable(this.mContext, R.drawable.ic_cloud_picker_off);
        final String nonePrefLabel = this.mContext.getString(R.string.picker_settings_no_provider);
        return new CloudMediaProviderOption(NONE_PREF_KEY, nonePrefLabel, nonePrefIcon);
    }

    @Nullable
    private String getProviderAuthority(@NonNull String selectedKey) {
        // For None option, the provider auth should be null to disable cloud media provider.
        return selectedKey.equals(NONE_PREF_KEY) ? null : selectedKey;
    }

    private Context getPrefContext() {
        return getPreferenceManager().getContext();
    }

    private void resetPreferenceScreen(PreferenceScreen screen) {
        screen.removeAll();
    }

    @VisibleForTesting
    void refreshProviderOptions(List<CloudMediaProviderOption> providerOptions) {
        mProviderOptions.clear();
        mProviderOptions.addAll(providerOptions);
        mProviderOptions.add(getNoneProviderOption());
    }

    @NonNull
    private List<CloudMediaProviderOption> getProviderOptions() {
        // Get info of available cloud providers.
        List<CloudProviderInfo> cloudProviders =
                CloudProviderUtils.getAllAvailableCloudProviders(
                        mContext,
                        ((PhotoPickerSettingsActivity) getActivity()).getConfigStore(),
                        UserHandle.of(mUserId.getIdentifier()));

        return getProviderOptionsFromCloudProviderInfos(cloudProviders);
    }

    @Nullable
    private String getProviderAuthority() {
        try (ContentProviderClient client = getContentProviderClient()) {
            final Bundle out = client.call(GET_CLOUD_PROVIDER_CALL,
                    /* arg */ null, /* extras */ null);
            return out.getString(GET_CLOUD_PROVIDER_RESULT, NONE_PREF_KEY);
        } catch (RemoteException | PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Could not get selected cloud provider", e);
        }
    }

    private boolean persistSelectedProvider(@Nullable String newCloudProvider) {
        try (ContentProviderClient client = getContentProviderClient()) {
            Bundle input = new Bundle();
            input.putString(EXTRA_CLOUD_PROVIDER, newCloudProvider);
            if (client == null) {
                return false;
            }
            client.call(SET_CLOUD_PROVIDER_CALL, /* arg */ null, /* extras */ input);
            return true;
        } catch (RemoteException | PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not persist selected cloud provider", e);
            return false;
        }
    }

    @Nullable
    private ContentProviderClient getContentProviderClient()
            throws PackageManager.NameNotFoundException {
        return mUserId.getContentResolver(mContext)
                .acquireUnstableContentProviderClient(MediaStore.AUTHORITY);
    }

    @NonNull
    private List<CloudMediaProviderOption> getProviderOptionsFromCloudProviderInfos(
            @NonNull List<CloudProviderInfo> cloudProviders) {
        // TODO(b/195009187): In case current cloud provider is not part of the allow list, it will
        //  not be listed on the Settings page. Handle this case so that it does show up.
        final List<CloudMediaProviderOption> providerOption = new ArrayList<>();
        for (CloudProviderInfo cloudProvider : cloudProviders) {
            providerOption.add(
                    CloudMediaProviderOption
                            .fromCloudProviderInfo(cloudProvider, mContext, mUserId));
        }
        return providerOption;
    }

    @NonNull
    private UserId getUserId() {
        if (getArguments() != null && getArguments().containsKey(EXTRA_TAB_USER_ID)) {
            final int currentUID = getArguments().getInt(EXTRA_TAB_USER_ID);
            final UserHandle userHandle = UserHandle.of(currentUID);
            return UserId.of(userHandle);
        } else {
            throw new IllegalArgumentException(
                    "User Id for a SettingsCloudMediaSelectFragment is not set.");
        }
    }
}