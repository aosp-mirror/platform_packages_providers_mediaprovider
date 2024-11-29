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

package src.com.android.photopicker.features.test.prefetchfeature

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.data.PrefetchDataService
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking

class PrefetchFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        // Use any valid key
        private val prefetchResultKey: PrefetchResultKey = PrefetchResultKey.SEARCH_STATE

        override val TAG: String = "PrefetchFeature"

        override fun getPrefetchRequest(
            config: PhotopickerConfiguration
        ): Map<PrefetchResultKey, suspend (PrefetchDataService) -> Any?>? =
            mapOf(prefetchResultKey to { true })

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ): Boolean {
            return runBlocking {
                val featureStatus: Any? = deferredPrefetchResultsMap[prefetchResultKey]?.await()
                when (featureStatus) {
                    null -> false
                    is Boolean -> featureStatus
                    else -> false
                }
            }
        }

        override fun build(featureManager: FeatureManager) = PrefetchFeature()
    }

    override fun registerLocations(): List<Pair<Location, Int>> = listOf()

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {}

    override val token: String = TAG

    override val eventsConsumed: Set<RegisteredEventClass> = emptySet()

    override val eventsProduced: Set<RegisteredEventClass> = emptySet()
}
