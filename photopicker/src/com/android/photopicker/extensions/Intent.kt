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

/**
 * @return An [ArrayList] of MIME type filters derived from the intent. If no MIME type filters
 *   should be applied, return null.
 * @throws [IllegalIntentExtraException] if the input MIME types filters cannot be applied.
 */
fun Intent.getPhotopickerMimeTypes(): ArrayList<String>? {
    val mimeTypes: Array<String>? = getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
    if (mimeTypes != null) {
        if (mimeTypes.all { mimeType -> isMediaMimeType(mimeType) }) {
            return mimeTypes.toCollection(ArrayList())
        } else {
            // Picker can be opened from Documents UI by the user. In this case, the intent action
            // will be Intent.ACTION_GET_CONTENT and the mime types may contain non-media types.
            // Don't apply any MIME type filters in this case. Otherwise, throw an exception.
            if (!action.equals(Intent.ACTION_GET_CONTENT)) {
                throw IllegalIntentExtraException(
                    "Only media MIME types can be accepted. Input MIME types: $mimeTypes"
                )
            }
        }
    } else {
        // Ignore the set type if it is not media type and don't apply any MIME type filters.
        if (type != null && isMediaMimeType(type!!)) return arrayListOf(type!!)
    }
    return null
}

/**
 * Determines if Photopicker is capable of handling the [Intent.EXTRA_MIME_TYPES] provided to the
 * activity in this Photopicker session launched with [android.intent.ACTION_GET_CONTENT].
 *
 * @return true if the list of mimetypes can be handled by Photopicker.
 */
fun Intent.canHandleGetContentIntentMimeTypes(): Boolean {
    if (!hasExtra(Intent.EXTRA_MIME_TYPES)) {
        // If the incoming type is */* then Photopicker can't handle this mimetype
        return isMediaMimeType(getType())
    }

    val mimeTypes = getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
    mimeTypes?.let {

        // If the list of MimeTypes is empty, nothing was explicitly set, so assume that
        // non-media files should be displayed.
        if (mimeTypes.size == 0) return false

        // Ensure all mimetypes in the incoming filter list are supported
        for (mimeType in mimeTypes) {
            if (!isMediaMimeType(mimeType)) {
                return false
            }
        }
    }
        // Should not be null at this point (the intent contains the extra key),
        // but better safe than sorry.
        ?: return false

    return true
}

/**
 * Determines if the mimeType is a media mimetype that Photopicker can support.
 *
 * @return Whether the mimetype is supported by Photopicker.
 */
private fun isMediaMimeType(mimeType: String?): Boolean {
    return mimeType?.let { it.startsWith("image/") || it.startsWith("video/") } ?: false
}
