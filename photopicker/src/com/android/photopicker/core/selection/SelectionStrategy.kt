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

package com.android.photopicker.core.selection

import android.provider.MediaStore
import com.android.photopicker.core.configuration.PhotopickerConfiguration

/**
 * Selection of items and the way items are processed can vary based on the configuration
 * photo picker is being used in. This class provides all the different modes for selection and also
 * a method that accepts the configuration and returns the suitable mode of selection suitable for
 * it.
 */
enum class SelectionStrategy {
    DEFAULT,
    GRANTS_AWARE_SELECTION;

    companion object {
        fun determineSelectionStrategy(
            configuration: PhotopickerConfiguration
        ): SelectionStrategy {
            when (configuration.action) {
                // if the current action in configuration is
                // MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP then the returned selection should
                // be of implementation GrantsAwareSelectionImpl.
                MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP ->
                    return GRANTS_AWARE_SELECTION

                else ->
                    return DEFAULT
            }
        }
    }
}
