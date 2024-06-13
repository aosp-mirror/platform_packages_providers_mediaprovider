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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedLifecycle

/**
 * A composable function that obtains the requested [ViewModel] based on the current
 * [PhotopickerRuntimeEnv]. This will re-use existing view models in the current [ViewModelStore]'s
 * context, or create a new view model if the correct type cannot be found.
 *
 * This should be used in place of all other view model constructors, both [hiltViewModel] and
 * [viewModel] as well as directly calling a ViewModel's public constructor. This ensures the
 * created view model is properly re-used, and it is cleared when the ViewModelStore is cleared.
 *
 * @param <VM> the type of the viewModel to obtain
 * @return the current instance of the view model, or a newly created view model if none existed in
 *   the [ViewModelStore]
 */
@Composable
inline fun <reified VM : ViewModel> obtainViewModel(): VM {
    val configuration = LocalPhotopickerConfiguration.current
    var viewModel: VM =
        when (configuration.runtimeEnv) {
            // When the current runtime is embedded, override the current ViewModelStore to use
            // the [LocalEmbeddedLifecycle]'s store, and rely on the [EmbeddedViewModelFactory]
            // to create and inject view models.
            PhotopickerRuntimeEnv.EMBEDDED -> {
                val embeddedLifecycle = LocalEmbeddedLifecycle.current
                checkNotNull(embeddedLifecycle) {
                    "Cannot obtain view models in embedded runtime when" +
                        " LocalEmbeddedLifecycle is not set."
                }
                var embeddedViewModel: VM? = null
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides embeddedLifecycle,
                    LocalLifecycleOwner provides embeddedLifecycle,
                    LocalSavedStateRegistryOwner provides embeddedLifecycle,
                ) {
                    embeddedViewModel =
                        viewModel(
                            embeddedLifecycle,
                            factory = embeddedLifecycle.defaultViewModelProviderFactory
                        )
                }
                // This should never actually be null, as the [EmbeddedViewModelFactory] will throw
                // an error rather than return a null value, but we need to de-null the type before
                // returning it to the calling composable.
                checkNotNull(embeddedViewModel) {
                    "Unable to obtain viewmodel from embedded factory: ${VM::class.simpleName}"
                }
            }
            // When the current run time is activity, rely on the standard hiltViewModel injection,
            // which scopes the view model to the navigation graph's current backstack entry.
            PhotopickerRuntimeEnv.ACTIVITY -> hiltViewModel()
        }
    return viewModel
}
