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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.android.photopicker.core.PhotopickerAppWithBottomSheet
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.theme.PhotopickerTheme
import com.android.photopicker.data.model.Media
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * This is the main entrypoint into the Android Photopicker.
 *
 * This class is responsible for bootstrapping the launched activity, session related dependencies,
 * and providing the compose ui entrypoint in [[PhotopickerApp]] with everything it needs.
 */
@AndroidEntryPoint(ComponentActivity::class)
class MainActivity : Hilt_MainActivity() {

    @Inject @ActivityRetainedScoped lateinit var configurationManager: ConfigurationManager
    @Inject @ActivityRetainedScoped lateinit var selection: Selection<Media>
    // This needs to be injected lazily, to defer initialization until the action can be set
    // on the ConfigurationManager.
    @Inject @ActivityRetainedScoped lateinit var featureManager: Lazy<FeatureManager>

    // Events requires the feature manager, so initialize this lazily until the action is set.
    @Inject lateinit var events: Lazy<Events>

    companion object {
        val TAG: String = "Photopicker"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        // Set the action before allowing FeatureManager to be initialized, so that it receives
        // the correct config with this activity's action.
        getIntent()?.let {
            configurationManager.setIntent(it)
        }

        // Begin listening for events before starting the UI.
        listenForEvents()

        setContent {
            val photopickerConfiguration by
                configurationManager.configuration.collectAsStateWithLifecycle()

            // Provide values to the entire compose stack.
            CompositionLocalProvider(
                LocalFeatureManager provides featureManager.get(),
                LocalPhotopickerConfiguration provides photopickerConfiguration,
                LocalSelection provides selection,
                LocalEvents provides events.get(),
            ) {
                PhotopickerTheme { PhotopickerAppWithBottomSheet(onDismissRequest = ::finish) }
            }
        }
    }

    /** Setup an [Event] listener for the [MainActivity] to monitor the event bus. */
    private fun listenForEvents() {

        lifecycleScope.launch {
            events.get().flow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { event
                ->
                when (event) {
                    is Event.MediaSelectionConfirmed -> {
                        finish()
                    }
                }
            }
        }
    }
}
