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

package com.android.photopicker

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.UserProperties
import android.net.Uri
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.launchActivityForResult
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.Background
import com.android.photopicker.core.Main
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureToken.CORE
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.utils.StubProvider
import com.android.photopicker.tests.utils.mockito.mockSystemService
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
import dagger.Module
import dagger.Lazy
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.MockitoAnnotations

/** This test class will run Photopicker's actual MainActivity. */
@UninstallModules(
    ActivityModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityTest {
    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)

    val testDispatcher = StandardTestDispatcher()
    /** Overrides for ActivityModule */
    @BindValue @Main val mainScope: TestScope = TestScope(testDispatcher)
    @BindValue @Background var testBackgroundScope: CoroutineScope = mainScope.backgroundScope

    /** Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    @Inject lateinit var configurationManager: ConfigurationManager
    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var events: Lazy<Events>
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    val contentResolver: ContentResolver = MockContentResolver()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        hiltRule.inject()
        // Stubs for UserMonitor
        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()
        whenever(mockUserManager.getUserBadge()) {
            resources.getDrawable(R.drawable.android, /* theme= */ null)
        }
        whenever(mockUserManager.getProfileLabel()) { "label" }
        whenever(mockUserManager.getUserProperties(any(UserHandle::class.java))) {
            UserProperties.Builder().build()
        }
        whenever(mockContext.contentResolver) { contentResolver }
        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.packageName) { "com.android.photopicker" }
        // Recursively return the same mockContext for all user packages to keep the stubing simple.
        whenever(mockContext.createContextAsUser(any(UserHandle::class.java), anyInt())) {
            mockContext
        }
        whenever(
            mockContext.createPackageContextAsUser(
                anyString(),
                anyInt(),
                any(UserHandle::class.java)
            )
        ) {
            mockContext
        }
    }

    @Test
    fun testMainActivitySetsActivityAction() {
        mainScope.runTest {
            val intent =
                Intent()
                    .setAction(MediaStore.ACTION_PICK_IMAGES)
                    .setComponent(
                        ComponentName(
                            InstrumentationRegistry.getInstrumentation().targetContext,
                            MainActivity::class.java
                        )
                    )
            with(ActivityScenario.launch<MainActivity>(intent)) {
                advanceTimeBy(100)
                assertWithMessage("Expected configuration to contain an action")
                    .that(configurationManager.configuration.first().action)
                    .isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            }
        }
    }

    /**
     * Using [StubProvider] as a backing provider, ensure that [MainActivity] returns data to the
     * calling app when the selection is confirmed by the user.
     */
    @Test
    fun testMainActivityReturnsPickerUrisSingleSelectionActionPickImages() {

        val testImage = StubProvider.getTestMediaFromStubProvider(1).first()

        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .setComponent(
                    ComponentName(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        MainActivity::class.java
                    )
                )

        with(launchActivityForResult<MainActivity>(intent)) {
            mainScope.runTest {
                onActivity {
                    mainScope.launch {
                        selection.add(testImage)
                        events.get().dispatch(Event.MediaSelectionConfirmed(CORE.token))
                    }
                }

                advanceTimeBy(100)
            }

            val result = this.result
            assertWithMessage("Expected scenario result to be OK")
                .that(result?.resultCode)
                .isEqualTo(RESULT_OK)
            val data = result?.resultData

            assertWithMessage("Expected activity to return a uri")
                .that(data?.getData())
                .isEqualTo(testImage.mediaUri)
        }
    }
    /**
     * Using [StubProvider] as a backing provider, ensure that [MainActivity] returns data to the
     * calling app when the selection is confirmed by the user.
     */
    @Test
    fun testMainActivityReturnsPickerUrisSingleSelectionActionGetContent() {

        val testImage = StubProvider.getTestMediaFromStubProvider(1).first()

        val intent =
            Intent()
                .setAction(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .setComponent(
                    ComponentName(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        MainActivity::class.java
                    )
                )

        with(launchActivityForResult<MainActivity>(intent)) {
            mainScope.runTest {
                onActivity {
                    mainScope.launch {
                        selection.add(testImage)
                        events.get().dispatch(Event.MediaSelectionConfirmed(CORE.token))
                    }
                }

                advanceTimeBy(100)
            }

            val result = this.result
            assertWithMessage("Expected scenario result to be OK")
                .that(result?.resultCode)
                .isEqualTo(RESULT_OK)
            val data = result?.resultData

            assertWithMessage("Expected activity to return a uri")
                .that(data?.getData())
                .isEqualTo(testImage.mediaUri)
        }
    }

    /**
     * Using [StubProvider] as a backing provider, ensure that [MainActivity] returns data to the
     * calling app when the selection is confirmed by the user.
     */
    @Test
    fun testMainActivityReturnsPickerUrisMultiSelection() {

        val selectedItems = StubProvider.getTestMediaFromStubProvider(20)

        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                .setComponent(
                    ComponentName(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        MainActivity::class.java
                    )
                )

        with(launchActivityForResult<MainActivity>(intent)) {
            mainScope.runTest {
                onActivity {
                    mainScope.launch {
                        selection.addAll(selectedItems)
                        events.get().dispatch(Event.MediaSelectionConfirmed(CORE.token))
                    }
                }

                advanceTimeBy(100)
            }

            val result = this.result
            assertWithMessage("Expected scenario result to be OK")
                .that(result?.resultCode)
                .isEqualTo(RESULT_OK)

            val data = result?.resultData
            val results = data?.getClipDataUris()

            assertWithMessage("Expected activity to return correct number of URIs")
                .that(data?.clipData?.itemCount)
                .isEqualTo(selectedItems.size)

            assertWithMessage("Expected returned URIs to match selection")
                .that(results)
                .isEqualTo(selectedItems.map { it.mediaUri })
        }
    }

    @Test
    fun testMainActivityGetContentWithPickImagesMaxCancelsActivity() {

        val intent =
            Intent()
                .setAction(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                .setComponent(
                    ComponentName(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        MainActivity::class.java
                    )
                )

        with(launchActivityForResult<MainActivity>(intent)) {
            val result = this.result
            assertWithMessage("Expected scenario result to be CANCELED")
                .that(result?.resultCode)
                .isEqualTo(RESULT_CANCELED)
        }
    }
}

/** Helper function to extract the list of Uris from a [ClipData] object found in an intent. */
private fun Intent.getClipDataUris(): List<Uri> {
    // Use a LinkedHashSet to maintain any ordering that may be
    // present in the ClipData
    val resultSet = LinkedHashSet<Uri>()
    data?.let { data -> resultSet.add(data) }
    val clipData = clipData
    if (clipData == null && resultSet.isEmpty()) {
        return emptyList()
    } else if (clipData != null) {
        for (i in 0 until clipData.itemCount) {
            val uri = clipData.getItemAt(i).uri
            if (uri != null) {
                resultSet.add(uri)
            }
        }
    }
    return ArrayList(resultSet)
}
