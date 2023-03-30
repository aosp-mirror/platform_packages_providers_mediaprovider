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

import static android.provider.DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT;

import android.os.Binder;
import android.os.SystemProperties;
import android.provider.DeviceConfig;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Supplier;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Interface for retrieving MediaProvider configs.
 * The configs are usually stored to and read from {@link android.provider.DeviceConfig} provider,
 * however having this interface provides an easy way to mock out configs in tests (which don't
 * always have permissions for accessing the {@link android.provider.DeviceConfig}).
 */
public interface ConfigStore {
    boolean DEFAULT_TAKE_OVER_GET_CONTENT = false;
    boolean DEFAULT_USER_SELECT_FOR_APP = true;
    boolean DEFAULT_STABILISE_VOLUME_INTERNAL = false;
    boolean DEFAULT_STABILIZE_VOLUME_EXTERNAL = false;

    boolean DEFAULT_TRANSCODE_ENABLED = true;
    boolean DEFAULT_TRANSCODE_OPT_OUT_STRATEGY_ENABLED = false;
    int DEFAULT_TRANSCODE_MAX_DURATION = 60 * 1000; // 1 minute

    int DEFAULT_PICKER_SYNC_DELAY = 5000; // 5 seconds

    boolean DEFAULT_PICKER_GET_CONTENT_PRELOAD = true;
    boolean DEFAULT_PICKER_PICK_IMAGES_PRELOAD = true;
    boolean DEFAULT_PICKER_PICK_IMAGES_RESPECT_PRELOAD_ARG = false;

    /**
     * @return non-null list of {@link android.provider.CloudMediaProvider}
     * {@link android.content.pm.ProviderInfo#authority authorities}.
     */
    @NonNull
    List<String> getAllowlistedCloudProviders();

    /**
     * @return a delay (in milliseconds) before executing PhotoPicker media sync on media events
     * like inserts/updates/deletes to artificially throttle the burst notifications.
     */
    default int getPickerSyncDelayMs() {
        return DEFAULT_PICKER_SYNC_DELAY;
    }

    /**
     * @return if {@link com.android.providers.media.photopicker.PhotoPickerActivity} should preload
     *         selected media items before "returning"
     *         ({@link com.android.providers.media.photopicker.PhotoPickerActivity#setResultAndFinishSelf()})
     *         back to the calling application, in the case when the PhotoPicker was launched via
     *         {@link android.content.Intent#ACTION_GET_CONTENT ACTION_GET_CONTENT}.
     * @see com.android.providers.media.photopicker.PhotoPickerActivity#shouldPreloadSelectedItems()
     * @see com.android.providers.media.photopicker.SelectedMediaPreloader
     */
    default boolean shouldPickerPreloadForGetContent() {
        return DEFAULT_PICKER_GET_CONTENT_PRELOAD;
    }

    /**
     * @return if {@link com.android.providers.media.photopicker.PhotoPickerActivity} should preload
     *         selected media items before "returning"
     *         ({@link com.android.providers.media.photopicker.PhotoPickerActivity#setResultAndFinishSelf()})
     *         back to the calling application, in the case when the PhotoPicker was launched via
     *         {@link android.provider.MediaStore#ACTION_PICK_IMAGES ACTION_PICK_IMAGES}.
     * @see com.android.providers.media.photopicker.PhotoPickerActivity#shouldPreloadSelectedItems()
     * @see com.android.providers.media.photopicker.SelectedMediaPreloader
     */
    default boolean shouldPickerPreloadForPickImages() {
        return DEFAULT_PICKER_PICK_IMAGES_PRELOAD;
    }

    /**
     * @return if {@link com.android.providers.media.photopicker.PhotoPickerActivity} should respect
     *         {@code EXTRA_PRELOAD_SELECTED} {@code Intent} "argument" when making a
     *         decision whether to preload selected media items before "returning"
     *         ({@link com.android.providers.media.photopicker.PhotoPickerActivity#setResultAndFinishSelf()})
     *         back to the calling application, in the case when the PhotoPicker was launched via
     *         {@link android.provider.MediaStore#ACTION_PICK_IMAGES ACTION_PICK_IMAGES}.
     * @see com.android.providers.media.photopicker.PhotoPickerActivity#shouldPreloadSelectedItems()
     * @see com.android.providers.media.photopicker.SelectedMediaPreloader
     */
    default boolean shouldPickerRespectPreloadArgumentForPickImages() {
        return DEFAULT_PICKER_PICK_IMAGES_RESPECT_PRELOAD_ARG;
    }

    /**
     * @return if PhotoPicker should handle {@link android.content.Intent#ACTION_GET_CONTENT}.
     */
    default boolean isGetContentTakeOverEnabled() {
        return DEFAULT_TAKE_OVER_GET_CONTENT;
    }

