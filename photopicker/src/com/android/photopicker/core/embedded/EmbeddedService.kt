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

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.android.photopicker.core.EmbeddedServiceComponentBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint(Service::class)
class EmbeddedService : Hilt_EmbeddedService() {

    /** A builder to obtain an [EmbeddedServiceComponent] for a new [Session]. */
    @Inject lateinit var embeddedServiceComponentBuilder: EmbeddedServiceComponentBuilder

    companion object {
        val TAG: String = "PhotopickerEmbeddedService"
    }

    override fun onBind(intent: Intent?): IBinder? {
        throw NotImplementedError(
            "onBind is not yet implemented for Photopicker's embedded service."
        )
    }

    override fun onCreate() {
        super.onCreate()
        throw NotImplementedError(
            "onCreate is not yet implemented for Photopicker's embedded service."
        )
    }

    override fun onDestroy() {
        throw NotImplementedError(
            "onDestroy is not yet implemented for Photopicker's embedded service."
        )
    }
}
