/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.photopicker.PhotoPickerSettingsActivity;
import com.android.providers.media.util.Logging;

import java.io.File;

public class MediaApplication extends Application {
    private static final String TAG = "MediaApplication";

    @SuppressLint("StaticFieldLeak")
    @GuardedBy("MediaApplication.class")
    @Nullable
    private static volatile MediaApplication sInstance;

    @GuardedBy("MediaApplication.class")
    @Nullable
    private static volatile ConfigStore sConfigStore;

    /**
     * MediaProvider's code runs in two processes: primary and UI (PhotoPicker).
     *
     * The <b>primary</b> process hosts the {@link MediaProvider} itself, along with
     * the {@link MediaService} and
     * the {@link com.android.providers.media.fuse.ExternalStorageServiceImpl "Fuse" Service}.
     * The name of the process matches the package name of the MediaProvider module.
     *
     * The <b>UI</b> (PhotoPicker) process hosts MediaProvider's UI components, namely
     * the {@link com.android.providers.media.photopicker.PhotoPickerActivity} and
     * the {@link com.android.providers.media.photopicker.PhotoPickerSettingsActivity}.
     * The name of the process is the package name of the MediaProvider module suffixed with
     * ":PhotoPicker".
     */
    private static final boolean sIsUiProcess;

    static {
        final String processName = getProcessName();
        sIsUiProcess = processName.endsWith(":PhotoPicker");

        // We package some of our "production" source code in some of our case (for more details
        // see MediaProviderTests build rule in packages/providers/MediaProvider/tests/Android.bp),
        // and occasionally may need to know if we are running as a "real" MediaProvider or "in a
        // test".
        // For this - we may check the process. Since process names on Android usually match the
        // package name of the corresponding package, and the package names of our test end with
        // ".test" (e.g. "com.android.providers.media.tests") - that's what we are checking for.
        final boolean isTestProcess = processName.endsWith(".tests");

        // Only need to load fuse lib in the primary process.
        if (!sIsUiProcess) {
            try {
                System.loadLibrary("fuse_jni");
            } catch (UnsatisfiedLinkError e) {

                if (isTestProcess) {
                    // We are "in a test", which does not ship out native lib - log a warning and
                    // carry on.
                    Log.w(TAG, "Could not load fuse_jni.so in a test (" + processName + ")", e);
                } else {
                    // We are not "in a test" - rethrow.
                    throw e;
                }
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final ConfigStore configStore;
        synchronized (MediaApplication.class) {
            sInstance = this;
            if (sConfigStore == null) {
                sConfigStore = new ConfigStore.ConfigStoreImpl(getResources());
            }
            configStore = sConfigStore;
        }

        final File persistentDir = this.getDir("logs", Context.MODE_PRIVATE);
        Logging.initPersistent(persistentDir);

        if (isPrimaryProcess()) {
            maybeEnablePhotoPickerSettingsActivity();
            configStore.addOnChangeListener(
                    BackgroundThread.getExecutor(), this::maybeEnablePhotoPickerSettingsActivity);
        }
    }

    /** Provides access to the Application Context. */
    public static synchronized Context getAppContext() {
        // ContentProviders instances may get created before the Application instance
        // (see javadoc to Application#onCreate())
        if (sInstance != null) {
            return sInstance.getApplicationContext();
        }
        final MediaProvider mediaProviderInstance = MediaProvider.getInstance();
        if (mediaProviderInstance != null) {
            return mediaProviderInstance.getContext().getApplicationContext();
        }
        throw new IllegalStateException("Neither a MediaApplication instance nor a MediaProvider "
                + "instance has been created yet.");
    }

    /** Check if this process is the primary MediaProvider's process. */
    public static boolean isPrimaryProcess() {
        return !sIsUiProcess;
    }

    /** Check if this process is the MediaProvider's UI (PhotoPicker) process. */
    public static boolean isUiProcess() {
        return sIsUiProcess;
    }

    /** Retrieve {@link ConfigStore} instance. */
    @NonNull
    public static synchronized ConfigStore getConfigStore() {
        if (sConfigStore == null) {
            // Normally ConfigStore would be created in onCreate() above, but in some cases the
            // framework may create ContentProvider-s *before* the Application#onCreate() is called.
            // In this case we use the MediaProvider instance to create the ConfigStore.
            sConfigStore = new ConfigStore.ConfigStoreImpl(getAppContext().getResources());
        }
        return sConfigStore;
    }

    /**
     * Enable or disable {@link PhotoPickerSettingsActivity} depending on whether
     * Cloud-Media-in-Photo-Picker feature is enabled or not.
     */
    private void maybeEnablePhotoPickerSettingsActivity() {
        final boolean isCloudMediaEnabled = getConfigStore().isCloudMediaInPhotoPickerEnabled();

        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, PhotoPickerSettingsActivity.class),
                isCloudMediaEnabled
                        ? COMPONENT_ENABLED_STATE_ENABLED : COMPONENT_ENABLED_STATE_DISABLED,
                /* flags */ PackageManager.DONT_KILL_APP);

        Log.i(TAG, "PhotoPickerSettingsActivity is now "
                + (isCloudMediaEnabled ? "enabled" : "disabled" + "."));
    }
}
