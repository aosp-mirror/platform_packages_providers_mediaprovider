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
import android.media.ApplicationMediaCapabilities
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.core.configuration.IllegalIntentExtraException
import com.android.photopicker.core.navigation.PhotopickerDestinations

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
        }
        // Handle [Intent.EXTRA_ALLOW_MULTIPLE] for GET_CONTENT takeover.
        else if (
            getAction() == Intent.ACTION_GET_CONTENT &&
                getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        ) {
            MediaStore.getPickImagesMaxLimit()
        } else if (getAction() == MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP) {
            MediaStore.getPickImagesMaxLimit()
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
 * Validate the correct action and fetch the [EXTRA_PICK_IMAGES_IN_ORDER] extra from the intent.
 *
 * [EXTRA_PICK_IMAGES_IN_ORDER] only works in ACTION_PICK_IMAGES, so this method will throw
 * [IllegalIntentExtraException] for any other actions.
 *
 * @return the value of the extra, default if it is not set or an [IllegalIntentExtraException] is
 *   thrown if the action is not supported.
 */
fun Intent.getPickImagesInOrderEnabled(default: Boolean): Boolean {

    if (extras?.containsKey(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER) == true) {
        return when (action) {
            MediaStore.ACTION_PICK_IMAGES ->
                getBooleanExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, default)
            else ->
                // All other actions are unsupported.
                throw IllegalIntentExtraException(
                    "EXTRA_PICK_IMAGES_IN_ORDER is not supported for ${getAction()}"
                )
        }
    } else {
        return default
    }
}

/**
 * Validate the [MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB] extra from the intent.
 * [EXTRA_PICK_IMAGES_LAUNCH_TAB] only works in ACTION_PICK_IMAGES, and is ignored in all other
 * configurations.
 *
 * @param default The default to use in the case of an invalid or missing extra.
 * @return The [PhotopickerDestinations] that matches the value in the intent, or the default if
 *   nothing matches.
 */
fun Intent.getStartDestination(default: PhotopickerDestinations): PhotopickerDestinations {

    if (getExtras()?.containsKey(MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB) == true) {
        return when (getAction()) {
            // This intent extra is only supported for ACTION_PICK_IMAGES
            MediaStore.ACTION_PICK_IMAGES ->
                when (
                    getIntExtra(
                        MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB,
                        // The default does not match any destination
                        /* default= */ 9999,
                    )
                ) {
                    MediaStore.PICK_IMAGES_TAB_ALBUMS -> PhotopickerDestinations.ALBUM_GRID
                    MediaStore.PICK_IMAGES_TAB_IMAGES -> PhotopickerDestinations.PHOTO_GRID
                    // Some unknown value was specified, or it was null
                    else -> default
                }
            // All other actions are unsupported.
            else ->
                throw IllegalIntentExtraException(
                    "EXTRA_PICK_IMAGES_LAUNCH_TAB is not supported for ${getAction()}, " +
                        "use ACTION_PICK_IMAGES instead."
                )
        }
    } else {
        return default
    }
}

/**
 * @return An [ArrayList] of MIME type filters derived from the intent. If no MIME type filters
 *   should be applied, return null.
 * @throws [IllegalIntentExtraException] if the input MIME types filters cannot be applied.
 */
