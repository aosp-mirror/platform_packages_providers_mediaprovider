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

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.PackageManager.DONT_KILL_APP
import android.util.Log
import com.android.photopicker.core.configuration.DeviceConfigProxy
import com.android.photopicker.core.configuration.DeviceConfigProxyImpl
import com.android.photopicker.core.configuration.NAMESPACE_MEDIAPROVIDER

/**
 * BroadcastReceiver that receives a wake up signal from MediaProvider whenever the
 * [NAMESPACE_MEDIAPROVIDER] DeviceConfig is updated.
 */
class PhotopickerDeviceConfigReceiver : BroadcastReceiver() {

    // Leave as var so that tests can replace this value
    var deviceConfig: DeviceConfigProxy = DeviceConfigProxyImpl()

    companion object {
        const val TAG = "PhotopickerDeviceConfigReceiver"
        /** A list of activities that need to be enabled or disabled based on flag state. */
        val activities = listOf("MainActivity", "PhotopickerGetContentActivity",
        "PhotopickerUserSelectActivity")
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: will evaluate Photopicker components with new DeviceConfig.")

        // Update Photopicker's various activities based on the current device config.
        updateActivityState(context)
    }

    /** Update the Activity state of Photopicker based on the current DeviceConfig. */
    private fun updateActivityState(context: Context) {

        // Photopicker's activities are based on the enable_modern_picker flag
        val modernPhotopickerIsEnabled =
            deviceConfig.getFlag(
                namespace = NAMESPACE_MEDIAPROVIDER,
                key = "enable_modern_picker",
                defaultValue = false
            )

        val packageName = MainActivity::class.java.getPackage()?.getName()
        checkNotNull(packageName) { "Package name is required to update activity state." }

        for (activity in activities) {
            Log.d(TAG, "Setting $modernPhotopickerIsEnabled state for $packageName.$activity")
            val name = ComponentName(context, packageName + "." + activity)
            setComponentState(context, modernPhotopickerIsEnabled, name)
        }
    }

    /**
     * Set a Photopicker component's enabled setting.
     *
     * @param context The running context
     * @param enabled Whether the component should be enabled
     * @param componentName The name of the component to change
     */
    private fun setComponentState(
        context: Context,
        enabled: Boolean,
        componentName: ComponentName
    ) {

        val state =
            when (enabled) {
                true -> COMPONENT_ENABLED_STATE_ENABLED
                false -> COMPONENT_ENABLED_STATE_DISABLED
            }

        context.packageManager.setComponentEnabledSetting(componentName, state, DONT_KILL_APP)
    }
}
