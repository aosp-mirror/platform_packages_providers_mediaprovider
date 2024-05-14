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

package com.android.photopicker.core.components

import com.android.photopicker.data.model.Group.Album
import com.android.photopicker.data.model.Media

/**
 * A wrapper around items in the [MediaGrid] to allow the grid to distinguish between separators and
 * individual [Media].
 */
sealed class MediaGridItem {

    /**
     * Represents a single [Media] (photo or video) in the [MediaGrid].
     *
     * @property media The media that this item represents.
     */
    data class MediaItem(val media: Media) : MediaGridItem()

    /**
     * Represents an [Album] in the [MediaGrid].
     *
     * @property album The album that this item represents.
     */
    data class AlbumItem(val album: Album) : MediaGridItem()

    /**
     * Represents a separator in the [MediaGrid]. (Such as a month separator.)
     *
     * @property label A label that can be used to represent this separator in the UI.
     */
    data class SeparatorItem(val label: String) : MediaGridItem()


    /**
     * Handles operations that requires customized output based on the type of [MediaGridItem].
     */
    companion object {
        /**
         * Assembles a key for a [MediaGridItem]. This key must be always be stable and unique in
         * the grid.
         *
         * @return a Unique, stable key that represents one item in the grid.
         */
        fun keyFactory(item: MediaGridItem?, index: Int): String {
            return when (item) {
                is MediaItem -> "${item.media.pickerId}"
                is SeparatorItem -> "${item.label}_$index"
                is AlbumItem -> "${item.album.pickerId}" // check if this should be id or pickerId
                null -> "$index"
            }
        }

        /**
         * Default builder for generating a contentType signature for a grid item.
         *
         * ContentType is used to signify re-use of containers to increase the efficiency of the
         * Grid loading. Each subtype of MediaGridItem should return a distinct value to ensure
         * optimal re-use.
         *
         * @return The contentType signature of the provided item.
         */
        fun defaultBuildContentType(item: MediaGridItem?): Int {
            return when (item) {
                is MediaItem -> 1
                is SeparatorItem -> 2
                is AlbumItem -> 3
                null -> 0
            }
        }
    }
}
