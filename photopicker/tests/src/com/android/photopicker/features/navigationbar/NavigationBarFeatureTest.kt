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

package com.android.photopicker.features.navigationbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.R
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.configuration.testActionPickImagesConfiguration
import com.android.photopicker.core.configuration.testGetContentConfiguration
import com.android.photopicker.core.configuration.testPhotopickerConfiguration
import com.android.photopicker.core.configuration.testUserSelectImagesForAppConfiguration
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NavigationBarFeatureTest : PhotopickerFeatureBaseTest() {

    private val TEST_TAG_NAV_BAR = "navigation_bar"
    private val testScope: TestScope = TestScope()

    @get:Rule() val composeTestRule = createComposeRule()

    @Composable
    fun composeNavigationBar(featureManager: FeatureManager) {

        CompositionLocalProvider(
            LocalFeatureManager provides featureManager,
            LocalNavController provides createNavController(),
        ) {
            featureManager.composeLocation(
                Location.NAVIGATION_BAR,
                maxSlots = 1,
                modifier = Modifier.testTag(TEST_TAG_NAV_BAR),
            )
        }
    }

    /* Ensures the NavigationBar is drawn with the production registered features. */
    @Test
    fun testNavigationBarProductionConfig() {

        val photosGridNavButtonLabel =
            getTestableContext()
                .getResources()
                .getString(R.string.photopicker_photos_nav_button_label)

        val featureManager =
            buildFeatureManagerWithFeatures(FeatureManager.KNOWN_FEATURE_REGISTRATIONS)

        composeTestRule.setContent { composeNavigationBar(featureManager) }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasTestTag(TEST_TAG_NAV_BAR)).assertIsDisplayed()

        // Photos Grid Nav Button
        composeTestRule
            .onNode(hasText(photosGridNavButtonLabel))
            .assertIsDisplayed()
            .assert(hasClickAction())
    }

    /*
     * Ensures the NavigationBar is drawn with the production registered features when the
     * configuration is for [ACTION_PICK_IMAGES]
     */
    @Test
    fun testNavigationBarProductionConfig_ActionPickImages() {
        val featureManager =
            buildFeatureManagerWithFeatures(
                features = FeatureManager.KNOWN_FEATURE_REGISTRATIONS,
                config = testActionPickImagesConfiguration,
            )

        composeTestRule.setContent { composeNavigationBar(featureManager) }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasTestTag(TEST_TAG_NAV_BAR)).assertIsDisplayed()
    }

    /*
     * Ensures the NavigationBar is drawn with the production registered features when the
     * configuration is for [ACTION_GET_CONTENT]
     */
    @Test
    fun testNavigationBarProductionConfig_GetContent() {
        val featureManager =
            buildFeatureManagerWithFeatures(
                features = FeatureManager.KNOWN_FEATURE_REGISTRATIONS,
                config = testGetContentConfiguration,
            )

        composeTestRule.setContent { composeNavigationBar(featureManager) }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasTestTag(TEST_TAG_NAV_BAR)).assertIsDisplayed()
    }

    /*
     * Ensures the NavigationBar is drawn with the production registered features when the
     * configuration is for [ACTION_USER_SELECT_IMAGES_FOR_APP]
     */
    @Test
    fun testNavigationBarProductionConfig_UserSelectImagesForApp() {
        val featureManager =
            buildFeatureManagerWithFeatures(
                features = FeatureManager.KNOWN_FEATURE_REGISTRATIONS,
                config = testUserSelectImagesForAppConfiguration
            )

        composeTestRule.setContent { composeNavigationBar(featureManager) }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasTestTag(TEST_TAG_NAV_BAR)).assertIsDisplayed()
    }

    /**
     * Builds a feature manager that is initialized with the given feature registrations and config.
     *
     * @param features the set of feature registrations
     * @param config the [PhotopickerConfiguration] provided to FeatureManager.
     */
    private fun buildFeatureManagerWithFeatures(
        features: Set<FeatureRegistration>,
        config: PhotopickerConfiguration = testPhotopickerConfiguration,
    ): FeatureManager {
        return FeatureManager(
            configuration =
                provideTestConfigurationFlow(
                    scope = testScope.backgroundScope,
                    defaultConfiguration = config,
                ),
            scope = testScope.backgroundScope,
            registeredFeatures = features,
            /*coreEventsConsumed=*/ setOf<RegisteredEventClass>(),
            /*coreEventsProduced=*/ setOf<RegisteredEventClass>(),
        )
    }
}
