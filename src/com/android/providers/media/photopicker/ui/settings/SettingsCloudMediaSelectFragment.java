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

import static com.android.providers.media.MediaApplication.getConfigStore;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.settingslib.widget.SelectorWithWidgetPreference;

/**
 * This fragment will display a list of available cloud providers for the profile selected.
 * Users can view or change the preferred cloud provider media app.
 */
public class SettingsCloudMediaSelectFragment extends PreferenceFragmentCompat
        implements SelectorWithWidgetPreference.OnClickListener {
    public static final String EXTRA_TAB_USER_ID = "user_id";
    private static final String TAG = "SettingsCMSelectFgmt";

    @NonNull
    private UserId mUserId;
    @NonNull
    private Context mContext;
    @NonNull
    private SettingsCloudMediaViewModel mSettingsCloudMediaViewModel;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        mContext = requireNonNull(context);
        mUserId = requireNonNull(getUserId());
        mSettingsCloudMediaViewModel = createViewModel();
    }

    @Override
    public void onResume() {
        super.onResume();

        mSettingsCloudMediaViewModel.loadAccountNameAsync();
    }

    @UiThread
    @Override
    public void onRadioButtonClicked(@NonNull SelectorWithWidgetPreference selectedPref) {
        final String selectedProviderKey = selectedPref.getKey();
        // Check if current provider is different from the selected provider.
        if (!TextUtils.equals(selectedProviderKey,
                mSettingsCloudMediaViewModel.getSelectedPreferenceKey())) {
            final boolean success =
                    mSettingsCloudMediaViewModel.updateSelectedProvider(selectedProviderKey);
            if (success) {
                updateSelectedRadioButton();
            } else {
                Toast.makeText(getContext(),
                        R.string.picker_settings_toast_error, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.addPreferencesFromResource(R.xml.pref_screen_picker_settings);

        mSettingsCloudMediaViewModel.loadData(getConfigStore());
        observeAccountNameChanges();
        refreshUI();
    }

    @UiThread
    private void refreshUI() {
        final PreferenceScreen screen = getPreferenceScreen();
        resetPreferenceScreen(screen);

        screen.addPreference(buildTitlePreference());
        for (CloudMediaProviderOption provider :
                mSettingsCloudMediaViewModel.getProviderOptions()) {
            screen.addPreference(buildProviderOptionPreference(provider));
        }

        updateSelectedRadioButton();
    }

    private void observeAccountNameChanges() {
        mSettingsCloudMediaViewModel.getCurrentProviderAccount()
                .observe(this, accountDetails -> {
                    // Only update current account name on the UI if cloud provider linked to the
                    // account name matches the current provider.
                    if (accountDetails != null
                            && accountDetails.getCloudProviderAuthority()
                            .equals(mSettingsCloudMediaViewModel.getSelectedProviderAuthority())) {
                        final Preference selectedPref = findPreference(
                                mSettingsCloudMediaViewModel.getSelectedPreferenceKey());
                        // TODO(b/262002538): {@code selectedPref} could be null if the selected
                        //  cloud provider is not in the allowed list. This is not something a
                        //  typical user will encounter.
                        if (selectedPref != null) {
                            selectedPref.setSummary(accountDetails.getCloudProviderAccountName());
                        }
                    }
                });
    }

    @UiThread
    private void updateSelectedRadioButton() {
        final String selectedPreferenceKey =
                mSettingsCloudMediaViewModel.getSelectedPreferenceKey();
        for (CloudMediaProviderOption providerOption
                : mSettingsCloudMediaViewModel.getProviderOptions()) {
            final Preference pref = findPreference(providerOption.getKey());
            if (pref instanceof SelectorWithWidgetPreference) {
                final SelectorWithWidgetPreference providerPref =
                        (SelectorWithWidgetPreference) pref;

                final boolean newSelectionState =
                        TextUtils.equals(providerPref.getKey(), selectedPreferenceKey);
                providerPref.setChecked(newSelectionState);

                providerPref.setSummary(null);
                if (newSelectionState) {
                    mSettingsCloudMediaViewModel.loadAccountNameAsync();
                }
            }
        }
    }

    @NonNull
    private Preference buildProviderOptionPreference(@NonNull CloudMediaProviderOption provider) {
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
        titlePref.setLayoutResource(R.layout.pref_settings_cloud_select_title);
        return titlePref;
    }

    private Context getPrefContext() {
        return getPreferenceManager().getContext();
    }

    private void resetPreferenceScreen(PreferenceScreen screen) {
        screen.removeAll();
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

    @NonNull
    private SettingsCloudMediaViewModel createViewModel() {
        final ViewModelProvider viewModelProvider =
                new ViewModelProvider(
                        requireActivity(),
                        new SettingsViewModelFactory(mContext, mUserId)
                );
        final String viewModelKey = Integer.toString(mUserId.getIdentifier());

        return viewModelProvider.get(viewModelKey, SettingsCloudMediaViewModel.class);
    }

    private static class SettingsViewModelFactory implements ViewModelProvider.Factory {
        @NonNull
        private final Context mContext;
        @NonNull
        private final UserId mUserId;

        SettingsViewModelFactory(@NonNull Context context, @NonNull UserId userId) {
            mContext = requireNonNull(context);
            mUserId = requireNonNull(userId);
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new SettingsCloudMediaViewModel(mContext, mUserId);
        }
    }
}