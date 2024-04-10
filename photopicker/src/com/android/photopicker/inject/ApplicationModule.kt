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

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import com.android.photopicker.core.network.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Injection Module that provides access to objects bound to the PhotopickerApplication.
 *
 * These can be injected by requesting the type with the [@ApplicationOwned] qualifier.
 *
 * The module outlives the individual activities (and survives configuration changes), and is NOT
 * bound to a single Photopicker session.
 *
 * Note: Jobs that are launched in the [CoroutineScope] provided by this module will continue to run
 * until the Application's process is stopped.
 */
@Module
@InstallIn(SingletonComponent::class)
class ApplicationModule {

    companion object {
        val TAG: String = "PhotopickerApplicationModule"
    }

    // Avoid initialization until it's actually needed.
    private lateinit var backgroundScope: CoroutineScope
    private lateinit var networkMonitor: NetworkMonitor

    @Provides
    @ApplicationOwned
    fun applicationBackgroundScope(@Background dispatcher: CoroutineDispatcher): CoroutineScope {
        if (::backgroundScope.isInitialized) {
            return backgroundScope
        } else {
            Log.d(TAG, "Initializing application background scope.")
            backgroundScope = CoroutineScope(SupervisorJob() + dispatcher)
            return backgroundScope
        }
    }

    @Provides
    @ApplicationOwned
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver {
        return context.getContentResolver()
    }

    /**
     * Provider for the [NetworkMonitor]. This is lazily initialized only when requested to save on
     * initialization costs of this module.
     */
    @Provides
    @ApplicationOwned
    fun provideNetworkMonitor(
        @ApplicationContext context: Context,
        @ApplicationOwned scope: CoroutineScope,
    ): NetworkMonitor {
        if (::networkMonitor.isInitialized) {
            return networkMonitor
        } else {
            Log.d(
                NetworkMonitor.TAG,
                "NetworkMonitor requested, but not yet initialized. Initializing NetworkMonitor."
            )
            networkMonitor = NetworkMonitor(context, scope)
            return networkMonitor
        }
    }
}
