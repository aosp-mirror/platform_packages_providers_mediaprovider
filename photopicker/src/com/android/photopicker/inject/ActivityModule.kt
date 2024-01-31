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

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.android.photopicker.core.network.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.qualifiers.ActivityContext
import kotlinx.coroutines.CoroutineScope

/**
 * Injection Module that provides access to objects bound to the [Activity]'s [Lifecycle].
 *
 * These can be injected by requesting the type with the [@ActivityOwned] qualifier.
 *
 * The module obtains a reference to the activity by installing in ActivityComponent (thus binding
 * the scope of this module to an individual Activity instance).
 *
 * Note: Jobs that are launched in the [CoroutineScope] provided by this module will be
 * automatically cancelled when the Activity's lifecycle is ended.
 */
@Module
@InstallIn(ActivityComponent::class)
class ActivityModule {

    // Avoid initialization until it's actually needed.
    private lateinit var networkMonitor: NetworkMonitor

    @Provides
    @ActivityOwned
    fun lifecycle(activity: Activity): Lifecycle {
        check(activity is LifecycleOwner) { "activity must implement LifecycleOwner" }
        return activity.lifecycle
    }

    @Provides
    @ActivityOwned
    fun activityScope(activity: Activity): CoroutineScope {
        check(activity is LifecycleOwner) { "activity must implement LifecycleOwner" }
        return activity.lifecycleScope
    }

    /**
     * Provider for the [NetworkMonitor]. This is lazily initialized only when requested to save on
     * initialization costs of this module.
     */
    @Provides
    @ActivityOwned
    fun provideNetworkMonitor(
        @ActivityContext context: Context,
        @ActivityOwned scope: CoroutineScope,
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
