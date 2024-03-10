/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.core.glide

import android.content.ContentResolver
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import java.io.InputStream

/**
 * A factory class responsible for instantiating the [PhotopickerModelLoader].
 *
 * Dependencies are provided by the [AppGlideModule].
 *
 * @property contentResolver the [ContentResolver] this ModelLoader and it's workers should use.
 */
class PhotopickerModelLoaderFactory(val contentResolver: ContentResolver) :
    ModelLoaderFactory<GlideLoadable, InputStream> {

    /**
     * Glide will call this once during initialization to get an instance of the ModelLoader for the
     * [GlideLoadable] registration.
     */
    override fun build(unused: MultiModelLoaderFactory): ModelLoader<GlideLoadable, InputStream> {
        return PhotopickerModelLoader(contentResolver)
    }

    /** Can be used to release any held resources when Glide no longer needs this factory. */
    override fun teardown() {}
}