    /**
     * @return if PhotoPickerUserSelectActivity should be enabled
     */
    default boolean isUserSelectForAppEnabled() {
        return DEFAULT_USER_SELECT_FOR_APP;
    }

    /**
     * @return if stable URI are enabled for the internal volume.
     */
    default boolean isStableUrisForInternalVolumeEnabled() {
        return DEFAULT_STABILISE_VOLUME_INTERNAL;
    }

    /**
     * @return if stable URI are enabled for the external volume.
     */
    default boolean isStableUrisForExternalVolumeEnabled() {
        return DEFAULT_STABILIZE_VOLUME_EXTERNAL;
    }

    /**
     * @return if transcoding is enabled.
     */
    default boolean isTranscodeEnabled() {
        return DEFAULT_TRANSCODE_ENABLED;
    }

    /**
     * @return if transcoding is the default option.
     */
    default boolean shouldTranscodeDefault() {
        return DEFAULT_TRANSCODE_OPT_OUT_STRATEGY_ENABLED;
    }

    /**
     * @return max transcode duration (in milliseconds).
     */
    default int getTranscodeMaxDurationMs() {
        return DEFAULT_TRANSCODE_MAX_DURATION;
    }

    @NonNull
    List<String> getTranscodeCompatManifest();

    @NonNull
    List<String> getTranscodeCompatStale();

    /**
     * Add a listener for changes.
     */
    void addOnChangeListener(@NonNull Executor executor, @NonNull Runnable listener);

    /**
     * Implementation of the {@link ConfigStore} that reads "real" configs from
     * {@link android.provider.DeviceConfig}. Meant to be used by the "production" code.
     */
    class ConfigStoreImpl implements ConfigStore {
        private static final String KEY_TAKE_OVER_GET_CONTENT = "take_over_get_content";
        private static final String KEY_USER_SELECT_FOR_APP = "user_select_for_app";

        @VisibleForTesting
        public static final String KEY_STABILISE_VOLUME_INTERNAL = "stablise_volume_internal";
        private static final String KEY_STABILIZE_VOLUME_EXTERNAL = "stabilize_volume_external";

        private static final String KEY_TRANSCODE_ENABLED = "transcode_enabled";
        private static final String KEY_TRANSCODE_OPT_OUT_STRATEGY_ENABLED = "transcode_default";
        private static final String KEY_TRANSCODE_MAX_DURATION = "transcode_max_duration_ms";
        private static final String KEY_TRANSCODE_COMPAT_MANIFEST = "transcode_compat_manifest";
        private static final String KEY_TRANSCODE_COMPAT_STALE = "transcode_compat_stale";

        private static final String SYSPROP_TRANSCODE_MAX_DURATION =
            "persist.sys.fuse.transcode_max_file_duration_ms";
        private static final int TRANSCODE_MAX_DURATION_INVALID = 0;

        private static final String KEY_PICKER_ALLOWED_CLOUD_PROVIDERS = "allowed_cloud_providers";
        private static final String KEY_PICKER_SYNC_DELAY = "default_sync_delay_ms";
        private static final String KEY_PICKER_GET_CONTENT_PRELOAD =
                "picker_get_content_preload_selected";
        private static final String KEY_PICKER_PICK_IMAGES_PRELOAD =
                "picker_pick_images_preload_selected";
        private static final String KEY_PICKER_PICK_IMAGES_RESPECT_PRELOAD_ARG =
                "picker_pick_images_respect_preload_selected_arg";

        private static final boolean sCanReadDeviceConfig = SdkLevel.isAtLeastS();

        @Override
        @NonNull
        public List<String> getAllowlistedCloudProviders() {
            return getStringArrayDeviceConfig(KEY_PICKER_ALLOWED_CLOUD_PROVIDERS);
        }

        @Override
        public int getPickerSyncDelayMs() {
            return getIntDeviceConfig(KEY_PICKER_SYNC_DELAY, DEFAULT_PICKER_SYNC_DELAY);
        }

        @Override
        public boolean shouldPickerPreloadForGetContent() {
            return getBooleanDeviceConfig(KEY_PICKER_GET_CONTENT_PRELOAD,
                    DEFAULT_PICKER_GET_CONTENT_PRELOAD);
        }

        @Override
        public boolean shouldPickerPreloadForPickImages() {
            return getBooleanDeviceConfig(KEY_PICKER_PICK_IMAGES_PRELOAD,
                    DEFAULT_PICKER_PICK_IMAGES_PRELOAD);
        }

        @Override
        public boolean shouldPickerRespectPreloadArgumentForPickImages() {
            return getBooleanDeviceConfig(KEY_PICKER_PICK_IMAGES_RESPECT_PRELOAD_ARG,
                    DEFAULT_PICKER_PICK_IMAGES_RESPECT_PRELOAD_ARG);
        }

