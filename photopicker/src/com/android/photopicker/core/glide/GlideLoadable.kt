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

import android.net.Uri
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.signature.ObjectKey

/** The default mimeType for Glide to use when loading */
val DEFAULT_IMAGE_MIME_TYPE = "image/*"

/**
 * The base object for loading objects with Glide. Any object that implements this interface can be
 * passed through the [PhotopickerGlideModule] loading pipeline.
 */
interface GlideLoadable {

    /**
     * Provide a cache signature for the loadable. Resolution is provided so that a separate
     * signature can be provided for the various resolutions.
     *
     * @return A unique [ObjectKey] to represent this combination of Loadable + Resolution.
     */
    fun getSignature(resolution: Resolution): ObjectKey

    /**
     * Provides the Uri that this loadable's bytes can be retrieved from. Ultimately, this will be
     * what is passed to [ContentResolver] to open an [AssetFileDescriptor] for this loadable.
     *
     * @return The Uri that this loadable represents.
     */
    fun getLoadableUri(): Uri

    /**
     * Provide a glide specific DataSource that represents the origin of this loadable.
     *
     * This affects Glide's caching strategy as Local sources are considered to be cheaper to
     * re-fetch where as Remote sources are more expensive.
     *
     * @return The Glide [DataSource] that represents this loadables origin.
     */
    fun getDataSource(): DataSource

    /**
     * Provide a the MimeType for Glide to use for this loadable.
     * By default, this is ["image / *"], but for certain formats (a more specific image type), this
     * can be overridden.
     */
    fun getMimeTypeForGlide(): String {
      return DEFAULT_IMAGE_MIME_TYPE
    }
}
