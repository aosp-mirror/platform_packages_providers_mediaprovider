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
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.navigation.NavController
import com.android.providers.media.tools.photopickerv2.R
import com.android.providers.media.tools.photopickerv2.navigation.NavigationItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PhotoPickerTitle is a composable function that displays the title of the PhotoPicker app.
 *
 * @param label the label to be displayed as the title of the PhotoPicker app.
 */
@Composable
fun PhotoPickerTitle(label: String = stringResource(id = R.string.title_photopicker)) {
    Text(
        text = label,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        modifier = Modifier.padding(bottom = 16.dp)
    )
}

/**
 * SwitchComponent is a composable function that displays a switch component.
 *
 * @param label the label to be displayed next to the switch component.
 * @param checked the state of the switch component.
 * @param onCheckedChange the callback function to be called when the switch component is changed.
 */
@Composable
fun SwitchComponent(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = Color.Black,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * TextFieldComponent is a composable function that displays a text field component.
 *
 * @param value the value of the text field component.
 * @param onValueChange the callback function to be called when the text field component is changed.
 * @param label the label to be displayed next to the text field component.
 * @param keyboardOptions the keyboard options to be used for the text field component.
 * @param modifier the modifier to be applied to the text field component.
 */
@Composable
fun TextFieldComponent(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = keyboardOptions,
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent)
    )
}

/**
 * ErrorMessage is a composable function that displays an error message.
 *
 * @param text the text to be displayed as the error message.
 */
@Composable
fun ErrorMessage(
    text: String
) {
    val context = LocalContext.current

    if (text.isNotEmpty()) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}

/**
 * ButtonComponent is a composable function that displays a button component.
 *
 * @param label the label to be displayed on the button component.
 * @param onClick the callback function to be called when the button component is clicked.
 * @param modifier the modifier to be applied to the button component.
 * @param colors the color of the button.
 * @param enabled the enabled state of the button component.
 */
@Composable
fun ButtonComponent(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        colors = colors,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Text(label)
    }
}

/**
 * NavigationComponent is a composable function that displays a navigation component.
 *
 * @param navController the navigation controller to be used for the navigation component.
 * @param items the list of items to be displayed in the navigation component.
 * @param currentRoute the current route of the navigation component.
 */
@Composable
fun NavigationComponent(
    navController: NavController,
    items: List<NavigationItem>,
    currentRoute: String?
) {
    NavigationBar {
        items.forEach { item ->
            NavigationBarItem(
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.label)) },
                selected = currentRoute == item.route,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

/**
 * DropdownList is a composable function that creates a dropdown list component.
 *
 * @param label The label to be displayed above the dropdown list.
 * @param options A list of options to be displayed in the dropdown list.
 * @param selectedOption The currently selected option.
 * @param onOptionSelected A callback function that gets called when an option is selected.
 * @param enabled A boolean flag to enable or disable the dropdown list.
 */
@Composable
fun DropdownList(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    enabled: Boolean
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column {
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
            color = if (enabled) Color.Black else Color.Gray
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (enabled) Color.Transparent else Color.Gray)
                .clickable { if (enabled) isExpanded = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = selectedOption,
                color = if (enabled) Color.Black else Color.Gray,
                modifier = Modifier.padding(8.dp)
            )
        }

        if (isExpanded) {
            Popup(
                alignment = Alignment.TopCenter,
                properties = PopupProperties(
                    excludeFromSystemGesture = true,
                ),
                onDismissRequest = { isExpanded = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState)
                        .border(1.dp, Color.Gray)
                        .background(Color.White),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    options.forEachIndexed { index, option ->
                        if (index != 0) {
                            HorizontalDivider(thickness = 1.dp, color = Color.LightGray)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (enabled) {
                                        onOptionSelected(option)
                                        isExpanded = false
                                    }
                                }
                                .background(if (enabled) Color.Transparent else Color.LightGray),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option,
                                color = if (enabled) Color.Black else Color.Gray,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetaDataDetails(
    uri: Uri,
    contentResolver: ContentResolver,
    showMetaData: Boolean,
    inDocsUITab: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (showMetaData) {
            val cursor: Cursor? = contentResolver.query(
                uri, null, null, null, null
            )
            cursor?.use {
                // Metadata Details for PhotoPicker Tab and PickerChoice Tab
                if (!inDocsUITab){
                    if (it.moveToNext()) {
                        val mediaUri = it.getString(it.getColumnIndexOrThrow(
                            MediaStore.Images.Media.DATA))
                        val displayName = it.getString(it.getColumnIndexOrThrow(
                            MediaStore.Images.Media.DISPLAY_NAME))
                        val size = it.getLong(it.getColumnIndexOrThrow(
                            MediaStore.Images.Media.SIZE))
                        val sizeInKB = size / 1000
                        val dateTaken = it.getLong(it.getColumnIndexOrThrow(
                            MediaStore.Images.Media.DATE_TAKEN))

                        val duration =
                            it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DURATION))
                        val durationInSec = duration / 1000
                        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        val dateString = formatter.format(Date(dateTaken))

                        Column {
                            Text(
                                text = "Meta Data Details:",
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                            )
                            Text(text = "URI: $mediaUri")
                            Text(text = "Display Name: $displayName")
                            Text(text = "Size: $sizeInKB KB")
                            Text(text = "Date Taken: $dateString")
                            Text(text = "Duration: $durationInSec s")
                        }
                    }
                } else {
                    // Metadata Details for DocsUI Tab
                    if (it.moveToNext()){
                        val documentID = it.getLong(it.getColumnIndexOrThrow(
                            MediaStore.Images.Media.DOCUMENT_ID))
                        val mimeType = it.getString(it.getColumnIndexOrThrow(
                            MediaStore.Images.Media.MIME_TYPE))
                        val displayName =
                            it.getString(it.getColumnIndexOrThrow(
                                MediaStore.Images.Media.DISPLAY_NAME))
                        val size = it.getLong(it.getColumnIndexOrThrow(
                            MediaStore.Images.Media.SIZE))
                        val sizeInKB = size / 1000
                        Column {
                            Text(
                                text = "Meta Data Details:",
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                            )

                            Text(text = "Document ID: $documentID")
                            Text(text = "Display Name: $displayName")
                            Text(text = "Size: $sizeInKB KB")
                            Text(text = "Mime Type: $mimeType")
                        }
                    }
                }
            }
        }
    }
}

enum class LaunchLocation {
    PHOTOS_TAB,
    ALBUMS_TAB;

    companion object {
        fun getListOfAvailableLocations(): List<String> {
            return entries.map { it -> it.name }
        }
    }
}
