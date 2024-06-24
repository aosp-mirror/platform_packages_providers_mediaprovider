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

package com.android.photopicker.core.events

import com.android.photopicker.core.Background
import com.android.providers.media.MediaProviderStatsLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Photopicker telemetry class which intercepts the incoming events dispatched by various components
 * and maps them to their respective logging proto. All the logging occurs in background scope.
 */
class PhotopickerEventLogger {
    fun start(
        scope: CoroutineScope,
        @Background backgroundDispatcher: CoroutineDispatcher,
        events: Events
    ) {
        scope.launch(backgroundDispatcher) {
            events.flow.collect { event ->
                when (event) {
                    is Event.ReportPhotopickerSessionInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED,
                            event.sessionId,
                            event.packageUid,
                            event.pickerSelection.selection,
                            event.cloudProviderUid,
                            event.userProfile.profile,
                            event.pickerStatus.status,
                            event.pickedItemsCount,
                            event.pickedItemsSize,
                            event.profileSwitchButtonVisible,
                            event.pickerMode.mode,
                            event.pickerCloseMethod.method
                        )
                    }
                    is Event.ReportPhotopickerApiInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED,
                            event.sessionId,
                            event.pickerIntentAction.intentAction,
                            event.pickerSize.size,
                            event.mediaFilter.type,
                            event.maxPickedItemsCount,
                            event.selectedTab.tab,
                            event.selectedAlbum.album,
                            event.isOrderedSelectionSet,
                            event.isAccentColorSet,
                            event.isDefaultTabSet,
                            event.isSearchEnabled
                        )
                    }
                    is Event.LogPhotopickerUIEvent -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.UI_EVENT_REPORTED,
                            event.sessionId,
                            event.packageUid,
                            event.uiEvent.event
                        )
                    }
                    is Event.ReportPhotopickerMediaItemStatus -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED,
                            event.sessionId,
                            event.mediaStatus.status,
                            event.mediaLocation.location,
                            event.itemPosition,
                            event.selectedAlbum.album,
                            event.mediaType.type,
                            event.cloudOnly,
                            event.pickerSize.size
                        )
                    }
                    is Event.LogPhotopickerPreviewInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_PREVIEW_INFO_LOGGED,
                            event.sessionId,
                            event.previewModeEntry.entry,
                            event.previewItemCount,
                            event.mediaType.type,
                            event.videoInteraction.interaction
                        )
                    }
                    is Event.LogPhotopickerMenuInteraction -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_MENU_INTERACTION_LOGGED,
                            event.sessionId,
                            event.packageUid,
                            event.menuItem.item
                        )
                    }
                    is Event.LogPhotopickerBannerInteraction -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_BANNER_INTERACTION_LOGGED,
                            event.sessionId,
                            event.bannerType.type,
                            event.userInteraction.interaction
                        )
                    }
                    is Event.LogPhotopickerMediaLibraryInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_MEDIA_LIBRARY_INFO_LOGGED,
                            event.sessionId,
                            event.cloudProviderUid,
                            event.librarySize,
                            event.mediaCount
                        )
                    }
                    is Event.LogPhotopickerPageInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_PAGE_INFO_LOGGED,
                            event.sessionId,
                            event.pageNumber,
                            event.itemsLoadedInPage
                        )
                    }
                    is Event.ReportPhotopickerMediaGridSyncInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_MEDIA_GRID_SYNC_INFO_REPORTED,
                            event.sessionId,
                            event.mediaCollectionInfoStartTime,
                            event.mediaCollectionInfoEndTime,
                            event.mediaSyncStartTime,
                            event.mediaSyncEndTime,
                            event.incrementalMediaSyncStartTime,
                            event.incrementalMediaSyncEndTime,
                            event.incrementalDeletedMediaSyncStartTime,
                            event.incrementalDeletedMediaSyncEndTime
                        )
                    }
                    is Event.ReportPhotopickerAlbumSyncInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_ALBUM_SYNC_INFO_REPORTED,
                            event.sessionId,
                            event.getAlbumsStartTime,
                            event.getAlbumsEndTime,
                            event.getAlbumMediaStartTime,
                            event.getAlbumMediaEndTime
                        )
                    }
                    is Event.ReportPhotopickerSearchInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED,
                            event.sessionId,
                            event.searchMethod.method,
                            event.pickedItems,
                            event.startTime,
                            event.endTime
                        )
                    }
                    is Event.ReportSearchDataExtractionDetails -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.SEARCH_DATA_EXTRACTION_DETAILS_REPORTED,
                            event.sessionId,
                            event.unprocessedImagesCount,
                            event.processingStartTime,
                            event.processingEndTime,
                            event.isProcessingSuccessful,
                            event.isResponseReceived
                        )
                    }
                    is Event.ReportEmbeddedPhotopickerInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.EMBEDDED_PHOTOPICKER_INFO_REPORTED,
                            event.sessionId,
                            event.isSurfacePackageCreationSuccessful,
                            event.surfacePackageDeliveryStartTime,
                            event.surfacePackageDeliveryEndTime
                        )
                    }
                }
            }
        }
    }
}
