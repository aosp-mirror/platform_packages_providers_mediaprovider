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

package com.android.photopicker.core.banners

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.R
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Event.LogPhotopickerBannerInteraction
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry.BannerType
import com.android.photopicker.core.events.Telemetry.UserBannerInteraction
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.data.TestPrefetchDataService
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BannerTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val TEST_BANNER_1_TITLE = "I'm a test banner"
    private val TEST_BANNER_1_MESSAGE = "I'm a test banner message"
    private val TEST_BANNER_1_ACTION_LABEL = "Click Me"
    private val TEST_BANNER_1_ICON_DESCRIPTION = "I'm an icon!"
    private val TEST_BANNER_1 =
        object : Banner {

            override val declaration =
                object : BannerDeclaration {
                    override val id = "test_banner"
                    override val dismissable = true
                    override val dismissableStrategy = BannerDeclaration.DismissStrategy.ONCE
                }

            @Composable override fun buildTitle() = TEST_BANNER_1_TITLE

            @Composable override fun buildMessage() = TEST_BANNER_1_MESSAGE

            @Composable override fun actionLabel() = TEST_BANNER_1_ACTION_LABEL

            override fun onAction(context: Context) {}

            @Composable override fun getIcon() = Icons.Filled.VerifiedUser

            @Composable override fun iconContentDescription() = TEST_BANNER_1_ICON_DESCRIPTION
        }

    private val TEST_BANNER_2_TITLE = "I'm another test banner"
    private val TEST_BANNER_2_MESSAGE = "I'm another test banner message"
    private val TEST_BANNER_2 =
        object : Banner {

            // This is kind of an ugly hack, but there are not any [BannerDefinition]
            // that are currently not dismissable, and it's acceptable until there is
            // such a definition to test with.
            override val declaration =
                object : BannerDeclaration {
                    override val id = "test_banner2"
                    override val dismissable = false
                    override val dismissableStrategy = BannerDeclaration.DismissStrategy.ONCE
                }

            @Composable override fun buildTitle() = TEST_BANNER_2_TITLE

            @Composable override fun buildMessage() = TEST_BANNER_2_MESSAGE

            @Composable override fun getIcon() = Icons.Filled.VerifiedUser
        }

    @Composable
    private fun showBanner(banner: Banner, config: PhotopickerConfiguration, events: Events) {

        CompositionLocalProvider(
            LocalPhotopickerConfiguration provides config,
            LocalEvents provides events,
        ) {
            Banner(banner)
        }
    }

    @Test
    fun testBannerDisplaysTitleAndMessage() = runTest {
        val featureManager =
            FeatureManager(
                configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                scope = this.backgroundScope,
                TestPrefetchDataService(),
            )

        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(scope = this.backgroundScope),
                featureManager = featureManager,
            )

        val emissions = mutableListOf<Event>()
        backgroundScope.launch { events.flow.toList(emissions) }

        composeTestRule.setContent {
            showBanner(
                banner = TEST_BANNER_1,
                TestPhotopickerConfiguration.build {
                    action("TEST_ACTION")
                    intent(Intent("TEST_ACTION"))
                },
                events,
            )
        }

        advanceTimeBy(100)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(TEST_BANNER_1_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(TEST_BANNER_1_MESSAGE).assertIsDisplayed()

        val event: LogPhotopickerBannerInteraction =
            checkNotNull(emissions.first() as? LogPhotopickerBannerInteraction) {
                "Emitted event was not LogPhotopickerBannerInteraction."
            }

        assertWithMessage("Expected a banner type in event.")
            .that(event.bannerType)
            .isEqualTo(BannerType.UNSET_BANNER_TYPE)
        assertWithMessage("Expected a banner displayed interaction")
            .that(event.userInteraction)
            .isEqualTo(UserBannerInteraction.UNSET_BANNER_INTERACTION)
    }

    @Test
    fun testBannerDisplaysActionButton() = runTest {
        val featureManager =
            FeatureManager(
                configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                scope = this.backgroundScope,
                TestPrefetchDataService(),
            )

        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(scope = this.backgroundScope),
                featureManager = featureManager,
            )

        val emissions = mutableListOf<Event>()
        backgroundScope.launch { events.flow.toList(emissions) }

        composeTestRule.setContent {
            showBanner(
                banner = TEST_BANNER_1,
                TestPhotopickerConfiguration.build {
                    action("TEST_ACTION")
                    intent(Intent("TEST_ACTION"))
                },
                events,
            )
        }
        composeTestRule
            .onNodeWithText(TEST_BANNER_1_ACTION_LABEL)
            .assertIsDisplayed()
            .assert(hasClickAction())
            .performClick()

        advanceTimeBy(100)
        composeTestRule.waitForIdle()

        val event: LogPhotopickerBannerInteraction =
            checkNotNull(emissions.last() as? LogPhotopickerBannerInteraction) {
                "Emitted event was not LogPhotopickerBannerInteraction."
            }

        assertWithMessage("Expected a banner type in event.")
            .that(event.bannerType)
            .isEqualTo(BannerType.UNSET_BANNER_TYPE)
        assertWithMessage("Expected a banner action button clicked interaction")
            .that(event.userInteraction)
            .isEqualTo(UserBannerInteraction.CLICK_BANNER_ACTION_BUTTON)
    }

    @Test
    fun testBannerDisplaysIcon() = runTest {
        val featureManager =
            FeatureManager(
                configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                scope = this.backgroundScope,
                TestPrefetchDataService(),
            )

        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(scope = this.backgroundScope),
                featureManager = featureManager,
            )

        composeTestRule.setContent {
            showBanner(
                banner = TEST_BANNER_1,
                TestPhotopickerConfiguration.build {
                    action("TEST_ACTION")
                    intent(Intent("TEST_ACTION"))
                },
                events,
            )
        }
        composeTestRule
            .onNode(hasContentDescription(TEST_BANNER_1_ICON_DESCRIPTION))
            .assertIsDisplayed()
    }

    @Test
    fun testBannerDisplaysDismissButtonForDismissable() = runTest {
        val featureManager =
            FeatureManager(
                configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                scope = this.backgroundScope,
                TestPrefetchDataService(),
            )

        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(scope = this.backgroundScope),
                featureManager = featureManager,
            )

        val emissions = mutableListOf<Event>()
        backgroundScope.launch { events.flow.toList(emissions) }

        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()
        val dismissString = resources.getString(R.string.photopicker_dismiss_banner_button_label)
        composeTestRule.setContent {
            showBanner(
                TEST_BANNER_1,
                TestPhotopickerConfiguration.build {
                    action("TEST_ACTION")
                    intent(Intent("TEST_ACTION"))
                },
                events,
            )
        }

        composeTestRule
            .onNodeWithText(dismissString)
            .assertIsDisplayed()
            .assert(hasClickAction())
            .performClick()

        advanceTimeBy(100)
        composeTestRule.waitForIdle()

        val event: LogPhotopickerBannerInteraction =
            checkNotNull(emissions.last() as? LogPhotopickerBannerInteraction) {
                "Emitted event was not LogPhotopickerBannerInteraction."
            }

        assertWithMessage("Expected a banner type in event.")
            .that(event.bannerType)
            .isEqualTo(BannerType.UNSET_BANNER_TYPE)
        assertWithMessage("Expected a banner dismiss button clicked interaction")
            .that(event.userInteraction)
            .isEqualTo(UserBannerInteraction.CLICK_BANNER_DISMISS_BUTTON)
    }

    @Test
    fun testBannerHidesDismissButton() = runTest {
        val featureManager =
            FeatureManager(
                configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                scope = this.backgroundScope,
                TestPrefetchDataService(),
            )

        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(scope = this.backgroundScope),
                featureManager = featureManager,
            )

        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()
        val dismissString = resources.getString(R.string.photopicker_dismiss_banner_button_label)
        composeTestRule.setContent {
            showBanner(
                TEST_BANNER_2,
                TestPhotopickerConfiguration.build {
                    action("TEST_ACTION")
                    intent(Intent("TEST_ACTION"))
                },
                events,
            )
        }
        composeTestRule.onNodeWithText(dismissString).assertIsNotDisplayed()
    }
}
