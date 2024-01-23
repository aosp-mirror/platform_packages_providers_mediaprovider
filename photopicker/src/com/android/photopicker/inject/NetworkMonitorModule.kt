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

package com.android.photopicker.core

import android.content.Context
import android.util.Log
import com.android.photopicker.core.network.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
class NetworkMonitorModule {
    // Avoid initialization until it's actually needed.
    private lateinit var networkMonitor: NetworkMonitor

    /**
     * Provider for the [NetworkMonitor]. This is lazily initialized only when requested to save on
     * initialization costs of this module.
     */
    @Provides
    fun provideNetworkMonitor(
        @ApplicationContext context: Context,
    ): NetworkMonitor {
        if (::networkMonitor.isInitialized) {
            return networkMonitor
        } else {
            Log.d(
                NetworkMonitor.TAG,
                "NetworkMonitor requested, but not yet initialized. Initializing NetworkMonitor."
            )
            // Build a CoroutineScope that is off the Main thread for NetworkStatus updates.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            networkMonitor = NetworkMonitor(context, scope)
            return networkMonitor
        }
    }
}
