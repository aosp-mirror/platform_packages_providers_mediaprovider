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

package com.android.photopicker.features.privacyexplainer

import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.android.photopicker.R
import com.android.photopicker.core.banners.Banner
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.Priority
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService

/** Feature class for the Photopicker's Privacy explainer. */
class PrivacyExplainerFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerPrivacyExplainerFeature"

        override fun isEnabled(config: PhotopickerConfiguration) = true

        override fun build(featureManager: FeatureManager) = PrivacyExplainerFeature()
    }

    override fun registerLocations(): List<Pair<Location, Int>> = emptyList()

    override val token = FeatureToken.PRIVACY_EXPLAINER.token

    override val ownedBanners: Set<BannerDefinitions> = setOf(BannerDefinitions.PRIVACY_EXPLAINER)

    override suspend fun getBannerPriority(
        banner: BannerDefinitions,
        bannerState: BannerState?,
        config: PhotopickerConfiguration,
        dataService: DataService,
        userMonitor: UserMonitor,
    ): Int {
        return when (banner) {
            BannerDefinitions.PRIVACY_EXPLAINER -> {
                if (bannerState?.dismissed == true) {
                    Priority.DISABLED.priority
                } else {
                    Priority.HIGH.priority
                }
            }
            else ->
                throw IllegalArgumentException("$TAG cannot build the requested banner: $banner")
        }
    }

    override suspend fun buildBanner(
        banner: BannerDefinitions,
        dataService: DataService,
        userMonitor: UserMonitor,
    ): Banner {
        return when (banner) {
            BannerDefinitions.PRIVACY_EXPLAINER ->
                object : Banner {
                    override val declaration = BannerDefinitions.PRIVACY_EXPLAINER

                    @Composable override fun buildTitle(): String = ""

                    @Composable
                    override fun buildMessage(): String {
                        val config = LocalPhotopickerConfiguration.current
                        val genericAppName =
                            stringResource(R.string.photopicker_privacy_explainer_generic_app_name)

                        return when (config.action) {
                            MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP ->
                                stringResource(
                                    R.string.photopicker_privacy_explainer_permission_mode,
                                    config.callingPackageLabel ?: genericAppName,
                                )
                            else ->
                                stringResource(
                                    R.string.photopicker_privacy_explainer,
                                    config.callingPackageLabel ?: genericAppName,
                                )
                        }
                    }

                    @Composable
                    override fun getIcon(): ImageVector? {
                        return ImageVector.vectorResource(R.drawable.android_security_privacy)
                    }
                }
            else ->
                throw IllegalArgumentException("$TAG cannot build the requested banner: $banner")
        }
    }

    override val eventsConsumed = setOf<RegisteredEventClass>()
    override val eventsProduced = setOf<RegisteredEventClass>()

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {}
}