        @Override
        public boolean isGetContentTakeOverEnabled() {
            return getBooleanDeviceConfig(KEY_TAKE_OVER_GET_CONTENT, DEFAULT_TAKE_OVER_GET_CONTENT);
        }

        @Override
        public boolean isUserSelectForAppEnabled() {
            return getBooleanDeviceConfig(KEY_USER_SELECT_FOR_APP, DEFAULT_USER_SELECT_FOR_APP);
        }

        @Override
        public boolean isStableUrisForInternalVolumeEnabled() {
            return getBooleanDeviceConfig(
                    KEY_STABILISE_VOLUME_INTERNAL, DEFAULT_STABILISE_VOLUME_INTERNAL);
        }

        @Override
        public boolean isStableUrisForExternalVolumeEnabled() {
            return getBooleanDeviceConfig(
                    KEY_STABILIZE_VOLUME_EXTERNAL, DEFAULT_STABILIZE_VOLUME_EXTERNAL);
        }

        @Override
        public boolean isTranscodeEnabled() {
            return getBooleanDeviceConfig(
                    KEY_TRANSCODE_ENABLED, DEFAULT_TRANSCODE_ENABLED);
        }

        @Override
        public boolean shouldTranscodeDefault() {
            return getBooleanDeviceConfig(KEY_TRANSCODE_OPT_OUT_STRATEGY_ENABLED,
                    DEFAULT_TRANSCODE_OPT_OUT_STRATEGY_ENABLED);
        }

        @Override
        public int getTranscodeMaxDurationMs() {
            // First check if OEMs overwrite default duration via system property.
            int maxDurationMs = SystemProperties.getInt(
                SYSPROP_TRANSCODE_MAX_DURATION, TRANSCODE_MAX_DURATION_INVALID);

            // Give priority to OEM value if set. Only accept larger values, which can be desired
            // for more performant devices. Lower values may result in unexpected behaviour
            // (a value of 0 would mean transcoding is actually disabled) or break CTS tests (a
            // value small enough to prevent transcoding the videos under test).
            // Otherwise, fallback to device config / default values.
            if (maxDurationMs != TRANSCODE_MAX_DURATION_INVALID
                    && maxDurationMs > DEFAULT_TRANSCODE_MAX_DURATION) {
                return maxDurationMs;
            }
            return getIntDeviceConfig(KEY_TRANSCODE_MAX_DURATION, DEFAULT_TRANSCODE_MAX_DURATION);
        }

        @Override
        @NonNull
        public List<String> getTranscodeCompatManifest() {
            return getStringArrayDeviceConfig(KEY_TRANSCODE_COMPAT_MANIFEST);
        }

        @Override
        @NonNull
        public List<String> getTranscodeCompatStale() {
            return getStringArrayDeviceConfig(KEY_TRANSCODE_COMPAT_STALE);
        }

        @Override
        public void addOnChangeListener(@NonNull Executor executor, @NonNull Runnable listener) {
            if (!sCanReadDeviceConfig) {
                return;
            }

            // TODO(b/246590468): Follow best naming practices for namespaces of device config flags
            // that make changes to this package independent of reboot
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_STORAGE_NATIVE_BOOT, executor, unused -> listener.run());
        }

        private static boolean getBooleanDeviceConfig(@NonNull String key, boolean defaultValue) {
            if (!sCanReadDeviceConfig) {
                return defaultValue;
            }
            return withCleanCallingIdentity(() ->
                    DeviceConfig.getBoolean(NAMESPACE_STORAGE_NATIVE_BOOT, key, defaultValue));
        }

        private static int getIntDeviceConfig(@NonNull String key, int defaultValue) {
            if (!sCanReadDeviceConfig) {
                return defaultValue;
            }
            return withCleanCallingIdentity(() ->
                    DeviceConfig.getInt(NAMESPACE_STORAGE_NATIVE_BOOT, key, defaultValue));
        }

        private static String getStringDeviceConfig(@NonNull String key) {
            if (!sCanReadDeviceConfig) {
                return null;
            }
            return withCleanCallingIdentity(() ->
                    DeviceConfig.getString(NAMESPACE_STORAGE_NATIVE_BOOT, key, null));
        }

        private static List<String> getStringArrayDeviceConfig(@NonNull String key) {
            final String items = getStringDeviceConfig(key);
            if (StringUtils.isNullOrEmpty(items)) {
                return Collections.emptyList();
            }
            return Arrays.asList(items.split(","));
        }

        private static <T> T withCleanCallingIdentity(@NonNull Supplier<T> action) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                return action.get();
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }
    }
}
