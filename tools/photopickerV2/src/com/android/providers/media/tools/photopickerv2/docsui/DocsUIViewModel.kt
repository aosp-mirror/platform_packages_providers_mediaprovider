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
package com.android.providers.media.tools.photopickerv2.docsui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * DocsUIViewModel is responsible for managing the state and logic
 * of the DocsUI feature.
 */
class DocsUIViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val _selectedMedia = MutableStateFlow<List<Uri>>(emptyList())
    val selectedMedia: StateFlow<List<Uri>> = _selectedMedia

    fun updateSelectedMediaList(uris: List<Uri>) {
        _selectedMedia.value = uris
    }

    fun validateAndLaunchPicker(
        isActionGetContentSelected: Boolean,
        isOpenDocumentSelected: Boolean,
        allowMultiple: Boolean,
        selectedMimeType: String,
        allowCustomMimeType: Boolean,
        customMimeTypeInput: String,
        launcher: (Intent) -> Unit
    ): String? {

        var finalMimeType = ""
        if (allowCustomMimeType) finalMimeType = customMimeTypeInput
        else if (selectedMimeType != "") finalMimeType = selectedMimeType
        else finalMimeType = "*/*"

        val intent = if (isActionGetContentSelected) {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = finalMimeType
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        } else if (isOpenDocumentSelected) {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = finalMimeType
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
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