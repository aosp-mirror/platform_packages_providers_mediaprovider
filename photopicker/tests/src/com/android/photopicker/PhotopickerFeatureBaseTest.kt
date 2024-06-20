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

package com.android.photopicker.features

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.UserProperties
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.R
import com.android.photopicker.core.PhotopickerMain
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.testPhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.theme.PhotopickerTheme
import com.android.photopicker.data.model.Media
import com.android.photopicker.tests.utils.mockito.mockSystemService
import com.android.photopicker.tests.utils.mockito.whenever
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString

/**
 * A base test class that includes some common utilities for starting a UI test with the Photopicker
 * compose UI.
 */
abstract class PhotopickerFeatureBaseTest {

    lateinit var navController: TestNavHostController

    /** A default implementation for retrieving a real context object for use during tests. */
    protected fun getTestableContext(): Context {
        return InstrumentationRegistry.getInstrumentation().getContext()
    }

    /**
     * Generates a suitable [TestNavHostController] which can be provided to the compose stack and
     * allow tests to directly navigate.
     */
    protected fun createNavController(): TestNavHostController {
        navController = TestNavHostController(getTestableContext())
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.navigatorProvider.addNavigator(DialogNavigator())
        return navController
    }

    /** Generate a standard set of mocks that [UserMonitor] will need for test users. */
    protected fun setupTestForUserMonitor(
        mockContext: Context,
        mockUserManager: UserManager,
        contentResolver: ContentResolver,
        mockPackageManager: PackageManager
    ) {
        // Stub out UserManager with the mock
        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }

        val resources = getTestableContext().getResources()
        whenever(mockUserManager.getUserBadge()) {
            resources.getDrawable(R.drawable.android, /* theme= */ null)
        }
        whenever(mockUserManager.getProfileLabel())
            .thenReturn(
                resources.getString(R.string.photopicker_profile_primary_label),
                resources.getString(R.string.photopicker_profile_managed_label),
                resources.getString(R.string.photopicker_profile_unknown_label),
            )
        // Return default [UserProperties] for all [UserHandle]
        whenever(mockUserManager.getUserProperties(any(UserHandle::class.java))) {
            UserProperties.Builder().build()
        }

        // Stubs for UserMonitor to acquire contentResolver for each User.
        whenever(mockContext.contentResolver) { contentResolver }
        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.packageName) { "com.android.photopicker" }

        // Recursively return the same mockContext for all user packages to keep the stubbing
        // simple.
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

    /**
     * A helper method that calls into the [PhotopickerMain] composable in the UI stack and provides
     * the correct [CompositionLocalProvider]s required to bootstrap the UI.
     */
    @Composable
    protected fun callPhotopickerMain(
        featureManager: FeatureManager,
        selection: Selection<Media>,
        events: Events,
        photopickerConfiguration: PhotopickerConfiguration = testPhotopickerConfiguration,
        navController: TestNavHostController = createNavController(),
    ) {
        CompositionLocalProvider(
            LocalFeatureManager provides featureManager,
            LocalSelection provides selection,
            LocalPhotopickerConfiguration provides photopickerConfiguration,
            LocalNavController provides navController,
            LocalEvents provides events,
        ) {
            PhotopickerTheme(
                intent = photopickerConfiguration.intent
            ) { PhotopickerMain() }
        }
    }
}
