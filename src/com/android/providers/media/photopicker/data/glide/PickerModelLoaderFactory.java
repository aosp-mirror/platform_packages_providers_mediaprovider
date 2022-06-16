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
import android.net.Uri;

import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;

import java.io.InputStream;

/**
 * Custom {@link ModelLoaderFactory} which provides a {@link ModelLoader} for loading thumbnails
 * from cloud media provider.
 */
public class PickerModelLoaderFactory implements ModelLoaderFactory<Uri, InputStream> {

    private final Context mContext;

    public PickerModelLoaderFactory(Context context) {
        mContext = context;
    }

    @Override
    public ModelLoader<Uri, InputStream> build(MultiModelLoaderFactory unused) {
        return new PickerModelLoader(mContext);
    }

    @Override
    public void teardown() {
        // Do nothing.
    }
}
