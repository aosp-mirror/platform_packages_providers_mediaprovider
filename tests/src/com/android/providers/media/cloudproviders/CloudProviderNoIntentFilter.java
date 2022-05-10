/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.cloudproviders;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.CloudMediaProvider;

import java.io.FileNotFoundException;

/**
 * Implements a placeholder {@link CloudMediaProvider}.
 */
public class CloudProviderNoIntentFilter extends CloudMediaProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor onQueryMedia(Bundle extras) {
        throw new UnsupportedOperationException("onQueryMedia not supported");
    }

    @Override
    public Cursor onQueryDeletedMedia(Bundle extras) {
        throw new UnsupportedOperationException("onQueryDeletedMedia not supported");
    }

    @Override
    public AssetFileDescriptor onOpenPreview(String mediaId, Point size, Bundle extras,
            CancellationSignal signal) throws FileNotFoundException {
        throw new UnsupportedOperationException("onOpenPreview not supported");
    }

    @Override
    public ParcelFileDescriptor onOpenMedia(String mediaId, Bundle extras,
            CancellationSignal signal) throws FileNotFoundException {
        throw new UnsupportedOperationException("onOpenMedia not supported");
    }

    @Override
    public Bundle onGetMediaCollectionInfo(Bundle extras) {
        throw new UnsupportedOperationException("onGetMediaCollectionInfo not supported");
    }
}
