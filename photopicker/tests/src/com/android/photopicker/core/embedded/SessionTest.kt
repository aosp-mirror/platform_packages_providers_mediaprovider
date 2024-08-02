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
package com.android.photopicker.core.embedded

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.EmbeddedPhotopickerFeatureInfo
import android.provider.EmbeddedPhotopickerSessionResponse
import android.provider.IEmbeddedPhotopickerClient
import android.test.mock.MockContentResolver
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.EmbeddedServiceComponent
import com.android.photopicker.core.EmbeddedServiceComponentBuilder
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.requireSystemService
import com.android.photopicker.inject.EmbeddedTestModule
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
import dagger.Module
import dagger.hilt.EntryPoints
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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.times
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class SessionTest : EmbeddedPhotopickerFeatureBaseTest() {
    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()

    /** Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : EmbeddedTestModule()

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for EmbeddedServiceModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    @Inject @Main lateinit var mainDispatcher: CoroutineDispatcher

    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = testScope.backgroundScope

    /**
     * Preview uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper
    @Mock lateinit var mockContentProvider: ContentProvider
    // Needed for UserMonitor
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Inject lateinit var mockContext: Context
    @Inject lateinit var embeddedServiceComponentBuilder: EmbeddedServiceComponentBuilder
    @Inject lateinit var selection: Selection<Media>
    @Inject override lateinit var configurationManager: ConfigurationManager

    val featureInfo = EmbeddedPhotopickerFeatureInfo.Builder().build()

    val client =
        // TODO(b/354929684): Replace AIDL implementation with wrapper class.
        object : IEmbeddedPhotopickerClient.Stub() {

            override fun onSessionOpened(response: EmbeddedPhotopickerSessionResponse) {}

            override fun onSessionError(errorMsg: String) {}

            override fun onItemSelected(uri: Uri) {}

            override fun onItemDeselected(uri: Uri) {}
        }

    // Session has a surfacePackage which outlives the test if not closed, so it always needs to be
    // closed at the end of each test to prevent any existing UI activity from leaking into the next
    // test. Hold a reference to it in the test class and try to call close in the @After block.
    private var session: Session? = null

    /**
     * This is the method test cases should use to acquire a Session. This ensures the [Session]
     * under test will get cleaned up after the test case is completed. If a Session is not created
     * through this hook, it needs to be closed manually.
     */
    private fun getSessionUnderTest(component: EmbeddedServiceComponent): Session {
        val (displayId, width, height) = assumeDisplaysAndGetDisplayDataForTestDevice()
        // Try to close the previous session before it gets dereferenced if one exists.
        session?.close()
        val newSession =
            Session(
                context = getTestableContext(),
                clientPackageName = getTestableContext().getPackageName(),
                clientUid = Process.myUid(),
                component = component,
                width = width,
                height = height,
                displayId = displayId,
                hostToken = Binder(),
                featureInfo = featureInfo,
                clientCallback = client,
            )
        session = newSession
        return newSession
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        hiltRule.inject()

        whenever(mockContext.getApplicationInfo()) { getTestableContext().getApplicationInfo() }

        val mockContentResolver = MockContentResolver(mockContext)
        provider = MockContentProviderWrapper(mockContentProvider)
        mockContentResolver.addProvider(MockContentProviderWrapper.AUTHORITY, provider)
        contentResolver = mockContentResolver

        // Return a resource png so that glide actually has something to load
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            getTestableContext().getResources().openRawResourceFd(R.drawable.android)
        }
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
    }

    @After()
    fun teardown() {
        // It is important to tearDown glide after every test to ensure it picks up the updated
        // mocks from Hilt and mocks aren't leaked between tests.
        session?.close()
        session = null
    }

    /**
     * Helper method to ensure that a display exists for the test and extracts accurate display
     * information so the test runs with real display data.
     */
    private fun assumeDisplaysAndGetDisplayDataForTestDevice(): Triple<Int, Int, Int> {

        val context = getTestableContext()
        val displayManager: DisplayManager = context.requireSystemService()
        val windowManager: WindowManager = context.requireSystemService()

        // Suppress this test on any devices that don't have a display.
        assumeTrue(displayManager.displays.size > 0)
        val display =
            checkNotNull(displayManager.displays.first()) {
                "The displayId provided to openSession did not result in a valid display."
            }
        val windowMetrics = windowManager.getCurrentWindowMetrics()
        val bounds = windowMetrics.getBounds()

        return Triple(display.getDisplayId(), bounds.width(), bounds.height())
    }

    @Test
    fun testCreateSessionStartsPhotopickerUi() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()

            val session = getSessionUnderTest(component)
            advanceTimeBy(100)

            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)
            val sessionLifecycle: EmbeddedLifecycle = entryPoint.lifecycle()

            // After creating the view the lifecycle should be advanced to RESUMED to ensure the
            // view is running.
            assertWithMessage("Expected state to be Resumed")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.RESUMED)

            // Now the view is in the test's compose tree, so do a simple check to make sure
            // the view actually initialized and the test can locate the photo grid / modify the
            // selection.
            composeTestRule.setContent {
                // Wrap the surfacePackage inside of an [AndroidView] to make the view accessible to
                // the test.
                AndroidView(
                    factory = {
                        SurfaceView(getTestableContext()).apply {
                            setChildSurfacePackage(session.surfacePackage)
                        }
                    }
                )
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val resources = getTestableContext().getResources()

            // This is the accessibility label for a Photo in the grid.
            val mediaItemString = resources.getString(R.string.photopicker_media_item)

            // Verify that data in PhotoGrid is displayed
            composeTestRule
                .onAllNodesWithContentDescription(mediaItemString)
                .onFirst()
                .assert(hasClickAction())
                .assertIsDisplayed()
                .performClick()

            // Wait for PhotoGridViewModel to modify Selection
            advanceTimeBy(100)

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)
        }

    @Test
    fun testCloseSessionDestroysLifecycle() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()

            val session = getSessionUnderTest(component)
            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)
            val sessionLifecycle: EmbeddedLifecycle = entryPoint.lifecycle()
            advanceTimeBy(100)

            assertWithMessage("Expected state to be RESUMED")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.RESUMED)

            session.close()
            advanceTimeBy(100)

            assertWithMessage("Expected state to be Destroyed")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.DESTROYED)
        }

    @Test
    fun testSessionInitSetsLifecycleToResumed() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()

            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)
            val sessionLifecycle: EmbeddedLifecycle = entryPoint.lifecycle()

            assertWithMessage("Expected state to be Initialized")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.INITIALIZED)

            getSessionUnderTest(component)
            advanceTimeBy(100)

            assertWithMessage("Expected state to be RESUMED")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.RESUMED)
        }

    @Test
    fun testSessionNotifyVisibilityChangedUpdatesLifecycleState() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()
            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)
            val sessionLifecycle: EmbeddedLifecycle = entryPoint.lifecycle()

            val session = getSessionUnderTest(component)
            advanceTimeBy(100)

            assertWithMessage("Expected initial state to be RESUMED")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.RESUMED)

            async { session.notifyVisibilityChanged(isVisible = false) }.await()
            advanceTimeBy(100)

            assertWithMessage("Expected state to be CREATED")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.CREATED)

            async { session.notifyVisibilityChanged(isVisible = true) }.await()
            advanceTimeBy(100)

            assertWithMessage("Expected final state to be RESUMED")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.RESUMED)
        }

    @Test
    fun testSessionSetsCallerInConfiguration() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()
            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)

            // Create a session with the component and let it initialize.
            getSessionUnderTest(component)
            advanceTimeBy(100)

            val configuration = entryPoint.configurationManager().get().configuration.value
            assertWithMessage("Expected configuration to contain caller's package name")
                .that(configuration.callingPackage)
                .isEqualTo(getTestableContext().getPackageName())
            assertWithMessage("Expected configuration to contain caller's uid")
                .that(configuration.callingPackageUid)
                .isEqualTo(Process.myUid())
            assertWithMessage("Expected configuration to contain caller's display label")
                .that(configuration.callingPackageLabel)
                .isNotNull()
        }

    @Test
    fun testSessionSetsEmbeddedPhotopickerFeatureInfoInConfiguration() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()
            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)

            // Create a session with the component and let it initialize.
            getSessionUnderTest(component)
            advanceTimeBy(100)

            val configuration = entryPoint.configurationManager().get().configuration.value
            assertWithMessage(
                    "Expected configuration to contain the featureInfo max selection limit"
                )
                .that(configuration.selectionLimit)
                .isEqualTo(featureInfo.maxSelectionLimit)
            assertWithMessage("Expected configuration to contain the featureInfo mime types")
                .that(configuration.mimeTypes)
                .isEqualTo(featureInfo.mimeTypes)
            assertWithMessage(
                    "Expected configuration to contain the featureInfo ordered selection flag"
                )
                .that(configuration.pickImagesInOrder)
                .isEqualTo(featureInfo.isOrderedSelection)
            assertWithMessage("Expected configuration to contain the featureInfo pre-selected URIs")
                .that(configuration.preSelectedUris)
                .isEqualTo(featureInfo.preSelectedUris)
        }
}
