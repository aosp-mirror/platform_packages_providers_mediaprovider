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
import android.app.Activity
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.android.providers.media.tools.photopickerv2.utils.isImage
import com.android.providers.media.tools.photopickerv2.R
import com.android.providers.media.tools.photopickerv2.utils.ButtonComponent
import com.android.providers.media.tools.photopickerv2.utils.ErrorMessage
import com.android.providers.media.tools.photopickerv2.utils.PhotoPickerTitle
import com.android.providers.media.tools.photopickerv2.utils.SwitchComponent
import com.android.providers.media.tools.photopickerv2.utils.TextFieldComponent
import com.android.providers.media.tools.photopickerv2.utils.resetApp

/**
 * This is the screen for the PhotoPicker tab.
 */
@SuppressLint("NewApi")
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun PhotoPickerScreen(photoPickerViewModel: PhotoPickerViewModel = viewModel()) {
    val context = LocalContext.current

    // initializing intent extras
    var isOrderSelectionEnabled by remember { mutableStateOf(true) }
    var allowMultiple by remember { mutableStateOf(false) }
    var isActionGetContentSelected by remember { mutableStateOf(false) }
    val mimeTypeOptions = listOf("*/*", "image/*", "video/*")
    var selectedMimeType by remember { mutableStateOf(mimeTypeOptions[0]) }

    // We can only take string as an input, not an int using OutlinedTextField
    var maxSelectionInput by remember { mutableStateOf("10") }
    var maxMediaItemsDisplayed by remember { mutableStateOf(10) } // default items

    var selectionErrorMessage by remember { mutableStateOf("") }
    var maxSelectionLimitError by remember { mutableStateOf("") }

    // Initially, all the pick options are disabled. They get enabled on either selecting
    // action_get_content or pick images button
    var isAnyPickOptionEnabled by remember { mutableStateOf(false) }

    // For handling the result of the photo picking activity
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Get the clipData containing multiple selected items.
            val clipData = result.data?.clipData
            val uris = mutableListOf<Uri>() // An empty list to store the selected URIs

            // If multiple items are selected (clipData is not null), iterate through the items.
            if (clipData != null) {
                // Add each selected item to the URIs list,
                // up to the maxMediaItemsDisplayed limit if multiple selection is allowed
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } else {
                // If only a single item is selected, add its URI to the list
                result.data?.data?.let { uris.add(it) }
            }

            // Update the ViewModel with the list of selected URIs
            photoPickerViewModel.updateSelectedMediaList(uris)
        }
    }

    val resultMedia by photoPickerViewModel.selectedMedia.collectAsState()

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        // Title : PhotoPicker V2
        PhotoPickerTitle()

        // ACTION_PICK_IMAGES or ACTION_GET_CONTENT
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ButtonComponent(
                label = stringResource(id = R.string.pick_images),
                onClick = {
                    isAnyPickOptionEnabled = true
                    isActionGetContentSelected = false
                    maxSelectionInput = "10" // resetting the max Selection limit to default
                    maxMediaItemsDisplayed = 10
                    allowMultiple = false // setting it to false (default)
                    resetApp(photoPickerViewModel)
                },
                modifier = Modifier.weight(1f)
            )

            // ACTION_GET_CONTENT will only support "images/*" and "videos/*"
            // in the Photo picker tab
            ButtonComponent(
                label = stringResource(id = R.string.action_get_content),
                onClick = {
                    isAnyPickOptionEnabled = true
                    isActionGetContentSelected = true
                    allowMultiple = false // setting it to false (default)
                    resetApp(photoPickerViewModel)
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Display Images in Order
        SwitchComponent(
            label = stringResource(id = R.string.display_images_in_order),
            checked = isOrderSelectionEnabled,
            onCheckedChange = { isOrderSelectionEnabled = it },
            enabled = isAnyPickOptionEnabled && !isActionGetContentSelected
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Multiple Selection
        SwitchComponent(
            label = stringResource(id = R.string.allow_multiple_selection),
            checked = allowMultiple,
            onCheckedChange = {
                allowMultiple = it
            },
            enabled = isAnyPickOptionEnabled
        )

        // Max Number of Media Items
        // ACTION_GET_CONTENT does not support the intent EXTRA_PICK_IMAGES_MAX
        // i.e., it doesn't allow user to set a limit on the media items
        if (allowMultiple && !isActionGetContentSelected) {
            TextFieldComponent(
                value = maxSelectionInput,
                onValueChange = {
                    maxSelectionInput = it
                    // Converting the input to int
                    maxMediaItemsDisplayed = it.toIntOrNull() ?: 1
                },
                label = stringResource(id = R.string.max_number_of_media_items),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = isAnyPickOptionEnabled
            )
        }

        // Error Message if invalid input is given to Max number of media items
        if (allowMultiple && maxSelectionLimitError.isNotEmpty()) {
            ErrorMessage(
                text = selectionErrorMessage
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Pick Media Button
        ButtonComponent(
            label = stringResource(R.string.pick_media),
            onClick = {
                // Resetting the maxSelection input box when multiple selection is turned off
                if (!allowMultiple) {
                    maxSelectionLimitError = ""
                    selectionErrorMessage = ""
                    maxSelectionInput = "10"
                    maxMediaItemsDisplayed = 10
                }

                val errorMessage = photoPickerViewModel.validateAndLaunchPicker(
                    isActionGetContentSelected = isActionGetContentSelected,
                    allowMultiple = allowMultiple,
                    maxMediaItemsDisplayed = maxMediaItemsDisplayed,
                    selectedMimeType = selectedMimeType,
                    isOrderSelectionEnabled = isOrderSelectionEnabled,
                    launcher = launcher::launch
                )
                if (errorMessage != null) {
                    maxSelectionLimitError = errorMessage
                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                } else {
                    maxSelectionLimitError = ""
                }
            },
            enabled = isAnyPickOptionEnabled
        )

        // Error Message if there is a wrong input in the max Selection text field
        ErrorMessage(
            text = selectionErrorMessage
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn (
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
        ){
            items(resultMedia) { uri ->
                if (isImage(context, uri)) {
                    // To display image
                    GlideImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxSize()
                            .padding(top = 8.dp)
                    )
                } else {
                    AndroidView(
                        // To display video
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(uri)
                                start()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

