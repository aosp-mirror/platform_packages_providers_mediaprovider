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

package com.android.photopicker.features.profileselector

import android.content.Context
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.android.photopicker.R
import com.android.photopicker.core.banners.Banner
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerState
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
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.data.DataService
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking

/** Feature class for the Photopicker's Profile Selector button. */
class ProfileSelectorFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerProfileSelectorFeature"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ): Boolean {

            // Profile switching is not permitted in permission mode.
            if (MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.equals(config.action)) {
                return false
            }

            return true
        }

        override fun build(featureManager: FeatureManager) = ProfileSelectorFeature()
    }

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.PROFILE_SELECTOR, Priority.HIGH.priority))
    }

    override val token = FeatureToken.PROFILE_SELECTOR.token

    override val ownedBanners: Set<BannerDefinitions> = setOf(BannerDefinitions.SWITCH_PROFILE)

    override suspend fun getBannerPriority(
        banner: BannerDefinitions,
        bannerState: BannerState?,
        config: PhotopickerConfiguration,
        dataService: DataService,
        userMonitor: UserMonitor,
    ): Int {

        if (bannerState?.dismissed == true) {
            return Priority.DISABLED.priority
        }

        return when (userMonitor.launchingProfile.profileType) {
            UserProfile.ProfileType.PRIMARY -> Priority.DISABLED.priority
            else -> Priority.HIGH.priority
        }
    }

    override suspend fun buildBanner(
        banner: BannerDefinitions,
        dataService: DataService,
        userMonitor: UserMonitor,
    ): Banner {

        val currentProfile = userMonitor.userStatus.value.activeUserProfile
        val targetProfile: UserProfile =
            userMonitor.userStatus.value.allProfiles.find {
                it.profileType == UserProfile.ProfileType.PRIMARY
            } ?: userMonitor.userStatus.value.activeUserProfile

        if (currentProfile.identifier == targetProfile.identifier) {
            throw IllegalStateException(
                "Could not build switch profile banner, current and target profiles were the same."
            )
        }

        return when (banner) {
            BannerDefinitions.SWITCH_PROFILE -> {

                object : Banner {

                    override val declaration = BannerDefinitions.SWITCH_PROFILE

                    @Composable override fun buildTitle(): String = ""

                    @Composable
                    override fun buildMessage(): String {
                        return stringResource(
                            R.string.photopicker_profile_switch_banner_message,
                            currentProfile.label ?: getLabelForProfile(currentProfile),
                            targetProfile.label ?: getLabelForProfile(targetProfile),
                        )
                    }

                    @Composable
                    override fun getIcon(): ImageVector? {
                        return getIconForProfile(currentProfile)
                    }

                    @Composable
                    override fun actionLabel(): String? {
                        return stringResource(
                            R.string.photopicker_profile_banner_switch_button_label
                        )
                    }

                    override fun onAction(context: Context) {
                        val personalProfile: UserProfile? =
                            userMonitor.userStatus.value.allProfiles.find {
                                it.profileType == UserProfile.ProfileType.PRIMARY
                            }

                        personalProfile?.let {
                            runBlocking { userMonitor.requestSwitchActiveUserProfile(it, context) }
                        }
                    }
                }
            }
            else ->
                throw IllegalArgumentException("$TAG cannot build the requested banner: $banner")
        }
    }

    /** Events consumed by the ProfileSelector */
    override val eventsConsumed = setOf<RegisteredEventClass>()

    /** Events produced by the ProfileSelector */
    override val eventsProduced =
        setOf<RegisteredEventClass>(Event.LogPhotopickerUIEvent::class.java)

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        when (location) {
            Location.PROFILE_SELECTOR -> ProfileSelector(modifier)
            else -> {}
        }
    }
}
