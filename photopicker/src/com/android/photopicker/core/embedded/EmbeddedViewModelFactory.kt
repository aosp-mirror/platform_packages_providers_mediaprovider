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

import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.android.photopicker.core.Background
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.albumgrid.AlbumGridViewModel
import com.android.photopicker.features.cloudmedia.MediaPreloaderViewModel
import com.android.photopicker.features.photogrid.PhotoGridViewModel
import com.android.photopicker.features.preview.PreviewViewModel
import com.android.photopicker.features.profileselector.ProfileSelectorViewModel
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher

/**
 * A [ViewModelProvider.Factory] to construct view models for the Embedded Photopicker.
 *
 * The activity based Photopicker depends on an Activity implementation underneath of Hilt to create
 * / inject view model classes. Since embedded runs as a standalone remote-rendered [ComposeView]
 * this factory emulates the dependency injection of hilt for view models by calling the @Inject
 * constructors manually, and providing the dependencies from the Hilt injection container.
 *
 * Any new View models that need to be able to be used during an Embedded session will need to
 * provide a construction implementation in this factory, or code that attempts to obtain that view
 * model at run time will cause a crash.
 *
 * Additionally, this factory must receive references to the injectable Photopicker dependencies
 * from the [EmbeddedSessionModule] inside of the [EmbeddedServiceComponent]
 *
 * This has the side-effect of having to manually wire up some dependencies for view models in the
 * embedded picker, but allows the Compose based UI to be unaware of how view models get resolved.
 *
 * @property backgroundDispatcher
 * @property configurationManager
 * @property dataService
 * @property events
 * @property featureManager
 * @property selection
 * @property userMonitor
 */
class EmbeddedViewModelFactory(
    @Background val backgroundDispatcher: CoroutineDispatcher,
    val configurationManager: Lazy<ConfigurationManager>,
    val dataService: Lazy<DataService>,
    val events: Lazy<Events>,
    val featureManager: Lazy<FeatureManager>,
    val selection: Lazy<Selection<Media>>,
    val userMonitor: Lazy<UserMonitor>,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        with(modelClass) {
            return when {
                isAssignableFrom(AlbumGridViewModel::class.java) ->
                    @Suppress("UNCHECKED_CAST")
                    AlbumGridViewModel(null, selection.get(), dataService.get(), events.get()) as T
                isAssignableFrom(MediaPreloaderViewModel::class.java) ->
                    @Suppress("UNCHECKED_CAST")
                    MediaPreloaderViewModel(
                        null,
                        backgroundDispatcher,
                        selection.get(),
                        userMonitor.get()
                    )
                        as T
                isAssignableFrom(PhotoGridViewModel::class.java) ->
                    @Suppress("UNCHECKED_CAST")
                    PhotoGridViewModel(null, selection.get(), dataService.get(), events.get()) as T
                isAssignableFrom(PreviewViewModel::class.java) ->
                    @Suppress("UNCHECKED_CAST")
                    PreviewViewModel(null, selection.get(), userMonitor.get()) as T
                isAssignableFrom(ProfileSelectorViewModel::class.java) ->
                    @Suppress("UNCHECKED_CAST")
                    ProfileSelectorViewModel(null, selection.get(), userMonitor.get()) as T
                else ->
                    throw IllegalArgumentException(
                        "Unknown ViewModel class: ${modelClass.simpleName}"
                    )
            }
        }
    }
}
