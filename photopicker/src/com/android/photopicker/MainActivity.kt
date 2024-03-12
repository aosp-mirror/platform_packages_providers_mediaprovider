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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.photopicker.core.PhotopickerApp
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject

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

    companion object {
        val TAG: String = "Photopicker"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the action before allowing FeatureManager to be initialized, so that it receives
        // the correct config with this activity's action.
        configurationManager.setAction(getIntent()?.getAction() ?: "")

        setContent {
            val photopickerConfiguration by
                configurationManager.configuration.collectAsStateWithLifecycle()

            // Provide the [FeatureManager] to the entire compose stack.
            CompositionLocalProvider(
                LocalFeatureManager provides featureManager.get(),
                LocalSelection provides selection,
            ) {
                PhotopickerApp(config = photopickerConfiguration)
            }
        }
    }
}
