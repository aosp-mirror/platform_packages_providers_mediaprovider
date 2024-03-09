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
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoader.LoadData
import java.io.InputStream

/**
 * Loader class that is responsible for assembling the components and data to kick off a load of a
 * [GlideLoadable]
 *
 * @property contentResolver The resolver that should be used for content loads.
 * @property supportedAuthorities A list of currently supported authorities.
 */
class PhotopickerModelLoader(
    val contentResolver: ContentResolver,
) : ModelLoader<GlideLoadable, InputStream> {

    /**
     * This assembles a cache signature for storing the resulting bytes, and instantiates a worker
     * that can actually fetch the required data. The worker will be later called by Glide when the
     * load should begin.
     */
    override fun buildLoadData(
        model: GlideLoadable,
        width: Int,
        height: Int,
        options: Options
    ): LoadData<InputStream> {
        return LoadData(
            model.getSignature(Resolution.THUMBNAIL),
            PhotopickerMediaFetcher(contentResolver, model, width, height, options)
        )
    }

    /**
     * A check by Glide amongst registered ModelLoaders to resolve which loader should handle a
     * particular load.
     *
     * Since [GlideLoadable] is a custom implementation, this is the only ModelLoader that is able
     * to handle it, so if this handles check fails, the load will never start.
     *
     * @return If this model loader is able to load the requested model.
     */
    override fun handles(model: GlideLoadable): Boolean {
        // If the model is a GlideLoadable, this [ModelLoader] should try to handle it.
        return true
    }
}
