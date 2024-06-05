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

package com.android.photopicker.extensions

import android.content.Intent
import android.provider.MediaStore
import com.android.photopicker.core.configuration.IllegalIntentExtraException

/**
 * Check the various possible actions the intent could be running under and extract a valid value
 * from EXTRA_PICK_IMAGES_MAX
 *
 * @param default The default value to use if a suitable one cannot be found.
 * @return a valid selection limit set on the [Intent], or the default value if the provided value
 *   is invalid, or not set.
 */
fun Intent.getPhotopickerSelectionLimitOrDefault(default: Int): Int {

    val limit =
        if (
            getAction() == MediaStore.ACTION_PICK_IMAGES &&
                getExtras()?.containsKey(MediaStore.EXTRA_PICK_IMAGES_MAX) ?: false
        ) {
            // ACTION_PICK_IMAGES supports EXTRA_PICK_IMAGES_MAX,
            // so return the value from the intent
            getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1)
        } else if (
            getAction() == Intent.ACTION_GET_CONTENT &&
                extras?.containsKey(MediaStore.EXTRA_PICK_IMAGES_MAX) ?: false
        ) {
            // GET_CONTENT does not support EXTRA_PICK_IMAGES_MAX
            throw IllegalIntentExtraException(
                "EXTRA_PICK_IMAGES_MAX is not allowed for ACTION_GET_CONTENT, " +
                    "use ACTION_PICK_IMAGES instead."
            )
        } else {
            // No EXTRA_PICK_IMAGES_MAX was set, return the provided default
            default
        }

    // Ensure the limit extracted from above is in the allowed range
    if (limit !in 1..MediaStore.getPickImagesMaxLimit()) {
        throw IllegalIntentExtraException(
            "EXTRA_PICK_IMAGES_MAX not in the allowed range. Must be between 1 " +
                "and ${MediaStore.getPickImagesMaxLimit()}"
        )
    }

    return limit
}
