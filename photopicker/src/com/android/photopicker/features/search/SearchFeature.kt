/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.features.search

import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.features.Priority
import com.android.photopicker.data.PrefetchDataService
import com.android.photopicker.features.search.model.GlobalSearchState
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking

/** Feature class for the Photopicker's search functionality. */
class SearchFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "SearchFeature"

        override fun getPrefetchRequest(
            config: PhotopickerConfiguration
        ): Map<PrefetchResultKey, suspend (PrefetchDataService) -> Any?>? {
            return if (
                config.flags.PICKER_SEARCH_ENABLED &&
                    config.action != MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP
            ) {
                mapOf(
                    PrefetchResultKey.SEARCH_STATE to
                        { prefetchDataService ->
                            prefetchDataService.getGlobalSearchState()
                        }
                )
            } else {
                null
            }
        }

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ): Boolean {
            // Search feature is not enabled in permission mode.
            if (config.action == MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP) return false

            if (!config.flags.PICKER_SEARCH_ENABLED) return false

            return runBlocking {
                val searchStatus: Any? =
                    deferredPrefetchResultsMap[PrefetchResultKey.SEARCH_STATE]?.await()
                when (searchStatus) {
                    is GlobalSearchState ->
                        searchStatus == GlobalSearchState.ENABLED ||
                            searchStatus == GlobalSearchState.ENABLED_IN_OTHER_PROFILES_ONLY
                    else -> false // prefetch may have timed out
                }
            }
        }

        override fun build(featureManager: FeatureManager) = SearchFeature()
    }

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.SEARCH_BAR, Priority.HIGH.priority))
    }

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        when (location) {
            Location.SEARCH_BAR -> Search(modifier, params)
            else -> {}
        }
    }

    override val token = FeatureToken.SEARCH.token

    override val eventsConsumed = setOf<RegisteredEventClass>()

    /** Events produced by the search feature */
    override val eventsProduced =
        setOf(
            Event.ShowSnackbarMessage::class.java,
            Event.LogPhotopickerUIEvent::class.java,
            Event.ReportPhotopickerSearchInfo::class.java,
            Event.LogPhotopickerPageInfo::class.java,
        )
}
