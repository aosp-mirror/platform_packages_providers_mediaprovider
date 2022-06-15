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

package com.android.providers.media.photopicker.data.glide;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Custom {@link DataFetcher} to fetch a {@link ParcelFileDescriptor} for a thumbnail from a cloud
 * media provider.
 */
public class PickerThumbnailFetcher implements DataFetcher<ParcelFileDescriptor> {

    private final Context context;
    private final Uri model;
    private final int width;
    private final int height;

    PickerThumbnailFetcher(Context context, Uri model, int width, int height) {
        this.context = context;
        this.model = model;
        this.width = width;
        this.height = height;
    }

    @Override
    public void loadData(Priority priority, DataCallback<? super ParcelFileDescriptor> callback) {
        ContentResolver contentResolver = context.getContentResolver();
        final Bundle opts = new Bundle();
        opts.putParcelable(ContentResolver.EXTRA_SIZE, new Point(width, height));
        try (AssetFileDescriptor afd = contentResolver.openTypedAssetFileDescriptor(model,
                /* mimeType */ "image/*", opts, /* cancellationSignal */ null)) {
            if (afd == null) {
                final String err = "Failed to load data for " + model;
                callback.onLoadFailed(new FileNotFoundException(err));
                return;
            }
            callback.onDataReady(afd.getParcelFileDescriptor());
        } catch (IOException e) {
            callback.onLoadFailed(e);
        }
    }

    @Override
    public void cleanup() {
        // Intentionally empty only because we're not opening an InputStream or another I/O
        // resource.
    }

    @Override
    public void cancel() {
        // Intentionally empty.
    }

    @Override
    public Class<ParcelFileDescriptor> getDataClass() {
        return ParcelFileDescriptor.class;
    }

    @Override
    public DataSource getDataSource() {
        return DataSource.LOCAL;
    }
}
