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

package com.android.providers.media;

import static java.util.Objects.requireNonNull;

import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * {@link ConfigStore} implementation that does not attempt to read the "real" configs (usually
 * stored to {@link android.provider.DeviceConfig}) and instead simply returns default values.
 */
public class TestConfigStore implements ConfigStore {
    private boolean mCloudMediaInPhotoPickerEnabled = false;

    private boolean mPickerChoiceManagedSelectionEnabled = false;
    private List<String> mAllowedCloudProviderPackages = Collections.emptyList();
    private @Nullable String mDefaultCloudProviderPackage = null;
    private List<Pair<Executor, Runnable>> mObservers = new ArrayList<>();

    public void enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(String... providers) {
        mAllowedCloudProviderPackages = Arrays.asList(providers);
        mCloudMediaInPhotoPickerEnabled = true;
        notifyObservers();
    }

    public void enableCloudMediaFeature() {
        mCloudMediaInPhotoPickerEnabled = true;
        notifyObservers();
    }

    public void clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature() {
        mAllowedCloudProviderPackages = Collections.emptyList();
        disableCloudMediaFeature();
        notifyObservers();
    }

    public void disableCloudMediaFeature() {
        mCloudMediaInPhotoPickerEnabled = false;
        notifyObservers();
    }

    /**
     * Enables pickerChoiceManagedSelection flag in the test config.
     */
    public void enablePickerChoiceManagedSelectionEnabled() {
        mPickerChoiceManagedSelectionEnabled = true;
    }

    @Override
    public @NonNull List<String> getAllowedCloudProviderPackages() {
        return mAllowedCloudProviderPackages;
    }

    public void setAllowedCloudProviderPackages(String... providers) {
        if (providers.length == 0) {
            mAllowedCloudProviderPackages = Collections.emptyList();
        } else {
            mAllowedCloudProviderPackages = Arrays.asList(providers);
        }
        notifyObservers();
    }

    @Override
    public boolean isCloudMediaInPhotoPickerEnabled() {
        return mCloudMediaInPhotoPickerEnabled && !mAllowedCloudProviderPackages.isEmpty();
    }

    public void setDefaultCloudProviderPackage(@NonNull String packageName) {
        requireNonNull(packageName, "null packageName is not allowed. "
                + "Consider clearDefaultCloudProviderPackage()");
        mDefaultCloudProviderPackage = packageName;
    }

    public void clearDefaultCloudProviderPackage() {
        mDefaultCloudProviderPackage = null;
    }

    @Nullable
    @Override
    public String getDefaultCloudProviderPackage() {
        return mDefaultCloudProviderPackage;
    }

    @NonNull
    @Override
    public List<String> getTranscodeCompatManifest() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public List<String> getTranscodeCompatStale() {
        return Collections.emptyList();
    }

    @Override
    public boolean isPickerChoiceManagedSelectionEnabled() {
        return mPickerChoiceManagedSelectionEnabled;
    }

    @Override
    public void addOnChangeListener(@NonNull Executor executor, @NonNull Runnable listener) {
        Pair p = Pair.create(executor, listener);
        mObservers.add(p);
    }


    /**
     * Runs all subscribers to the TestConfigStore.
     */
    private void notifyObservers() {
        for (Pair<Executor, Runnable> observer: mObservers) {
            // Run tasks in a synchronous manner to avoid test flakes.
            observer.second.run();
        }
    }
}
