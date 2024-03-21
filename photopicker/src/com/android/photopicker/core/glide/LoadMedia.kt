/*
 *
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

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import com.bumptech.glide.RequestBuilder
import com.android.photopicker.R
import androidx.compose.ui.res.stringResource
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder

/**
 * The composable for loading images through the Glide pipeline.
 *
 * If callers are passing a custom requestBuilderTransformation be sure to properly set caching and
 * resolution parameters as the builder that will be passed is the default builder. This parameter
 * can (usually) be ignored, but it is exposed for special cases and testing modifications.
 *
 * @param media The [GlideLoadable] that should be loaded.
 * @param resolution The desired [Resolution].
 * @param modifier A modifier to apply to the resulting loaded image composable.
 * @param requestBuilderTransformation An optional RequestBuilder to apply to this Glide load
 *   request.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun loadMedia(
    media: GlideLoadable,
    resolution: Resolution,
    modifier: Modifier = Modifier,
    requestBuilderTransformation:
        ((
            media: GlideLoadable, resolution: Resolution, builder: RequestBuilder<Drawable>
        ) -> RequestBuilder<Drawable>)? =
        null
) {
    GlideImage(
        model = media,
        contentDescription = stringResource(R.string.photopicker_media_item),
        modifier = modifier,
        // TODO(b/323830032): Use a proper material theme color here.
        loading = placeholder(ColorPainter(Color.Black)),
        failure = placeholder(ColorPainter(Color.Black)),
    ) {
        requestBuilderTransformation?.invoke(media, resolution, it)
        // If no RequestBuilder function was provided, then apply the loadables signature to ensure
        // the cache is populated.
        ?: it.set(RESOLUTION_REQUESTED, resolution).signature(media.getSignature(resolution))
    }
}
