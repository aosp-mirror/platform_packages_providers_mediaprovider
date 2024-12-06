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

import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS
import android.util.Log
import com.android.photopicker.core.Background
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.MediaSource
import com.android.providers.media.MediaProviderStatsLog
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Photopicker telemetry class which intercepts the incoming events dispatched by various components
 * and maps them to their respective logging proto. All the logging occurs in background scope.
 */
class PhotopickerEventLogger(val dataService: Lazy<DataService>) {

    private val TAG = "PhotopickerEventLogger"

    /** Maps album id to the corresponding selected album enum values */
    private val mapAlbumIdToSelectedAlbum =
        hashMapOf(
            ALBUM_ID_CAMERA to Telemetry.SelectedAlbum.CAMERA,
            ALBUM_ID_SCREENSHOTS to Telemetry.SelectedAlbum.SCREENSHOTS,
            ALBUM_ID_FAVORITES to Telemetry.SelectedAlbum.FAVOURITES,
            ALBUM_ID_VIDEOS to Telemetry.SelectedAlbum.VIDEOS,
            ALBUM_ID_DOWNLOADS to Telemetry.SelectedAlbum.DOWNLOADS,
        )

    /** Maps album id to the corresponding selected album enum values */
    private val mapAlbumIdToAlbumOpened =
        hashMapOf(
            ALBUM_ID_CAMERA to Telemetry.UiEvent.ALBUM_CAMERA_OPEN,
            ALBUM_ID_SCREENSHOTS to Telemetry.UiEvent.ALBUM_SCREENSHOTS_OPEN,
            ALBUM_ID_FAVORITES to Telemetry.UiEvent.ALBUM_FAVOURITES_OPEN,
            ALBUM_ID_VIDEOS to Telemetry.UiEvent.ALBUM_VIDEOS_OPEM,
            ALBUM_ID_DOWNLOADS to Telemetry.UiEvent.ALBUM_DOWNLOADS_OPEN,
        )

    fun start(
        scope: CoroutineScope,
        @Background backgroundDispatcher: CoroutineDispatcher,
        events: Events,
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
                            event.pickerCloseMethod.method,
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
                            /* is_search_enabled */ false,
                            event.isCloudSearchEnabled,
                            event.isLocalSearchEnabled,
                        )
                    }
                    is Event.LogPhotopickerUIEvent -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_UI_EVENT_LOGGED,
                            event.sessionId,
                            event.packageUid,
                            event.uiEvent.event,
                        )
                    }
                    is Event.LogPhotopickerAlbumOpenedUIEvent -> {
                        val album = event.albumOpened
                        val albumOpened =
                            mapAlbumIdToAlbumOpened.getOrDefault(
                                album.id,
                                when (getAlbumDataSource(album)) {
                                    MediaSource.REMOTE -> Telemetry.UiEvent.ALBUM_FROM_CLOUD_OPEN
                                    // TODO replace with LOCAL value once added
                                    MediaSource.LOCAL -> Telemetry.UiEvent.ALBUM_FROM_CLOUD_OPEN
                                },
                            )
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_UI_EVENT_LOGGED,
                            event.sessionId,
                            event.packageUid,
                            albumOpened.event,
                        )
                    }
                    is Event.ReportPhotopickerMediaItemStatus -> {
                        val mediaAlbum = event.selectedAlbum
                        val selectedAlbum: Telemetry.SelectedAlbum =
                            if (
                                event.selectionSource == Telemetry.MediaLocation.ALBUM &&
                                    mediaAlbum != null
                            ) {
                                mapAlbumIdToSelectedAlbum.getOrDefault(
                                    mediaAlbum.id,
                                    when (getAlbumDataSource(mediaAlbum)) {
                                        MediaSource.REMOTE ->
                                            Telemetry.SelectedAlbum.UNDEFINED_CLOUD
                                        MediaSource.LOCAL -> Telemetry.SelectedAlbum.UNDEFINED_LOCAL
                                    },
                                )
                            } else {
                                Telemetry.SelectedAlbum.UNSET_SELECTED_ALBUM
                            }

                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED,
                            event.sessionId,
                            event.mediaStatus.status,
                            event.selectionSource.location,
                            event.itemPosition,
                            selectedAlbum.album,
                            event.mediaType.type,
                            event.cloudOnly,
                            event.pickerSize.size,
                        )
                    }
                    is Event.LogPhotopickerPreviewInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_PREVIEW_INFO_LOGGED,
                            event.sessionId,
                            event.previewModeEntry.entry,
                            event.previewItemCount,
                            event.mediaType.type,
                            event.videoInteraction.interaction,
                        )
                    }
                    is Event.LogPhotopickerMenuInteraction -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_MENU_INTERACTION_LOGGED,
                            event.sessionId,
                            event.packageUid,
                            event.menuItem.item,
                        )
                    }
                    is Event.LogPhotopickerBannerInteraction -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_BANNER_INTERACTION_LOGGED,
                            event.sessionId,
                            event.bannerType.type,
                            event.userInteraction.interaction,
                        )
                    }
                    is Event.LogPhotopickerMediaLibraryInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_MEDIA_LIBRARY_INFO_LOGGED,
                            event.sessionId,
                            event.cloudProviderUid,
                            event.librarySize,
                            event.mediaCount,
                        )
                    }
                    is Event.LogPhotopickerPageInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_PAGE_INFO_LOGGED,
                            event.sessionId,
                            event.pageNumber,
                            event.itemsLoadedInPage,
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
                            event.incrementalDeletedMediaSyncEndTime,
                        )
                    }
                    is Event.ReportPhotopickerAlbumSyncInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_ALBUM_SYNC_INFO_REPORTED,
                            event.sessionId,
                            event.getAlbumsStartTime,
                            event.getAlbumsEndTime,
                            event.getAlbumMediaStartTime,
                            event.getAlbumMediaEndTime,
                        )
                    }
                    is Event.ReportPhotopickerSearchInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED,
                            event.sessionId,
                            event.searchMethod.method,
                            /* picked_items */ 0,
                            /* startTime */ 0,
                            /* endTime */ 0,
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
                            event.isResponseReceived,
                        )
                    }
                    is Event.ReportEmbeddedPhotopickerInfo -> {
                        MediaProviderStatsLog.write(
                            MediaProviderStatsLog.EMBEDDED_PHOTOPICKER_INFO_REPORTED,
                            event.sessionId,
                            event.isSurfacePackageCreationSuccessful,
                            event.surfacePackageDeliveryStartTime,
                            event.surfacePackageDeliveryEndTime,
                        )
                    }
                }
            }
        }
    }

    /**
     * Fetch the data source of the album by matching it against the authority of the provider so
     * that we do not have to depend on glide's internal implementation(by using
     * album.getDataSource()) to fetch the album's data source
     */
    private fun getAlbumDataSource(album: Group.Album): MediaSource {
        for (provider in dataService.get().availableProviders.value) {
            if (provider.authority == album.authority) {
                return provider.mediaSource
            }
        }
        Log.w(
            TAG,
            "Unable to find an authority match with any provider for album " +
                album.displayName +
                " with authority " +
                album.authority +
                " while fetching the album data source",
        )
        return MediaSource.LOCAL
    }
}
