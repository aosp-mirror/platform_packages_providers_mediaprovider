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
package com.android.providers.media.tools.photopickerv2.navigation

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.providers.media.tools.photopickerv2.R
import com.android.providers.media.tools.photopickerv2.photopicker.PhotoPickerScreen
import com.android.providers.media.tools.photopickerv2.docsui.DocsUIScreen
import com.android.providers.media.tools.photopickerv2.pickerchoice.PickerChoiceScreen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.android.providers.media.tools.photopickerv2.utils.NavigationComponent


/**
 * NavGraph is the main navigation graph of the app.
 * It contains the three tabs of the app :
 * PhotoPicker
 * DocsUI
 * PickerChoice
 */
@SuppressLint
@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val pickerRoutes = listOf(
        NavigationItem.PhotoPicker,
        NavigationItem.DocsUI,
        NavigationItem.PickerChoice
    )
    val selectedRoute = rememberSaveable { mutableStateOf(NavigationItem.PhotoPicker.route) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: selectedRoute.value

    Scaffold(
        /**
         * This navigation bar is to navigate between the three tabs of the app :
         * PhotoPicker
         * DocsUI
         * PickerChoice
         **/
        bottomBar = {
            NavigationComponent(
                navController = navController,
                items = pickerRoutes,
                currentRoute = currentRoute
            )
        }
    ) { innerPadding ->
        NavigationHost(navController = navController, modifier = Modifier.padding(innerPadding))
    }
}

/**
 * NavigationHost is the navigation host for the app.
 * It is responsible for navigating between the three tabs of the app :
 * PhotoPicker
 * DocsUI
 * PickerChoice
 */
@SuppressLint
@Composable
fun NavigationHost(navController: NavController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController as NavHostController,
        startDestination = NavigationItem.PhotoPicker.route,
        modifier = modifier
    ) {
        composable(NavigationItem.PhotoPicker.route) { PhotoPickerScreen() }
        composable(NavigationItem.DocsUI.route) { DocsUIScreen() }
        composable(NavigationItem.PickerChoice.route) { PickerChoiceScreen() }
    }
}

enum class NavigationItem(val route: String, val icon: ImageVector, @StringRes val label: Int) {
    PhotoPicker("photopicker", Icons.Outlined.PhotoLibrary, R.string.tab_photopicker),
    DocsUI("docsui", Icons.Outlined.Folder, R.string.tab_docsui),
    PickerChoice("pickerchoice", Icons.Outlined.PhoneAndroid, R.string.tab_pickerchoice)
}
