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
package com.android.providers.media.tools.photopickerv2.pickerchoice

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.os.Build
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.providers.media.tools.photopickerv2.R
import com.android.providers.media.tools.photopickerv2.utils.ButtonComponent
import com.android.providers.media.tools.photopickerv2.utils.MetaDataDetails
import com.android.providers.media.tools.photopickerv2.utils.SwitchComponent
import com.android.providers.media.tools.photopickerv2.utils.isImage
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

/**
 * This is the screen for the PickerChoice tab.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun PickerChoiceScreen(pickerChoiceViewModel: PickerChoiceViewModel = viewModel()) {
    // When VERSION.SDK_INT is lower than VERSION U, then PickerChoice will not work on the device
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Error message when the device's version is lower than Version U
        Text(
            text = stringResource(id = R.string.picker_choice_unsupported),
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            modifier = Modifier.padding(20.dp)
                .paddingFromBaseline(40.dp),
            color = Color.Red
        )
    } else {
        val context = LocalContext.current

        var requestPermissionForImagesOnly by remember { mutableStateOf(false) }
        var requestPermissionForVideosOnly by remember { mutableStateOf(false) }
        var requestPermissionForBoth by remember { mutableStateOf(false) }

        var showMetaData by remember { mutableStateOf(false) }

        val showLatestSelectionOnly by pickerChoiceViewModel
            .latestSelectionOnly.observeAsState(false)

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            val partialGranted = permissions[READ_MEDIA_VISUAL_USER_SELECTED] == true ||
                    requestPermissionForImagesOnly ||
                    requestPermissionForVideosOnly
            if (allGranted || partialGranted) {
                pickerChoiceViewModel.checkPermissions(context.contentResolver)
            } else {
                Toast.makeText(context, "Permissions not granted", Toast.LENGTH_SHORT).show()
            }
        }

        fun resetPermissions() {
            requestPermissionForImagesOnly = false
            requestPermissionForVideosOnly = false
            requestPermissionForBoth = false
        }

        Column(
            modifier = Modifier.run {
                padding(16.dp)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            }
        ){
            Text(
                text = stringResource(id = R.string.tab_pickerchoice),
                fontWeight = FontWeight.Bold,
                fontSize = 25.sp,
                modifier = Modifier.padding(5.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.request_permissions_for),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Request Permission for Only Images
                    SwitchComponent(
                        label = stringResource(id = R.string.images),
                        checked = requestPermissionForImagesOnly,
                        onCheckedChange = {
                            requestPermissionForImagesOnly = it
                            if (it) {
                                resetPermissions()
                                requestPermissionForImagesOnly = true
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Request Permission for Only Videos
                    SwitchComponent(
                        label = stringResource(id = R.string.videos),
                        checked = requestPermissionForVideosOnly,
                        onCheckedChange = {
                            requestPermissionForVideosOnly = it
                            if (it) {
                                resetPermissions()
                                requestPermissionForVideosOnly = true
                            }
                        }
                    )
                }
            }

            // Request Permission for Both Images and Videos
            SwitchComponent(
                label = stringResource(id = R.string.both_images_and_videos),
                checked = requestPermissionForBoth,
                onCheckedChange = {
                    requestPermissionForBoth = it
                    if (it) {
                        resetPermissions()
                        requestPermissionForBoth = true
                    }
                }
            )

            // Switch to enable show latest selection only
            SwitchComponent(
                label = stringResource(id = R.string.show_latest_selection_only),
                checked = showLatestSelectionOnly,
                onCheckedChange = {
                    pickerChoiceViewModel.setLatestSelectionOnly(it)
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                ButtonComponent(
                    label = stringResource(id = R.string.request_permissions),
                    onClick = {
                        when {
                            requestPermissionForImagesOnly ->
                                pickerChoiceViewModel.requestAppPermissions(imagesOnly = true)
                            requestPermissionForVideosOnly ->
                                pickerChoiceViewModel.requestAppPermissions(videosOnly = true)
                            requestPermissionForBoth ->
                                pickerChoiceViewModel.requestAppPermissions()
                        }
                        permissionLauncher.launch(
                            pickerChoiceViewModel.permissionRequest.value ?: arrayOf())
                    },
                    enabled = requestPermissionForImagesOnly ||
                            requestPermissionForVideosOnly ||
                            requestPermissionForBoth,
                    modifier = Modifier.weight(1f)
                )
            }

            // Switch for showing meta data
            SwitchComponent(
                label = stringResource(R.string.show_metadata),
                checked = showMetaData,
                onCheckedChange = { showMetaData = it }
            )

            val mediaList by pickerChoiceViewModel.media.observeAsState(emptyList())
            DisplayMedia(mediaList, showMetaData)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DisplayMedia(mediaList: List<PickerChoiceViewModel.Media>, showMetaData: Boolean) {
    Column {
        mediaList.forEach { media ->
            if (showMetaData) {
                MetaDataDetails(
                    uri = media.uri,
                    contentResolver = LocalContext.current.contentResolver,
                    showMetaData = showMetaData,
                    inDocsUITab = false
                )
            }
            if (isImage(LocalContext.current, media.uri)) {
                // To display image
                GlideImage(
                    model = media.uri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(600.dp)
                        .padding(top = 8.dp)
                )
            } else {
                AndroidView(
                    // To display video
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(media.uri)
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
