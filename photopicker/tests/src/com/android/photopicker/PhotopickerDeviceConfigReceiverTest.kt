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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.PackageManager.DONT_KILL_APP
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.core.configuration.NAMESPACE_MEDIAPROVIDER
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.util.test.nonNullableEq
import com.android.photopicker.util.test.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class PhotopickerDeviceConfigReceiverTest {

    // Isolate the test device by providing a test wrapper around device config so that the
    // tests can control the flag values that are returned.
    val testDeviceConfigProxy = TestDeviceConfigProxyImpl()
    val intent = Intent(Intent.ACTION_MAIN)

    @Mock lateinit var mockContext: Context
    @Mock lateinit var mockPackageManager: PackageManager

    private val realContext = InstrumentationRegistry.getInstrumentation().getContext()
    lateinit var receiver: PhotopickerDeviceConfigReceiver

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testDeviceConfigProxy.reset()

        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.getPackageName()) { realContext.getPackageName() }

        receiver = PhotopickerDeviceConfigReceiver()

        // Replace the receiver's deviceConfig with a test implementation this tests controls.
        receiver.deviceConfig = testDeviceConfigProxy
    }

    @Test
    fun testEnableModernPickerFlagDisabled() {
        testDeviceConfigProxy.setFlag(NAMESPACE_MEDIAPROVIDER, "enable_modern_picker", "false")

        receiver.onReceive(mockContext, intent)

        // Verify calls to disable all of the activities were made
        verify(mockPackageManager, times(PhotopickerDeviceConfigReceiver.activities.size))
            .setComponentEnabledSetting(
                any(ComponentName::class.java),
                nonNullableEq(COMPONENT_ENABLED_STATE_DISABLED),
                nonNullableEq(DONT_KILL_APP),
            )
    }

    @Test
    fun testEnableModernPickerFlagEnabled() {
        testDeviceConfigProxy.setFlag(NAMESPACE_MEDIAPROVIDER, "enable_modern_picker", "true")

        receiver.onReceive(mockContext, intent)

        // Verify calls to enable all of the activities were made
        verify(mockPackageManager, times(PhotopickerDeviceConfigReceiver.activities.size))
            .setComponentEnabledSetting(
                any(ComponentName::class.java),
                nonNullableEq(COMPONENT_ENABLED_STATE_ENABLED),
                nonNullableEq(DONT_KILL_APP),
            )
    }
}
