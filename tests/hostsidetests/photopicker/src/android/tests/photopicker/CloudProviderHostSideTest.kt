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

package android.tests.photopicker

import com.android.tradefed.device.DeviceNotAvailableException
import com.android.tradefed.device.ITestDevice
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import com.android.tradefed.testtype.IDeviceTest
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test class for performing host side testing for interactions with an app that hosts a cloud
 * provider.
 *
 *
 * This is enabled with a test app referred here in this class as TEST_PACKAGE. This app is a
 * test-only apk that is installed by the test setup before this class is invoked. The apk has a
 * test cloud provider implementation.
 */
@RunWith(DeviceJUnit4ClassRunner::class)
class CloudProviderHostSideTest : IDeviceTest {
    private lateinit var mDevice: ITestDevice

    companion object {
        /** The package name of the test APK.  */
        private const val TEST_PACKAGE = "com.android.photopicker.testcloudmediaproviderapp"

        /** The shell command to enable the TEST_PACKAGE  */
        private const val COMMAND_ENABLE_TEST_APK = "pm enable --user 0 $TEST_PACKAGE"

        /** The authority for the cloud provider hosted by the test APK  */
        private const val TEST_CLOUD_PROVIDER_AUTHORITY =
            "com.android.photopicker.testcloudmediaproviderapp.test_cloud_provider"

        /** The shell command to get the current cloud provider  */
        private const val COMMAND_GET_CLOUD_PROVIDER =
            " content call --uri content://media --method get_cloud_provider"
    }

    override fun setDevice(device: ITestDevice) {
        mDevice = device
    }

    override fun getDevice(): ITestDevice {
        return mDevice
    }

    @Before
    @Throws(DeviceNotAvailableException::class)
    fun setUp() {
        // ensure the test APK is enabled before each test by setting it explicitly.
        mDevice.executeShellCommand(COMMAND_ENABLE_TEST_APK)
    }

    /**
     * Tests MediaProvider functionality to reset cloud provider to null when the current cloud
     * provider package is disabled. This is a use-case hit when the user changes app state through
     * settings.
     * It is replicated here using shell, but shell cannot mark a package as disabled unless it is a
     * test-only package. Hence the APK used here for testing is a test-only APK.
     * For this test
     */
    @Test
    @Throws(Exception::class)
    fun test_disableCloudProviderPackage_cloudProviderResets() {
        // disable device config syncs to ensure the applied configs are not disturbed during the
        // test.
        mDevice.executeShellCommand("device_config set_sync_disabled_for_tests until_reboot")

        // Add the test package authority to the allowlist for cloud providers.
        mDevice.executeShellCommand(
                "device_config put mediaprovider allowed_cloud_providers "
                        + "\"$TEST_CLOUD_PROVIDER_AUTHORITY\""
        )

        // Set the test cloud provider as the current provider.
        val setCloudProvider =
            " content call --uri content://media --method set_cloud_provider" +
                    " --extra cloud_provider:s:$TEST_CLOUD_PROVIDER_AUTHORITY"
        mDevice.executeShellCommand(setCloudProvider)

        // Verify that the provider is set
        val result: String = mDevice.executeShellCommand(COMMAND_GET_CLOUD_PROVIDER)
        assertWithMessage("Unexpected cloud provider, expected : $TEST_CLOUD_PROVIDER_AUTHORITY")
            .that(result.contains("{get_cloud_provider_result=$TEST_CLOUD_PROVIDER_AUTHORITY}"))
            .isTrue()

        // Disable test package.
        val stateChangeResult: String = mDevice.executeShellCommand(
            String.format(
                " pm disable --user %d %s",
                mDevice.getCurrentUser(),
                TEST_PACKAGE
            )
        )
        assertWithMessage("Expected the test package to be disabled.")
            .that(stateChangeResult.trim { it <= ' ' }.endsWith("new state: disabled"))
            .isTrue()

        // verify that the cloud provider has been reset and now no provider is set.
        var isCloudProviderReset = false
        // Polling for the cloud provider to reset
        val startTime = System.currentTimeMillis()
        val timeout: Long = 2000 // 2 seconds
        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                val resultForGetCloudProvider: String = mDevice.executeShellCommand(
                    COMMAND_GET_CLOUD_PROVIDER
                )
                assertWithMessage("Unexpected cloud provider, expected : null")
                    .that(resultForGetCloudProvider
                        .contains("{get_cloud_provider_result=null}"))
                    .isTrue()
                isCloudProviderReset = true
                break // Condition met, exit the loop
            } catch (e: AssertionError) {
                Thread.sleep(100) // Wait for a short time before retrying
            }
        }
        assertWithMessage("Unexpected cloud provider result, expected the cloud provider to reset.")
            .that(isCloudProviderReset)
            .isTrue()
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        mDevice.executeShellCommand(COMMAND_ENABLE_TEST_APK)
    }
}