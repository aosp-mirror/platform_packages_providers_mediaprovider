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

import androidx.annotation.Nullable;

import com.android.providers.media.MediaVolume;

import java.io.File;

@Deprecated
public class LegacyMediaScanner implements MediaScanner {
    private final Context mContext;

    public LegacyMediaScanner(Context context) {
        mContext = context;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public void scanDirectory(File file, int reason) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri scanFile(File file, int reason) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri scanFile(File file, int reason, @Nullable String ownerPackage) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onDetachVolume(MediaVolume volume) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onIdleScanStopped() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onDirectoryDirty(File file) {
        throw new UnsupportedOperationException();
    }
}
