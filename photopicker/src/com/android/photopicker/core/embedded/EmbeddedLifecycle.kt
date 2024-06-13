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

import android.os.Bundle
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * A custom [LifecycleOwner], [ViewModelStoreOwner] and [SavedStateRegistryOwner] for use with the
 * embedded runtime of Photopicker.
 *
 * This class is only used for Embedded Photopicker, it is not invoked during the regular,
 * activity-based photopicker experience.
 *
 * The embedded photopicker is not run inside of the activity framework, however the compose UI
 * requires certain activity provided conventions to run correctly, and for embedded photopicker
 * sessions, this class provides all of that functionality to the embedded runtime.
 *
 * @property viewModelFactory A [ViewModelProvider.Factory] implementation that can build all of the
 *   Photopicker [ViewModel] that are required during an embedded photopicker session.
 * @see [EmbeddedViewModelFactory] for implementation of how view models are created during the
 *   embedded runtime.
 */
class EmbeddedLifecycle(viewModelFactory: ViewModelProvider.Factory) :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    HasDefaultViewModelProviderFactory {

    companion object {
        val TAG: String = "EmbeddedViewLifecycle"
    }

    private val stateBundle: Bundle = Bundle()

    private val lifecycleRegistry =
        LifecycleRegistry.createUnsafe(/* provider= */ this).apply {
            currentState = Lifecycle.State.INITIALIZED
        }

    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry =
        savedStateRegistryController.savedStateRegistry

    /**
     * This [ViewModelStore] holds all of the Photopicker view models for an individual embedded
     * photopicker session.
     */
    override val viewModelStore: ViewModelStore = ViewModelStore()

    /**
     * Returns the default [ViewModelProvider.Factory] that should be used when no custom `Factory`
     * is provided to the [ViewModelProvider] constructors.
     */
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory = viewModelFactory

    /**
     * Returns the default [CreationExtras] that should be passed into
     * [ViewModelProvider.Factory.create] when no overriding [CreationExtras] were passed to the
     * [ViewModelProvider] constructors.
     */
    override val defaultViewModelCreationExtras: CreationExtras
        get() = CreationExtras.Empty

    fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(stateBundle)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        savedStateRegistryController.performSave(stateBundle)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
