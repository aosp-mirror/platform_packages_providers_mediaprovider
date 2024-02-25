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

package com.android.photopicker.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.PhotopickerConfiguration
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.features.alwaysdisabledfeature.AlwaysDisabledFeature
import com.android.photopicker.features.highpriorityuifeature.HighPriorityUiFeature
import com.android.photopicker.features.simpleuifeature.SimpleUiFeature
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [PhotopickerNavGraph] composable. */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PhotopickerNavGraphTest {

    @get:Rule val composeTestRule = createComposeRule()

    lateinit var navController: TestNavHostController
    lateinit var featureManager: FeatureManager

    val testRegistrations =
        setOf(
            SimpleUiFeature.Registration,
            HighPriorityUiFeature.Registration,
            AlwaysDisabledFeature.Registration,
        )

    @Before
    fun setup() {

        // Initialize a basic [FeatureManager] with the standard test registrations.
        featureManager =
            FeatureManager(
                PhotopickerConfiguration(action = "TEST_ACTION"),
                TestScope(),
                testRegistrations,
            )
    }

    /**
     * Composable that creates a new [TestNavHostController] and wraps the [PhotopickerNavGraph]
     * with its expected providers
     */
    @Composable
    private fun testNavGraph(featureManager: FeatureManager) {
        navController = TestNavHostController(LocalContext.current)
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.navigatorProvider.addNavigator(DialogNavigator())
        // Provide the feature manager to the compose stack.
        CompositionLocalProvider(LocalFeatureManager provides featureManager) {

            // Provide the nav controller via [CompositionLocalProvider] to
            // simulate how it receives it at runtime.
            CompositionLocalProvider(LocalNavController provides navController) {
                PhotopickerNavGraph()
            }
        }
    }

    /**
     * Ensures that if no features are enabled in [FeatureManager], we always have a default route
     * to prevent the UI from crashing
     */
    @Test
    fun testVerifyStartDestinationWithNoFeatures() {

        val emptyFeatureManager =
            FeatureManager(
                PhotopickerConfiguration(action = "TEST_ACTION"),
                TestScope(),
                emptySet<FeatureRegistration>()
            )

        composeTestRule.setContent { testNavGraph(emptyFeatureManager) }

        val route = navController.currentBackStackEntry?.destination?.route
        assertThat(route).isEqualTo(PhotopickerDestinations.DEFAULT.route)
    }

    /**
     * Ensures that the enabled feature route with the highest priority is set as the default
     * starting route.
     */
    @Test
    fun testStartDestinationWithPriorities() {

        composeTestRule.setContent { testNavGraph(featureManager) }

        val route = navController.currentBackStackEntry?.destination?.route
        assertThat(route).isEqualTo(HighPriorityUiFeature.START_ROUTE)
        composeTestRule.onNodeWithText(HighPriorityUiFeature.START_STRING).assertIsDisplayed()
        composeTestRule.onNodeWithText(HighPriorityUiFeature.DIALOG_STRING).assertDoesNotExist()
    }

    /** Ensures that composables can navigate to dialogs on the graph. */
    @Test
    fun testNavigationGraphIsNavigableToDialogs() {

        composeTestRule.setContent { testNavGraph(featureManager) }

        // Start route is decided based on priority.
        var route = navController.currentBackStackEntry?.destination?.route
        assertThat(route).isEqualTo(HighPriorityUiFeature.START_ROUTE)
        composeTestRule.onNodeWithText(HighPriorityUiFeature.START_STRING).assertIsDisplayed()
        composeTestRule.onNodeWithText(HighPriorityUiFeature.DIALOG_STRING).assertDoesNotExist()
        composeTestRule.onNodeWithText("navigate to dialog").performClick()

        // After clicking the button it should be on the dialog route now
        route = navController.currentBackStackEntry?.destination?.route
        assertThat(route).isEqualTo(HighPriorityUiFeature.DIALOG_ROUTE)
        composeTestRule.onNodeWithText(HighPriorityUiFeature.DIALOG_STRING).assertIsDisplayed()
        composeTestRule.onNodeWithText("navigate to start").performClick()

        // After clicking the button it should be on the start route now
        route = navController.currentBackStackEntry?.destination?.route
        assertThat(route).isEqualTo(HighPriorityUiFeature.START_ROUTE)
        composeTestRule.onNodeWithText(HighPriorityUiFeature.START_STRING).assertIsDisplayed()
        composeTestRule.onNodeWithText(HighPriorityUiFeature.DIALOG_STRING).assertDoesNotExist()
    }

    /** Ensures that composables can navigate between routes on the graph. */
    @Test
    fun testNavigationGraphIsNavigableToFeatureRoutes() {

        composeTestRule.setContent { testNavGraph(featureManager) }

        var route = navController.currentBackStackEntry?.destination?.route
        assertThat(route).isEqualTo(HighPriorityUiFeature.START_ROUTE)
        composeTestRule.onNodeWithText(HighPriorityUiFeature.START_STRING).assertIsDisplayed()
        composeTestRule.onNodeWithText(SimpleUiFeature.UI_STRING).assertDoesNotExist()
        composeTestRule.onNodeWithText("navigate to simple ui").performClick()

        route = navController.currentBackStackEntry?.destination?.route

        assertThat(route).isEqualTo(SimpleUiFeature.SIMPLE_ROUTE)
        composeTestRule.onNodeWithText(HighPriorityUiFeature.START_STRING).assertDoesNotExist()
        composeTestRule.onNodeWithText(SimpleUiFeature.UI_STRING).assertIsDisplayed()

        // Simulate a back press to navigate back to the previous route.
        composeTestRule.runOnUiThread { navController.popBackStack() }
        route = navController.currentBackStackEntry?.destination?.route
        assertThat(route).isEqualTo(HighPriorityUiFeature.START_ROUTE)
    }
}
