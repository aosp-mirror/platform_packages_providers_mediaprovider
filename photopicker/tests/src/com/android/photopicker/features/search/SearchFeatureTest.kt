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

package com.android.photopicker.features.search

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.features.search.model.GlobalSearchState
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.providers.media.flags.Flags
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class SearchFeatureTest : PhotopickerFeatureBaseTest() {
    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) var setFlagsRule = SetFlagsRule()

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

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
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var userHandle: UserHandle
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>

    @BindValue @ApplicationOwned val contentResolver: ContentResolver = MockContentResolver()

    @Inject lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    val deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>> =
        mapOf(
            PrefetchResultKey.SEARCH_STATE to
                runBlocking {
                    async {
                        return@async GlobalSearchState.ENABLED
                    }
                }
        )

    @Before
    fun setup() {

        MockitoAnnotations.initMocks(this)
        hiltRule.inject()
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
    }

    /* Ensures the Search feature is not enabled when flag is disabled. */
    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchFeature_whenFlagDisabled_isNotEnabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage("SearchBar is always enabled when search flag is disabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)

        val testGetContentConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(Intent.ACTION_GET_CONTENT)
                intent(Intent(Intent.ACTION_GET_CONTENT))
            }
        assertWithMessage("Search Feature is always enabled when search flag is disabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testGetContentConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)

        val testUserSelectImagesForAppConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                callingPackage("com.example.test")
                callingPackageUid(1234)
                callingPackageLabel("test_app")
            }
        assertWithMessage("Search Feature is always enabled when search flag is disabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testUserSelectImagesForAppConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    /* Verify Search feature is enabled when Search flag enabled.*/
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchFeature_whenFlagEnabled_isEnabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage("Search Feature is not always enabled when search flag enabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)

        val testGetContentConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(Intent.ACTION_GET_CONTENT)
                intent(Intent(Intent.ACTION_GET_CONTENT))
            }
        assertWithMessage("Search Feature is not always enabled when search flag enabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testGetContentConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)
    }

    /* Verify Search feature is enabled when Search flag and Embedded picker is enabled.*/
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH, Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER)
    fun testSearchFeature_whenEmbeddedPickerEnabled_isEnabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage("Search Feature is not always enabled when search flag enabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)

        val testGetContentConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                action(Intent.ACTION_GET_CONTENT)
                intent(Intent(Intent.ACTION_GET_CONTENT))
            }
        assertWithMessage("Search Feature is not always enabled when search flag enabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testGetContentConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchFeature_inPermissionMode_isDisabled() {
        val testUserSelectImagesForAppConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                callingPackage("com.example.test")
                callingPackageUid(1234)
                callingPackageLabel("test_app")
            }
        assertWithMessage("Search Feature is always enabled in Permission mode")
            .that(
                SearchFeature.Registration.isEnabled(
                    testUserSelectImagesForAppConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH, Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER)
    fun testSearchFeature_whenEmbeddedPickerEnabledInPermissionMode_isDisabled() {
        val testUserSelectImagesForAppConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                callingPackage("com.example.test")
                callingPackageUid(1234)
                callingPackageLabel("test_app")
            }
        assertWithMessage("Search Feature in embedded picker is always enabled in Perission mode")
            .that(
                SearchFeature.Registration.isEnabled(
                    testUserSelectImagesForAppConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_whenFlagEnabled_isDisplayed() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule
                .onNode(
                    hasText(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_search_placeholder_text)
                    )
                )
                .assertIsDisplayed()
            composeTestRule.onNode(
                hasContentDescription(
                    resources.getString(R.string.photopicker_search_placeholder_text)
                )
            )
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_whenClicked_opensSearchViewWithBackAction() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
                .performClick()
            composeTestRule.waitForIdle()

            // Asserts search view page with its placeholder text displayed
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()

            // Perform click action on back button in search bar of search view page
            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option))
                )
                .assert(hasClickAction())
                .performClick()
            composeTestRule.waitForIdle()

            // Search bar with Search text placeholder is displayed
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_onBackAction_clearsQuery() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            // Input test query in search bar and verify it is displayed
            val testQuery = "testquery"
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .performTextInput(testQuery)

            composeTestRule.onNodeWithText(testQuery).assertIsDisplayed()

            // Perform click action on back button in search bar of search view page
            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option))
                )
                .performClick()
            composeTestRule.waitForIdle()

            // Make sure test query is cleared and Search text placeholder is displayed
            composeTestRule.onNodeWithText(testQuery).assertIsNotDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_mimetypeOnlyVideo_showsVideoPlaceHolderText() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayListOf("video/*", "video/mpeg"))
                }
            configurationManager.get().setIntent(testIntent)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_videos_placeholder_text)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_mimetypeOnlyImage_showsPhotosPlaceHolderText() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayListOf("image/*", "image/png"))
                }
            configurationManager.get().setIntent(testIntent)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_mimetypeImageAndVideo_showsPhotosPlaceHolderText() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayListOf("image/*", "video/*"))
                }
            configurationManager.get().setIntent(testIntent)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_mimeTypeAll_showsPhotosPlaceHolderText() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayListOf("*/*"))
                }
            configurationManager.get().setIntent(testIntent)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()
        }
}
