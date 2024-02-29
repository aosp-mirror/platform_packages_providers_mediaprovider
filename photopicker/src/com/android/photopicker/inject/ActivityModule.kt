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
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.DeviceConfigProxyImpl
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.user.UserMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Injection Module that provides access to objects bound to the ActivityRetainedScope.
 *
 * These can be injected by requesting the type with the [@ActivityRetainedScoped] qualifier.
 *
 * The module outlives the individual activities (and survives configuration changes), but is bound
 * to a single Photopicker session.
 *
 * Note: Jobs that are launched in the [CoroutineScope] provided by this module will be
 * automatically cancelled when the ActivityRetainedScope is ended.
 */
@Module
@InstallIn(ActivityRetainedComponent::class)
class ActivityModule {

    companion object {
        val TAG: String = "PhotopickerActivityModule"
    }

    // Avoid initialization until it's actually needed.
    private lateinit var backgroundScope: CoroutineScope
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var featureManager: FeatureManager
    private lateinit var mainScope: CoroutineScope
    private lateinit var userMonitor: UserMonitor

    /** Provider for a @Background Dispatcher [CoroutineScope]. */
    @Provides
    @ActivityRetainedScoped
    @Background
    fun provideBackgroundScope(
        @Background dispatcher: CoroutineDispatcher,
        activityRetainedLifecycle: ActivityRetainedLifecycle
    ): CoroutineScope {
        if (::backgroundScope.isInitialized) {
            return backgroundScope
        } else {
            Log.d(TAG, "Initializing background scope.")
            backgroundScope = CoroutineScope(SupervisorJob() + dispatcher)
            activityRetainedLifecycle.addOnClearedListener {
                Log.d(TAG, "Activity retained lifecycle is ending, cancelling background scope.")
                backgroundScope.cancel()
            }
            return backgroundScope
        }
    }

    /** Provider for the [ConfigurationManager]. */
    @Provides
    @ActivityRetainedScoped
    fun provideConfigurationManager(
        @ActivityRetainedScoped @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
    ): ConfigurationManager {
        if (::configurationManager.isInitialized) {
            return configurationManager
        } else {
            Log.d(
                ConfigurationManager.TAG,
                "ConfigurationManager requested but not yet initialized." +
                    " Initializing ConfigurationManager."
            )
            configurationManager = ConfigurationManager(scope, dispatcher, DeviceConfigProxyImpl())
            return configurationManager
        }
    }

    @Provides
    @ActivityRetainedScoped
    fun provideFeatureManager(
        @ActivityRetainedScoped @Background scope: CoroutineScope,
        @ActivityRetainedScoped configurationManager: ConfigurationManager,
    ): FeatureManager {

        if (::featureManager.isInitialized) {
            return featureManager
        } else {
            Log.d(
                FeatureManager.TAG,
                "FeatureManager requested but not yet initialized. Initializing FeatureManager."
            )
            featureManager =
                // Do not pass a set of FeatureRegistrations here to use the standard set of
                // enabled features.
                FeatureManager(
                    configurationManager.configuration,
                    scope,
                )
            return featureManager
        }
    }

    /** Provider for a @Main Dispatcher [CoroutineScope]. */
    @Provides
    @ActivityRetainedScoped
    @Main
    fun provideMainScope(
        @Main dispatcher: CoroutineDispatcher,
        activityRetainedLifecycle: ActivityRetainedLifecycle
    ): CoroutineScope {

        if (::mainScope.isInitialized) {
            return mainScope
        } else {
            Log.d(TAG, "Initializing main scope.")
            mainScope = CoroutineScope(SupervisorJob() + dispatcher)
            activityRetainedLifecycle.addOnClearedListener {
                Log.d(TAG, "Activity retained lifecycle is ending, cancelling main scope.")
                mainScope.cancel()
            }
            return mainScope
        }
    }

    /** Provides the UserHandle of the current process owner. */
    @Provides
    @ActivityRetainedScoped
    fun provideUserHandle(): UserHandle {
        return Process.myUserHandle()
    }

    /** Provider for the [UserMonitor]. This is lazily initialized only when requested. */
    @Provides
    @ActivityRetainedScoped
    fun provideUserMonitor(
        @ApplicationContext context: Context,
        @ActivityRetainedScoped @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        @ActivityRetainedScoped handle: UserHandle,
    ): UserMonitor {
        if (::userMonitor.isInitialized) {
            return userMonitor
        } else {
            Log.d(
                UserMonitor.TAG,
                "UserMonitor requested but not yet initialized. Initializing UserMonitor."
            )
            userMonitor = UserMonitor(context, scope, dispatcher, handle)
            return userMonitor
        }
    }
}
