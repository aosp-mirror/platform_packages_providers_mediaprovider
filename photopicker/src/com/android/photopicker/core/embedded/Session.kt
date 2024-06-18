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

import android.content.Context
import androidx.compose.runtime.getValue
import com.android.photopicker.core.EmbeddedServiceComponent
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn

/**
 * Session object for a single session/instance of the Embedded Photopicker.
 *
 * This class manages a single session of the embedded photopicker and resolves all hilt
 * dependencies for the Photopicker views that run underneath it.
 *
 * @property context the [EmbeddedService] context object
 * @property component the [EmbeddedServiceComponent] which contains this session's individual hilt
 *   dependencies.
 * @see [EmbeddedServiceModule] for dependency implementations
 */
class Session(
    @Suppress("UNUSED_PARAMETER") context: Context,
    component: EmbeddedServiceComponent,
) {

    companion object {
        val TAG: String = "PhotopickerEmbeddedSession"
    }

    /**
     * Most dependencies are injected as [Dagger.Lazy] to avoid initializing classes in the wrong
     * order or before they are actually needed. This saves on initialization costs and ensures that
     * the sequence of initialization between [ConfigurationManager] -> [FeatureManager] flows in a
     * predictable manner.
     */
    @EntryPoint
    @InstallIn(EmbeddedServiceComponent::class)
    interface EmbeddedEntryPoint {

        fun lifecycle(): EmbeddedLifecycle

        fun featureManager(): Lazy<FeatureManager>

        fun configurationManager(): Lazy<ConfigurationManager>

        fun selection(): Lazy<Selection<Media>>

        fun userMonitor(): Lazy<UserMonitor>

        fun dataService(): Lazy<DataService>

        fun events(): Lazy<Events>
    }

    /** A set of Session specific dependencies that are only used by this session instance */
    val dependencies = EntryPoints.get(component, EmbeddedEntryPoint::class.java)

    init {
        // Mark the [EmbeddedLifecycle] associated with the session as created when this class is
        // instantiated.
        dependencies.lifecycle().onCreate()
    }

    fun close() {
        // Mark the [EmbeddedLifecycle] associated with the session as destroyed when this class is
        // closed.
        dependencies.lifecycle().onDestroy()
    }
}
