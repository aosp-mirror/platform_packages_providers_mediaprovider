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

package com.android.photopicker.core.events

import android.provider.MediaStore
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.theme.AccentColorHelper
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.extensions.getUserProfilesVisibleToPhotopicker
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Dispatches an event to log all details with which the photopicker launched */
fun dispatchReportPhotopickerApiInfoEvent(
    coroutineScope: CoroutineScope,
    lazyEvents: Lazy<Events>,
    photopickerConfiguration: PhotopickerConfiguration,
    pickerIntentAction: Telemetry.PickerIntentAction =
        Telemetry.PickerIntentAction.UNSET_PICKER_INTENT_ACTION,
) {
    val dispatcherToken = FeatureToken.CORE.token
    val sessionId = photopickerConfiguration.sessionId
    // We always launch the picker in collapsed state. We track the state change as UI event.
    val pickerSize = Telemetry.PickerSize.COLLAPSED
    val mediaFilters =
        photopickerConfiguration.mimeTypes
            .map { mimeType ->
                when {
                    mimeType.contains("image") && mimeType.contains("video") ->
                        Telemetry.MediaType.PHOTO_VIDEO
                    mimeType.startsWith("image/") -> Telemetry.MediaType.PHOTO
                    mimeType.startsWith("video/") -> Telemetry.MediaType.VIDEO
                    else -> Telemetry.MediaType.UNSET_MEDIA_TYPE
                }
            }
            .ifEmpty { listOf(Telemetry.MediaType.UNSET_MEDIA_TYPE) }
    val maxPickedItemsCount = photopickerConfiguration.selectionLimit
    val selectedTab =
        when (photopickerConfiguration.startDestination) {
            PhotopickerDestinations.PHOTO_GRID -> Telemetry.SelectedTab.PHOTOS
            PhotopickerDestinations.ALBUM_GRID -> Telemetry.SelectedTab.ALBUMS
            else -> Telemetry.SelectedTab.UNSET_SELECTED_TAB
        }
    val selectedAlbum = Telemetry.SelectedAlbum.UNSET_SELECTED_ALBUM
    val isOrderedSelectionSet = photopickerConfiguration.pickImagesInOrder
    // TODO(b/376822503): Creating a new instance of AccentColorHelper() to check color seems
    //  unnecessary. Fix later
    val isAccentColorSet =
        AccentColorHelper(inputColor = photopickerConfiguration.accentColor ?: -1)
            .isValidAccentColorSet()
    val isDefaultTabSet =
        photopickerConfiguration.startDestination != PhotopickerDestinations.DEFAULT
    // TODO(b/376822503): Update when search is added
    val isSearchEnabled = false
    for (mediaFilter in mediaFilters) {
        coroutineScope.launch {
            lazyEvents
                .get()
                .dispatch(
                    Event.ReportPhotopickerApiInfo(
                        dispatcherToken = dispatcherToken,
                        sessionId = sessionId,
                        pickerIntentAction = pickerIntentAction,
                        pickerSize = pickerSize,
                        mediaFilter = mediaFilter,
                        maxPickedItemsCount = maxPickedItemsCount,
                        selectedTab = selectedTab,
                        selectedAlbum = selectedAlbum,
                        isOrderedSelectionSet = isOrderedSelectionSet,
                        isAccentColorSet = isAccentColorSet,
                        isDefaultTabSet = isDefaultTabSet,
                        isSearchEnabled = isSearchEnabled,
                    )
                )
        }
    }
}

