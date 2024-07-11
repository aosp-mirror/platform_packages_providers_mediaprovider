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

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.android.providers.media.tools.photopickerv2.R
import com.android.providers.media.tools.photopickerv2.navigation.NavigationItem

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
 * @param enabled the enabled state of the switch component.
 */
@Composable
fun SwitchComponent(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
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
            color = if (enabled) Color.Black else Color.Gray,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
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
 * @param enabled the enabled state of the text field component.
 * @param modifier the modifier to be applied to the text field component.
 */
@Composable
fun TextFieldComponent(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = keyboardOptions,
        modifier = modifier
            .fillMaxWidth()
            .background(if (enabled) Color.Transparent else Color.Gray),
        enabled = enabled
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
 * @param enabled the enabled state of the button component.
 * @param modifier the modifier to be applied to the button component.
 */
@Composable
fun ButtonComponent(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(),
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