fun Intent.getPhotopickerMimeTypes(): ArrayList<String>? {

    // Depending on how the extra was set it's necessary to check a couple of different places
    val mimeTypesParcelable = getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
    val mimeTypesArrayList = getStringArrayListExtra(Intent.EXTRA_MIME_TYPES)
    val mimeTypes: List<String>? = mimeTypesParcelable?.toList() ?: mimeTypesArrayList?.toList()

    mimeTypes?.let {
        if (mimeTypes.all { mimeType -> isMediaMimeType(mimeType) }) {
            return mimeTypes.toCollection(ArrayList())
        } else {

            // If the current action is ACTION_PICK_IMAGES then */* is a valid input that should
            // be interpreted as "all media mimetypes"
            if (action.equals(MediaStore.ACTION_PICK_IMAGES)) {
                if (it.contains("*/*")) {
                    return arrayListOf("image/*", "video/*")
                }
            }
            // Picker can be opened from Documents UI by the user. In this case, the intent action
            // will be Intent.ACTION_GET_CONTENT and the mime types may contain non-media types.
            // Don't apply any MIME type filters in this case. Otherwise, throw an exception.
            if (!action.equals(Intent.ACTION_GET_CONTENT)) {
                throw IllegalIntentExtraException(
                    "Only media MIME types can be accepted. Input MIME types: $mimeTypes"
                )
            }
        }
    }
        ?:
        // None of the intent extras were set, so check in the intent itself for [setType]
        type?.let {
            if (isMediaMimeType(it)) {
                return arrayListOf(it)
            } else {

                // If the current action is ACTION_PICK_IMAGES then */* is a valid input that should
                // be interpreted as "all media mimetypes"
                if (action.equals(MediaStore.ACTION_PICK_IMAGES)) {
                    if (it == "*/*") {
                        return arrayListOf("image/*", "video/*")
                    }
                }
                // Picker can be opened from Documents UI by the user. In this case, the intent
                // action will be Intent.ACTION_GET_CONTENT and the mime types may contain non-media
                // types. Don't apply any MIME type filters in this case. Otherwise, throw an
                // exception.
                if (!action.equals(Intent.ACTION_GET_CONTENT)) {
                    throw IllegalIntentExtraException(
                        "Only media MIME types can be accepted. Input MIME types: $it"
                    )
                }
            }
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
 * Fetch the [EXTRA_PICKER_PRE_SELECTION_URIS] extra from the intent.
 *
 * [EXTRA_PICKER_PRE_SELECTION_URIS] only works in ACTION_PICK_IMAGES, so this method will throw
 * [IllegalIntentExtraException] for any other actions.
 *
 * @return the value of the extra, null if it is not set or an [IllegalIntentExtraException] is
 *   thrown if the action is not supported.
 */
@Suppress("DEPRECATION")
fun Intent.getPickImagesPreSelectedUris(): ArrayList<Uri>? {
    val preSelectedUris: ArrayList<Uri>? =
        if (extras?.containsKey(MediaStore.EXTRA_PICKER_PRE_SELECTION_URIS) == true) {
            when (action) {
                MediaStore.ACTION_PICK_IMAGES -> {
                    extras?.let {
                        (if (SdkLevel.isAtLeastT()) {
                                it.getParcelableArrayList(
                                    MediaStore.EXTRA_PICKER_PRE_SELECTION_URIS,
                                    Uri::class.java,
                                ) as ArrayList<Uri>
                            } else {
                                it.getParcelableArrayList<Uri>(
                                    MediaStore.EXTRA_PICKER_PRE_SELECTION_URIS
                                ) as ArrayList<Uri>
                            })
                            .also { uris ->
                                val numberOfItemsAllowed =
                                    getPhotopickerSelectionLimitOrDefault(
                                        MediaStore.getPickImagesMaxLimit()
                                    )
                                if (uris.size > numberOfItemsAllowed) {
                                    throw IllegalIntentExtraException(
                                        "The number of URIs exceed the maximum allowed limit: " +
                                            "$numberOfItemsAllowed"
                                    )
                                }
                            }
                    }
                }
                else -> {
                    // All other actions are unsupported.
                    throw IllegalIntentExtraException(
                        "EXTRA_PICKER_PRE_SELECTION_URIS is not supported for ${getAction()}"
                    )
                }
            }
        } else {
            null
        }
    return preSelectedUris
}

/**
 * Fetches the [MediaStore.EXTRA_MEDIA_CAPABILITIES] extra from the intent.
 *
 * @return The [ApplicationMediaCapabilities] if present, null otherwise.
 */
@Suppress("DEPRECATION")
@RequiresApi(Build.VERSION_CODES.S)
fun Intent.getApplicationMediaCapabilities(): ApplicationMediaCapabilities? {
    extras?.apply {
        if (containsKey(MediaStore.EXTRA_MEDIA_CAPABILITIES)) {
            if (action != MediaStore.ACTION_PICK_IMAGES) {
                // This intent extra is only supported for ACTION_PICK_IMAGES
                throw IllegalIntentExtraException(
                    "EXTRA_MEDIA_CAPABILITIES is not supported for $action, " +
                        "use ACTION_PICK_IMAGES instead."
                )
            }

            return if (SdkLevel.isAtLeastT()) {
                getParcelable(
                    MediaStore.EXTRA_MEDIA_CAPABILITIES,
                    ApplicationMediaCapabilities::class.java,
                )
            } else {
                getParcelable(MediaStore.EXTRA_MEDIA_CAPABILITIES)
            }
        }
    }

    return null
}

/**
 * Determines if the mimeType is a media mimetype that Photopicker can support.
 *
 * @return Whether the mimetype is supported by Photopicker.
 */
private fun isMediaMimeType(mimeType: String?): Boolean {
    return mimeType?.let { it.startsWith("image/") || it.startsWith("video/") } ?: false
}
