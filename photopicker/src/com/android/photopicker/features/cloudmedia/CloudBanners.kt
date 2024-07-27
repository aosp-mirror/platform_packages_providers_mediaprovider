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

package com.android.photopicker.features.cloudmedia

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.android.photopicker.R
import com.android.photopicker.core.banners.Banner
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Provider

/**
 * A UI banner that shows the user a message asking them to set their CloudMediaProvider app and
 * provides a secondary action that links to the [ACTION_PICK_IMAGES_SETTINGS] page.
 */
val cloudChooseProviderBanner =
    object : Banner {

        override val declaration = BannerDefinitions.CLOUD_CHOOSE_PROVIDER

        @Composable
        override fun buildTitle(): String {
            return stringResource(R.string.photopicker_banner_cloud_choose_provider_title)
        }

        @Composable
        override fun buildMessage(): String {
            return stringResource(R.string.photopicker_banner_cloud_choose_provider_message)
        }

        @Composable
        override fun getIcon(): ImageVector? {
            return Icons.Outlined.Cloud
        }

        @Composable
        override fun actionLabel(): String? {
            return stringResource(R.string.photopicker_banner_cloud_choose_app_button)
        }

        override fun onAction(context: Context) {
            context.startActivity(Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS))
        }
    }

/**
 * Builder for the [BannerDefinitions.CLOUD_CHOOSE_ACCOUNT] banner that shows a secondary action
 * that links to the active CloudMediaProvider's account configuration page.
 *
 * @param cloudProvider the [Provider] details of the active CloudMediaProvider.
 * @param collectionInfo the associated [CollectionInfo] of the active collection with the active
 *   provider.
 * @return The [Banner] to be displayed in the UI.
 */
fun buildCloudChooseAccountBanner(cloudProvider: Provider, collectionInfo: CollectionInfo): Banner {
    return object : Banner {

        override val declaration = BannerDefinitions.CLOUD_CHOOSE_ACCOUNT

        @Composable
        override fun buildTitle(): String {
            return stringResource(R.string.photopicker_banner_cloud_choose_account_title)
        }

        @Composable
        override fun buildMessage(): String {
            return stringResource(
                R.string.photopicker_banner_cloud_choose_account_message,
                "${cloudProvider.displayName}",
            )
        }

        @Composable
        override fun getIcon(): ImageVector? {
            return Icons.Outlined.Cloud
        }

        @Composable
        override fun actionLabel(): String? {
            return collectionInfo.accountConfigurationIntent?.let {
                stringResource(R.string.photopicker_banner_cloud_choose_account_button)
            }
        }

        override fun onAction(context: Context) {
            collectionInfo.accountConfigurationIntent?.let { context.startActivity(it) }
        }
    }
}

/**
 * Builder for a CloudMediaAvailable banner object that indicates to the user their backed up cloud
 * media is available to be selected in the Photopicker.
 *
 * @param cloudProvider the [Provider] details of the active CloudMediaProvider.
 * @param collectionInfo the associated [CollectionInfo] of the active collection with the active
 *   provider.
 * @return The [Banner] to be displayed in the UI.
 */
fun buildCloudMediaAvailableBanner(
    cloudProvider: Provider,
    collectionInfo: CollectionInfo
): Banner {
    return object : Banner {

        override val declaration = BannerDefinitions.CLOUD_MEDIA_AVAILABLE

        @Composable
        override fun buildTitle(): String {
            return stringResource(R.string.photopicker_banner_cloud_media_available_title)
        }

        @Composable
        override fun buildMessage(): String {
            return stringResource(
                R.string.photopicker_banner_cloud_media_available_message,
                "${cloudProvider.displayName}",
                collectionInfo.accountName ?: ""
            )
        }

        @Composable
        override fun getIcon(): ImageVector? {
            return Icons.Outlined.Cloud
        }
    }
}
