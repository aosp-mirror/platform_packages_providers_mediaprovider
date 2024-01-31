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
import com.android.photopicker.core.PhotopickerApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * This is the main entrypoint into the Android Photopicker.
 *
 * This class is responsible for bootstrapping the launched activity, session related dependencies,
 * and providing the compose ui entrypoint in [[PhotopickerApp]] with everything it needs.
 */
@AndroidEntryPoint(ComponentActivity::class)
class MainActivity : Hilt_MainActivity() {
    companion object {
        val TAG: String = "Photopicker"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent { PhotopickerApp() }
    }
}
