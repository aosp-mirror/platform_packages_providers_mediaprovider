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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
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

    @Test
    fun testBannerDisplaysTitleAndMessage() {
        composeTestRule.setContent { Banner(banner = TEST_BANNER_1) }

        composeTestRule.onNodeWithText(TEST_BANNER_1_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(TEST_BANNER_1_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun testBannerDisplaysActionButton() {
        composeTestRule.setContent { Banner(banner = TEST_BANNER_1) }
        composeTestRule
            .onNodeWithText(TEST_BANNER_1_ACTION_LABEL)
            .assertIsDisplayed()
            .assert(hasClickAction())
    }

    @Test
    fun testBannerDisplaysIcon() {
        composeTestRule.setContent { Banner(banner = TEST_BANNER_1) }
        composeTestRule
            .onNode(hasContentDescription(TEST_BANNER_1_ICON_DESCRIPTION))
            .assertIsDisplayed()
    }

    @Test
    fun testBannerDisplaysDismissButtonForDismissable() {
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()
        val dismissString = resources.getString(R.string.photopicker_dismiss_banner_button_label)
        composeTestRule.setContent { Banner(TEST_BANNER_1) }
        composeTestRule.onNodeWithText(dismissString).assertIsDisplayed()
    }

    @Test
    fun testBannerHidesDismissButton() {
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()
        val dismissString = resources.getString(R.string.photopicker_dismiss_banner_button_label)
        composeTestRule.setContent { Banner(TEST_BANNER_2) }
        composeTestRule.onNodeWithText(dismissString).assertIsNotDisplayed()
    }
}
