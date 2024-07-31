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

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.providers.media.tools.photopickerv2.R
import com.android.providers.media.tools.photopickerv2.utils.ButtonComponent
import com.android.providers.media.tools.photopickerv2.utils.SwitchComponent
import com.android.providers.media.tools.photopickerv2.utils.TextFieldComponent
import com.android.providers.media.tools.photopickerv2.utils.isImage
import com.android.providers.media.tools.photopickerv2.utils.resetMedia
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

/**
 * This is the screen for the DocsUI tab.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DocsUIScreen(docsUIViewModel: DocsUIViewModel = viewModel()) {
    val context = LocalContext.current

    // The ACTION_GET_CONTENT intent is selected by default
    var selectedButton by remember { mutableStateOf<Int?>(R.string.action_get_content) }

    var allowMultiple by remember { mutableStateOf(false) }

    var isActionGetContentSelected by remember { mutableStateOf(true) }
    var isOpenDocumentSelected by remember { mutableStateOf(false) }

    var allowCustomMimeType by remember { mutableStateOf(false) }
    var selectedMimeType by remember { mutableStateOf("") }
    var customMimeTypeInput by remember { mutableStateOf("") }

    var showImagesOnly by remember { mutableStateOf(false) }
    var showVideosOnly by remember { mutableStateOf(false) }

    // Color of ACTION_GET_CONTENT and OPEN_DOCUMENT button
    val getContentColor = if (isActionGetContentSelected){
        ButtonDefaults.buttonColors()
    } else ButtonDefaults.buttonColors(Color.Gray)

    val openDocumentColor = if (isOpenDocumentSelected) {
        ButtonDefaults.buttonColors()
    } else ButtonDefaults.buttonColors(Color.Gray)

    val openDocumentTreeColor = if (!isActionGetContentSelected && !isOpenDocumentSelected) {
        ButtonDefaults.buttonColors()
    } else ButtonDefaults.buttonColors(Color.Gray)

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
            docsUIViewModel.updateSelectedMediaList(uris)
        }
    }

    val resultMedia by docsUIViewModel.selectedMedia.collectAsState()

    fun resetFeatureComponents(
        isGetContentSelected: Boolean,
        isOpenDocumentIntentSelected: Boolean,
        selectedButtonType: Int
    ) {
        isActionGetContentSelected = isGetContentSelected
        isOpenDocumentSelected = isOpenDocumentIntentSelected
        selectedButton = selectedButtonType
        allowMultiple = false
        showImagesOnly = false
        showVideosOnly = false
        selectedMimeType = ""
        resetMedia(docsUIViewModel)
        allowCustomMimeType = false
        customMimeTypeInput = ""
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .fillMaxWidth()
    ){
        Text(
            text = stringResource(id = R.string.tab_docsui),
            fontWeight = FontWeight.Bold,
            fontSize = 25.sp,
            modifier = Modifier.padding(16.dp)
        )

        Row (
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ){
            ButtonComponent(
                label = stringResource(id = R.string.action_get_content),
                onClick = {
                    resetFeatureComponents(
                        isGetContentSelected = true,
                        isOpenDocumentIntentSelected = false,
                        selectedButtonType = R.string.action_get_content
                    )
                },
                modifier = Modifier.weight(1f),
                colors = getContentColor
            )

            ButtonComponent(
                label = stringResource(R.string.open_document),
                onClick = {
                    resetFeatureComponents(
                        isGetContentSelected = false,
                        isOpenDocumentIntentSelected = true,
                        selectedButtonType = R.string.open_document
                    )
                },
                modifier = Modifier.weight(1f),
                colors = openDocumentColor,
            )
        }

        Row (
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ){
            ButtonComponent(
                label = stringResource(id = R.string.open_document_tree),
                onClick = {
                    resetFeatureComponents(
                        isGetContentSelected = false,
                        isOpenDocumentIntentSelected = false,
                        selectedButtonType = R.string.open_document_tree
                    )
                },
                modifier = Modifier.weight(1f),
                colors = openDocumentTreeColor
            )
        }

        if (isActionGetContentSelected || isOpenDocumentSelected){
            // SHOW ONLY IMAGES OR VIDEOS
            if (!allowCustomMimeType) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column (modifier = Modifier.weight(1f)){
                        SwitchComponent(
                            label = stringResource(R.string.show_images_only),
                            checked = showImagesOnly,
                            onCheckedChange = {
                                showImagesOnly = it
                                if (it) {
                                    showVideosOnly = false
                                    selectedMimeType = "image/*"
                                } else if (!showImagesOnly && !showVideosOnly) {
                                    selectedMimeType = ""
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column (modifier = Modifier.weight(1f)){
                        SwitchComponent(
                            label = stringResource(R.string.show_videos_only),
                            checked = showVideosOnly,
                            onCheckedChange = {
                                showVideosOnly = it
                                if (it) {
                                    showImagesOnly = false
                                    selectedMimeType = "video/*"
                                } else if (!showImagesOnly && !showVideosOnly) {
                                    selectedMimeType = ""
                                }
                            }
                        )
                    }
                }
            }

            // Allow Custom Mime Type
            SwitchComponent(
                label = stringResource(id = R.string.allow_custom_mime_type),
                checked = allowCustomMimeType,
                onCheckedChange = {
                    allowCustomMimeType = it
                }
            )

            if (allowCustomMimeType){
                TextFieldComponent(
                    // Custom Mime Type Input
                    value = customMimeTypeInput,
                    onValueChange = { customMimeType ->
                        customMimeTypeInput = customMimeType
                    },
                    label = stringResource(id = R.string.enter_mime_type)
                )
            }

            // Multiple Selection
            SwitchComponent(
                label = stringResource(id = R.string.allow_multiple_selection),
                checked = allowMultiple,
                onCheckedChange = {
                    allowMultiple = it
                }
            )
        }

        // Pick Media Button
        ButtonComponent(
            label = stringResource(R.string.pick_media),
            onClick = {
                // Resetting the custom Mime Type Box when allowCustomMimeType is unselected
                if (!allowCustomMimeType){
                    customMimeTypeInput = ""
                }

                val errorMessage = docsUIViewModel.validateAndLaunchPicker(
                    isActionGetContentSelected = isActionGetContentSelected,
                    isOpenDocumentSelected = isOpenDocumentSelected,
                    allowMultiple = allowMultiple,
                    selectedMimeType = selectedMimeType,
                    allowCustomMimeType = allowCustomMimeType,
                    customMimeTypeInput = customMimeTypeInput,
                    launcher = launcher::launch
                )
                if (errorMessage != null) {
                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column {
            resultMedia.forEach { uri ->
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
                            .height(600.dp)
                            .padding(top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(thickness = 6.dp)
                Spacer(modifier = Modifier.height(17.dp))
            }
        }
    }
}