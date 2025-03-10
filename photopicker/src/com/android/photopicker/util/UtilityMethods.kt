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

package com.android.photopicker.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_ANIMATED_WEBP
import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF
import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_MOTION_PHOTO
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.photopicker.R
import com.android.photopicker.data.model.Media
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Custom hashing function to generate a stable hash code value for any object given the input
 * values.
 *
 * There is perhaps a couple of reasons for choosing 31. The main reason is that it is a prime
 * number and prime numbers have better distribution results in hashing algorithms, by other words
 * the hashing outputs have less collisions for different inputs.
 *
 * The second reason is because 31 has a nice property – its multiplication can be replaced by a
 * bitwise shift which is faster than the standard multiplication: 31 * i == (i << 5) - i
 *
 * Modern VMs (such as the Android runtime) will perform this optimization automatically.
 */
fun hashCodeOf(vararg values: Any?) =
    values.fold(0) { acc, value ->
        val hashCode =
            if (value != null && value is Array<*>) value.contentHashCode() else value.hashCode()
        (acc * 31) + hashCode
    }

/**
 * Retrieves an ImageBitmap from a given URI.
 *
 * This function attempts to open an input stream from the provided URI and decode it into an
 * ImageBitmap using BitmapFactory. If successful, the resulting ImageBitmap is returned. If any
 * exception occurs during the process (e.g., the URI is invalid, the file doesn't exist, or there
 * are permission issues), null is returned.
 *
 * @param context A context required to get the content resolver
 * @param uri The URI of the icon to load.
 * @return The ImageBitmap if successfully loaded, otherwise null.
 */
fun getBitmapFromUri(context: Context, uri: Uri): ImageBitmap? {
    return try {
        context.contentResolver.openInputStream(uri).use { inputStream ->
            BitmapFactory.decodeStream(inputStream)?.asImageBitmap()
        }
    } catch (exception: Exception) {
        // Handle exceptions
        Log.e("getBitmapFromUri", "Unable to get Image as bitmap ", exception)
        null
    }
}

/**
 * Composable function that loads image bitmap from the provided URI asynchronously using
 * [LaunchedEffect] and [withContext] to avoid blocking the main thread.
 *
 * @param uri The URI of the image to load.
 * @param dispatcher - A CoroutineDispatcher for running this task
 * @return The loaded [ImageBitmap], or null if an error occurred while loading.
 */
@Composable
fun rememberBitmapFromUri(uri: Uri, dispatcher: CoroutineDispatcher): ImageBitmap? {
    var bitmap: ImageBitmap? by remember { mutableStateOf(null) }
    val context = LocalContext.current
    LaunchedEffect(uri) { withContext(dispatcher) { bitmap = getBitmapFromUri(context, uri) } }
    return bitmap
}

/**
 * Generates a content description for a given media item (photo, video, GIF, etc.). This
 * description is intended to be used for accessibility purposes, providing information about the
 * media to users with visual impairments.
 *
 * @param media The media item for which to generate a content description.
 * @param dateFormat The `DateFormat` object to use for formatting the date the media was taken.
 * @return A string containing the content description for the media.
 */
@Composable
fun getMediaContentDescription(media: Media, dateFormat: DateFormat): String {
    val dateTaken = dateFormat.format(Date(media.dateTakenMillisLong))
    if (media is Media.Video) {
        val duration = DateUtils.formatElapsedTime(media.duration / 1000L)
        return stringResource(R.string.photopicker_video_item_content_desc, dateTaken, duration)
    }
    val itemType: String =
        when (media.standardMimeTypeExtension) {
            _SPECIAL_FORMAT_GIF,
            _SPECIAL_FORMAT_ANIMATED_WEBP -> {
                stringResource(R.string.photopicker_gif)
            }
            _SPECIAL_FORMAT_MOTION_PHOTO -> {
                stringResource(R.string.photopicker_motion_photo)
            }
            else -> {
                stringResource(R.string.photopicker_photo)
            }
        }
    return stringResource(R.string.photopicker_item_content_desc, itemType, dateTaken)
}
