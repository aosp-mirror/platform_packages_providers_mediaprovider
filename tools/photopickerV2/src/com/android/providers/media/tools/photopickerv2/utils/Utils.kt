/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.providers.media.tools.photopickerv2.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.android.providers.media.tools.photopickerv2.photopicker.PhotoPickerViewModel

/**
 * isImage checks if the provided URI points to an image file.
 *
 * @param context The application context.
 * @param uri The URI of the file to check.
 * @return True if the URI points to an image file, false otherwise.
 */
fun isImage(context: Context, uri: Uri): Boolean {
    val contentResolver: ContentResolver = context.contentResolver
    val type = contentResolver.getType(uri)
    return type?.startsWith("image/") == true
}

/**
 * Resets the selected media in the provided PhotoPickerViewModel.
 *
 * @param photoPickerViewModel The PhotoPickerViewModel instance to reset.
 */
fun resetMedia(photoPickerViewModel: PhotoPickerViewModel) {
    photoPickerViewModel.updateSelectedMediaList(emptyList())
}



