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
package com.android.providers.media.tools.photopickerv2.photopicker

import android.annotation.SuppressLint
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.Companion.isPhotoPickerAvailable
import androidx.lifecycle.AndroidViewModel
import com.android.providers.media.tools.photopickerv2.utils.LaunchLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * PhotoPickerViewModel is responsible for managing the state and logic
 * of the PhotoPicker feature.
 */
@SuppressLint("NewApi")
class PhotoPickerViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val _selectedMedia = MutableStateFlow<List<Uri>>(emptyList())
    val selectedMedia: StateFlow<List<Uri>> = _selectedMedia

    private val _pickImagesMaxSelectionLimit: Int

    init{
        // If the PhotoPicker is available on the device, getPickImagesMaxLimit is there but not
        // always visible on the SDK (only from Android 13+)
        _pickImagesMaxSelectionLimit = if (isPhotoPickerAvailable(application)){
            val maxLimit = MediaStore.getPickImagesMaxLimit()
            if (maxLimit > 0) maxLimit else Int.MAX_VALUE
        } else {
            Int.MAX_VALUE
        }
    }

    fun updateSelectedMediaList(uris: List<Uri>) {
        _selectedMedia.value = uris
    }

    fun validateAndLaunchPicker(
        isActionGetContentSelected: Boolean,
        allowMultiple: Boolean,
        maxMediaItemsDisplayed: Int,
        selectedMimeType: String,
        allowCustomMimeType: Boolean,
        customMimeTypeInput: String,
        isOrderSelectionEnabled: Boolean,
        selectedLaunchTab: LaunchLocation,
        accentColor: String,
        isPreSelectionEnabled: Boolean,
        launcher: (Intent) -> Unit
    ): String? {
        if (!isActionGetContentSelected && allowMultiple){
            if (maxMediaItemsDisplayed <= 1) {
                return "Enter a valid count greater than one"
            }

            if (maxMediaItemsDisplayed > _pickImagesMaxSelectionLimit) {
                return "Set media item limit within $_pickImagesMaxSelectionLimit items"
            }
        }

        if (accentColor == "") {
            return "Enter an accent color"
        }

        val accentColorLong: Long = try {
            android.graphics.Color.parseColor(accentColor).toLong()
        } catch (e: IllegalArgumentException) {
            android.graphics.Color.parseColor("#FF6200EE").toLong() // Default color
        }

        val intent = if (isActionGetContentSelected) {
            // ACTION_GET_CONTENT supports only images and videos in the PhotoPicker tab
            Intent(Intent.ACTION_GET_CONTENT).apply {
                if (selectedMimeType == "image/*") type = "image/*"
                else if (selectedMimeType == "video/*") type = "video/*"
                else {
                    type = "image/*,video/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                }
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        } else {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                if (allowCustomMimeType) type = customMimeTypeInput
                else if (selectedMimeType != "") type = selectedMimeType

                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                if (allowMultiple) {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxMediaItemsDisplayed)
                }
                putExtra(
                    MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB,
                    if (selectedLaunchTab == LaunchLocation.ALBUMS_TAB) 0 else 1
                )
                putExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, isOrderSelectionEnabled)
                putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, accentColorLong)
                if (isPreSelectionEnabled){
                    Intent(putParcelableArrayListExtra(
                        "android.provider.extra.PICKER_PRE_SELECTION_URIS",
                        ArrayList(_selectedMedia.value)
                    ))
                }
            }
        }

        try {
            launcher(intent)
        } catch (e: ActivityNotFoundException) {
            val errorMessage =
                "No Activity found to handle Intent with type \"" + intent.type + "\""
            Toast.makeText(getApplication(), errorMessage, Toast.LENGTH_SHORT).show()
        }
        return null
    }
}