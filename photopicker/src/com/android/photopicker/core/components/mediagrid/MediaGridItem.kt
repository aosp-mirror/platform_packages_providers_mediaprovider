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
     * Represents a separator in the [MediaGrid]. (Such as a month separator.)
     *
     * @property label A label that can be used to represent this separator in the UI.
     */
    data class SeparatorItem(val label: String) : MediaGridItem()
}
