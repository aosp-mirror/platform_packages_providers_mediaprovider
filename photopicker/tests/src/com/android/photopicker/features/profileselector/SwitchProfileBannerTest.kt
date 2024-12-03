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

package com.android.photopicker.features.profileselector

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.inject.TestOptions
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.whenever
import com.google.common.truth.Truth.assertWithMessage
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class SwitchProfileBannerTest : PhotopickerFeatureBaseTest() {

    companion object {
        val USER_ID_PRIMARY: Int = 0
        val USER_HANDLE_PRIMARY: UserHandle = UserHandle.of(USER_ID_PRIMARY)
        val USER_ID_MANAGED: Int = 10
        val USER_HANDLE_MANAGED: UserHandle = UserHandle.of(USER_ID_MANAGED)
    }

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module
    @InstallIn(SingletonComponent::class)
    class TestModule :
        PhotopickerTestModule(TestOptions.build { processOwnerHandle(USER_HANDLE_MANAGED) })

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for ActivityModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = testScope.backgroundScope

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    @Inject lateinit var events: Events
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: Lazy<FeatureManager>
    @Inject lateinit var userHandle: UserHandle
    @Inject lateinit var bannerManager: Lazy<BannerManager>
    @Inject lateinit var userMonitor: Lazy<UserMonitor>
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>

    @BindValue @ApplicationOwned val contentResolver: ContentResolver = MockContentResolver()

    // Needed for UserMonitor
    @Inject lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        hiltRule.inject()
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)

        whenever(mockUserManager.userProfiles) { listOf(USER_HANDLE_PRIMARY, USER_HANDLE_MANAGED) }
        whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
        whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { USER_HANDLE_PRIMARY }
        whenever(mockUserManager.getProfileParent(USER_HANDLE_PRIMARY)) { null }

        val resources = getTestableContext().getResources()
        if (SdkLevel.isAtLeastV()) {
            whenever(mockUserManager.getProfileLabel())
                .thenReturn(
                    // Launching Profile
                    resources.getString(R.string.photopicker_profile_managed_label),
                    // userProfiles[0]
                    resources.getString(R.string.photopicker_profile_primary_label),
                    // userProfiles[1]
                    resources.getString(R.string.photopicker_profile_managed_label),
                )
        }
    }

    @Test
    fun testSwitchProfileBannerIsDisplayedWhenLaunchingProfileIsNotPrimary() =
        testScope.runTest {
            val resources = getTestableContext().getResources()

            bannerManager.get().refreshBanners()
            advanceTimeBy(100)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule.waitForIdle()

            val expectedMessage =
                resources.getString(
                    R.string.photopicker_profile_switch_banner_message,
                    "Work",
                    "Personal",
                )

            composeTestRule.onNode(hasText(expectedMessage)).assertIsDisplayed()

            // Click Switch and ensure the profile has changed and the banner is no longer shown.
            val switchButtonLabel =
                resources.getString(R.string.photopicker_profile_banner_switch_button_label)
            composeTestRule
                .onNode(hasText(switchButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            assertWithMessage("Expected profile to be the primary profile")
                .that(userMonitor.get().userStatus.value.activeUserProfile.handle)
                .isEqualTo(USER_HANDLE_PRIMARY)

            composeTestRule.onNode(hasText(expectedMessage)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(switchButtonLabel)).assertIsNotDisplayed()
        }
}
