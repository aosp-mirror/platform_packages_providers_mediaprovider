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

package com.android.providers.media.scan;

import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.providers.media.MediaVolume;

import java.io.File;

/**
 * Null scanner that ignores all scanning requests. Can be useful when running
 * as {@link MediaStore#AUTHORITY_LEGACY} or during unit tests.
 */
public class NullMediaScanner implements MediaScanner {
    private static final String TAG = "NullMediaScanner";

    private final Context mContext;

    public NullMediaScanner(Context context) {
        mContext = context;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public void scanDirectory(File file, int reason) {
        Log.w(TAG, "Ignoring scan request for " + file);
    }

    @Override
    public Uri scanFile(File file, int reason) {
        Log.w(TAG, "Ignoring scan request for " + file);
        return null;
    }

    @Override
    public void onDetachVolume(MediaVolume volume) {
        // Ignored
    }

    @Override
    public void onIdleScanStopped() {
        // Ignored
    }

    @Override
    public void onDirectoryDirty(File file) {
        // Ignored
    }
}
