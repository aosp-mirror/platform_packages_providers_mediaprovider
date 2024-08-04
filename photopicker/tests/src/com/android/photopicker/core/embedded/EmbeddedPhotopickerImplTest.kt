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

import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.provider.EmbeddedPhotopickerFeatureInfo
import android.provider.EmbeddedPhotopickerSessionResponse
import android.provider.IEmbeddedPhotopickerClient
import android.view.SurfaceControlViewHost
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.extensions.requireSystemService
import com.android.photopicker.tests.utils.mockito.whenever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class EmbeddedPhotopickerImplTest {

    // TODO(b/354929684): Replace AIDL implementation with wrapper class.
    @Mock lateinit var mockClient: IEmbeddedPhotopickerClient.Stub
    @Mock lateinit var mockSession: Session

    companion object {
        const val TEST_PACKAGE_NAME = "test"
        const val TEST_UID = 12345
        const val TEST_DISPLAY_ID = 0
        const val TEST_WIDTH = 250
        const val TEST_HEIGHT = 250
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testOpenSessionSendsResponseToClient() {

        // [SurfaceControlViewHost] requires it's constructor to be run on the UI thread,
        // and since this is an InstrumentedTest, the main thread is available. To keep
        // things simple this test just forces the entire test to execute in a coroutine
        // that blocks the test thread and executes the test code on the UI thread.
        runBlocking(Dispatchers.Main.immediate) {
            val context = InstrumentationRegistry.getInstrumentation().getContext()
            val displayManager: DisplayManager = context.requireSystemService()

            // Suppress this test on any devices that don't have a display.
            assumeTrue(displayManager.displays.size > 0)

            val display =
                checkNotNull(displayManager.displays.first()) {
                    "The displayId provided to openSession did not result in a valid display."
                }

            val host = SurfaceControlViewHost(context, display, Binder())
            whenever(mockSession.surfacePackage) { host.surfacePackage }

            val clientProxy =
                // TODO(b/354929684): Replace AIDL implementation with wrapper class.
                object : IEmbeddedPhotopickerClient.Stub() {
                    override fun onSessionOpened(response: EmbeddedPhotopickerSessionResponse) {
                        mockClient.onSessionOpened(response)
                    }

                    override fun onSessionError(errorMsg: String) {
                        mockClient.onSessionError(errorMsg)
                    }

                    override fun onItemSelected(uri: Uri) {
                        mockClient.onItemSelected(uri)
                    }

                    override fun onItemDeselected(uri: Uri) {
                        mockClient.onItemDeselected(uri)
                    }
                }

            val embeddedPhotopickerImpl =
                EmbeddedPhotopickerImpl(
                    // Ignore all the session factory arguments since this just returns the
                    // mockSession.
                    { _, _, _, _, _, _, _, _ -> mockSession },
                )

            embeddedPhotopickerImpl.openSession(
                TEST_PACKAGE_NAME,
                TEST_UID,
                /* hostToken*/ Binder(),
                TEST_DISPLAY_ID,
                TEST_WIDTH,
                TEST_HEIGHT,
                EmbeddedPhotopickerFeatureInfo.Builder().build(),
                clientProxy
            )

            verify(mockClient, times(1))
                .onSessionOpened(any(EmbeddedPhotopickerSessionResponse::class.java))
        }
    }
}
