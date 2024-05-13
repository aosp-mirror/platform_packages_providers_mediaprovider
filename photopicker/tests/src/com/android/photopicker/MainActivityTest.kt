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

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.UserProperties
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.Background
import com.android.photopicker.core.Main
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.utils.mockito.mockSystemService
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
import dagger.Module
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
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
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    val contentResolver: ContentResolver = MockContentResolver()
    var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setup() {
        scenario = null
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

    @After
    fun teardown() {
        scenario?.close()
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
            scenario = ActivityScenario.launch(intent)
            advanceTimeBy(100)
            assertWithMessage("Expected configuration to contain an action")
                .that(configurationManager.configuration.first().action)
                .isEqualTo(MediaStore.ACTION_PICK_IMAGES)
        }
    }
}