/** Dispatches an event to log all the final state details of the picker */
fun dispatchReportPhotopickerSessionInfoEvent(
    coroutineScope: CoroutineScope,
    lazyEvents: Lazy<Events>,
    photopickerConfiguration: PhotopickerConfiguration,
    lazyDataService: Lazy<DataService>,
    lazyUserMonitor: Lazy<UserMonitor>,
    lazyMediaSelection: Lazy<Selection<Media>>,
    pickerStatus: Telemetry.PickerStatus = Telemetry.PickerStatus.UNSET_PICKER_STATUS,
    pickerCloseMethod: Telemetry.PickerCloseMethod =
        Telemetry.PickerCloseMethod.UNSET_PICKER_CLOSE_METHOD,
) {
    val dispatcherToken = FeatureToken.CORE.token
    val sessionId = photopickerConfiguration.sessionId
    val packageUid = photopickerConfiguration.callingPackageUid ?: -1
    val pickerSelection =
        if (photopickerConfiguration.selectionLimit == 1) {
            Telemetry.PickerSelection.SINGLE
        } else {
            Telemetry.PickerSelection.MULTIPLE
        }
    val cloudProviderUid =
        lazyDataService
            .get()
            .availableProviders
            .value
            .firstOrNull { provider -> provider.mediaSource == MediaSource.REMOTE }
            ?.uid ?: -1
    val userProfile =
        when (lazyUserMonitor.get().userStatus.value.activeUserProfile.profileType) {
            UserProfile.ProfileType.PRIMARY -> Telemetry.UserProfile.PERSONAL
            UserProfile.ProfileType.MANAGED -> Telemetry.UserProfile.WORK
            else -> Telemetry.UserProfile.UNKNOWN
        }
    val pickedMediaItemsSet = lazyMediaSelection.get().flow.value
    val pickedItemsCount = pickedMediaItemsSet.size
    val pickedItemsSize = pickedMediaItemsSet.sumOf { it.sizeInBytes.toInt() }
    val pickerMode =
        when {
            photopickerConfiguration.action == MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP ->
                Telemetry.PickerMode.PERMISSION_MODE_PICKER
            photopickerConfiguration.runtimeEnv == PhotopickerRuntimeEnv.ACTIVITY ->
                Telemetry.PickerMode.REGULAR_PICKER
            photopickerConfiguration.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED ->
                Telemetry.PickerMode.EMBEDDED_PICKER
            else -> Telemetry.PickerMode.UNSET_PICKER_MODE
        }

    coroutineScope.launch {
        val profileSwitchButtonVisible =
            lazyUserMonitor.get().userStatus.getUserProfilesVisibleToPhotopicker().first().size > 1
        lazyEvents
            .get()
            .dispatch(
                Event.ReportPhotopickerSessionInfo(
                    dispatcherToken = dispatcherToken,
                    sessionId = sessionId,
                    packageUid = packageUid,
                    pickerSelection = pickerSelection,
                    cloudProviderUid = cloudProviderUid,
                    userProfile = userProfile,
                    pickerStatus = pickerStatus,
                    pickedItemsCount = pickedItemsCount,
                    pickedItemsSize = pickedItemsSize,
                    profileSwitchButtonVisible = profileSwitchButtonVisible,
                    pickerMode = pickerMode,
                    pickerCloseMethod = pickerCloseMethod,
                )
            )
    }
}

/** Dispatches an event to log media item status as selected / unselected */
fun dispatchReportPhotopickerMediaItemStatusEvent(
    coroutineScope: CoroutineScope,
    lazyEvents: Lazy<Events>,
    photopickerConfiguration: PhotopickerConfiguration,
    mediaItem: Media,
    mediaStatus: Telemetry.MediaStatus,
    pickerSize: Telemetry.PickerSize = Telemetry.PickerSize.UNSET_PICKER_SIZE,
) {
    val dispatcherToken = FeatureToken.CORE.token
    val sessionId = photopickerConfiguration.sessionId
    val selectionSource = mediaItem.selectionSource ?: Telemetry.MediaLocation.UNSET_MEDIA_LOCATION
    val itemPosition = mediaItem.index ?: -1
    val selectedAlbum = mediaItem.mediaItemAlbum
    val mimeType = mediaItem.mimeType
    // TODO(b/376822503): find live photo format
    val mediaType =
        if (mimeType.startsWith("image/")) {
            if (mimeType.contains("gif")) {
                Telemetry.MediaType.GIF
            } else {
                Telemetry.MediaType.PHOTO
            }
        } else if (mimeType.startsWith("video/")) {
            Telemetry.MediaType.VIDEO
        } else {
            Telemetry.MediaType.OTHER
        }
    val cloudOnly = mediaItem.mediaSource == MediaSource.REMOTE
    coroutineScope.launch {
        lazyEvents
            .get()
            .dispatch(
                Event.ReportPhotopickerMediaItemStatus(
                    dispatcherToken = dispatcherToken,
                    sessionId = sessionId,
                    mediaStatus = mediaStatus,
                    selectionSource = selectionSource,
                    itemPosition = itemPosition,
                    selectedAlbum = selectedAlbum,
                    mediaType = mediaType,
                    cloudOnly = cloudOnly,
                    pickerSize = pickerSize,
                )
            )
    }
}

/**
 * Dispatches an event to log the expansion state change event in picker. If [isExpanded], logs
 * [Telemetry.UiEvent.EXPAND_PICKER], else logs [Telemetry.UiEvent.COLLAPSE_PICKER].
 */
fun dispatchPhotopickerExpansionStateChangedEvent(
    coroutineScope: CoroutineScope,
    lazyEvents: Lazy<Events>,
    photopickerConfiguration: PhotopickerConfiguration,
    isExpanded: Boolean,
) {
    val dispatcherToken = FeatureToken.CORE.token
    val sessionId = photopickerConfiguration.sessionId
    val packageUid = photopickerConfiguration.callingPackageUid ?: -1
    val uiEvent =
        if (isExpanded) Telemetry.UiEvent.EXPAND_PICKER else Telemetry.UiEvent.COLLAPSE_PICKER
    coroutineScope.launch {
        lazyEvents
            .get()
            .dispatch(
                Event.LogPhotopickerUIEvent(
                    dispatcherToken = dispatcherToken,
                    sessionId = sessionId,
                    packageUid = packageUid,
                    uiEvent = uiEvent,
                )
            )
    }
}
