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

import android.app.Application;
import android.content.Context;

import com.android.providers.media.util.Logging;

import java.io.File;

public class MediaApplication extends Application {
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
        sIsUiProcess = getProcessName().endsWith(":PhotoPicker");

        // Only need to load fuse lib in the primary process.
        if (!sIsUiProcess) {
            System.loadLibrary("fuse_jni");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        final File persistentDir = this.getDir("logs", Context.MODE_PRIVATE);
        Logging.initPersistent(persistentDir);
    }

    /** Check if this process is the primary MediaProvider's process. */
    public static boolean isPrimaryProcess() {
        return !sIsUiProcess;
    }

    /** Check if this process is the MediaProvider's UI (PhotoPicker) process. */
    public static boolean isUiProcess() {
        return sIsUiProcess;
    }
}
