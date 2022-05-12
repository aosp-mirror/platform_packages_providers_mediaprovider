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

import android.content.Context;
import android.content.UriMatcher;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.CloudMediaProviderContract;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.resource.bitmap.VideoDecoder;
import com.bumptech.glide.signature.ObjectKey;

/**
 * Custom {@link ModelLoader} to load thumbnails from cloud media provider.
 */
public final class PickerModelLoader implements ModelLoader<Uri, ParcelFileDescriptor> {
    private final Context mContext;

    PickerModelLoader(Context context) {
        mContext = context;
    }

    @Override
    public LoadData<ParcelFileDescriptor> buildLoadData(Uri model, int width, int height,
            Options options) {
        final Long specifiedFrame = options.get(VideoDecoder.TARGET_FRAME);
        final boolean defaultFrame = specifiedFrame == null
                || specifiedFrame == VideoDecoder.DEFAULT_FRAME;
        return new LoadData<>(new ObjectKey(model),
                new PickerThumbnailFetcher(mContext, model, width, height, defaultFrame));
    }

    @Override
    public boolean handles(Uri model) {
        final int pickerId = 1;
        final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
        matcher.addURI(model.getAuthority(),
                CloudMediaProviderContract.URI_PATH_MEDIA + "/*", pickerId);

        // Matches picker URIs of the form content://<authority>/media
        return matcher.match(model) == pickerId;
    }
}
