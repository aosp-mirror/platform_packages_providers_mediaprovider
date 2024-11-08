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

import com.android.photopicker.core.banners.BannerDeclaration
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.data.model.Group
import com.android.providers.media.MediaProviderStatsLog

/* Convenience alias for classes that implement [Event] */
typealias RegisteredEventClass = Class<out Event>

/**
 * The definition of Photopicker events that can be sent through the Event bus.
 *
 * Event definitions should indicate where they are intended to be dispatched from.
 *
 * In general, favor adding a new event over re-using or re-purposing an existing event to avoid
 * conflicts or unintended side effects.
 *
 * See [Events] for implementation details and guidance on how to use the event bus. Ensure that any
 * events added are properly registered with the [FeatureManager].
 */
interface Event {

    /**
     * All events must contain a dispatcherToken which signifies which feature dispatched this
     * event. If the feature is not registered in the claiming feature's [eventsProduced] registry
     * this will cause an error.
     */
    val dispatcherToken: String

    /**
     * For ending the activity and referring the intent to documents UI. This is when the user
     * selects to browse to documents UI, rather than being re-routed automatically based on a
     * unsupported mimetype.
     */
    data class BrowseToDocumentsUi(override val dispatcherToken: String) : Event

    /**
     * For showing a message to the user in a snackbar.
     *
     * @see [SnackbarFeature] for snackbar implementation details.
     */
    data class ShowSnackbarMessage(override val dispatcherToken: String, val message: String) :
        Event

    // Each of the following data classes is an Event representation of their corresponding
    // atom proto

    /** Logs details about the launched picker session */
    data class ReportPhotopickerSessionInfo(
        override val dispatcherToken: String,
        val sessionId: Int,
        val packageUid: Int,
        val pickerSelection: Telemetry.PickerSelection,
        val cloudProviderUid: Int,
        val userProfile: Telemetry.UserProfile,
        val pickerStatus: Telemetry.PickerStatus,
        val pickedItemsCount: Int,
        val pickedItemsSize: Int,
        val profileSwitchButtonVisible: Boolean,
        val pickerMode: Telemetry.PickerMode,
        val pickerCloseMethod: Telemetry.PickerCloseMethod,
    ) : Event

    /**
     * Logs details about how the picker was launched including information on the set picker
     * options
     */
    data class ReportPhotopickerApiInfo(
        override val dispatcherToken: String,
        val sessionId: Int,
        val pickerIntentAction: Telemetry.PickerIntentAction,
        val pickerSize: Telemetry.PickerSize,
        val mediaFilter: Telemetry.MediaType,
        val maxPickedItemsCount: Int,
        val selectedTab: Telemetry.SelectedTab,
        val selectedAlbum: Telemetry.SelectedAlbum,
        val isOrderedSelectionSet: Boolean,
        val isAccentColorSet: Boolean,
        val isDefaultTabSet: Boolean,
        val isCloudSearchEnabled: Boolean,
        val isLocalSearchEnabled: Boolean,
    ) : Event

    /**
     * A general atom capturing any and all user interactions with the picker with other atoms
     * focusing on more specific interactions detailing the same.
     */
    data class LogPhotopickerUIEvent(
        override val dispatcherToken: String,
        val sessionId: Int,
        val packageUid: Int,
        val uiEvent: Telemetry.UiEvent,
    ) : Event

    data class LogPhotopickerAlbumOpenedUIEvent(
        override val dispatcherToken: String,
        val sessionId: Int,
        val packageUid: Int,
        val albumOpened: Group.Album,
    ) : Event

    /** Details out the information of a picker media item */
    data class ReportPhotopickerMediaItemStatus(
        override val dispatcherToken: String,
        val sessionId: Int,
        val mediaStatus: Telemetry.MediaStatus,
        val selectionSource: Telemetry.MediaLocation,
        val itemPosition: Int,
        val selectedAlbum: Group.Album?,
        val mediaType: Telemetry.MediaType,
        val cloudOnly: Boolean,
        val pickerSize: Telemetry.PickerSize,
    ) : Event

    /** Captures details of the picker's preview mode */
    data class LogPhotopickerPreviewInfo(
        override val dispatcherToken: String,
        val sessionId: Int,
        val previewModeEntry: Telemetry.PreviewModeEntry,
        val previewItemCount: Int,
        val mediaType: Telemetry.MediaType,
        val videoInteraction: Telemetry.VideoPlayBackInteractions,
    ) : Event

    /** Logs the user's interaction with the photopicker menu */
    data class LogPhotopickerMenuInteraction(
        override val dispatcherToken: String,
        val sessionId: Int,
        val packageUid: Int,
        val menuItem: Telemetry.MenuItemSelected,
    ) : Event

    /** Logs the user's interaction with different picker banners */
    data class LogPhotopickerBannerInteraction(
        override val dispatcherToken: String,
        val sessionId: Int,
        val bannerType: Telemetry.BannerType,
        val userInteraction: Telemetry.UserBannerInteraction,
    ) : Event

    /** Logs details of the picker media library size */
    data class LogPhotopickerMediaLibraryInfo(
        override val dispatcherToken: String,
        val sessionId: Int,
        val cloudProviderUid: Int,
        val librarySize: Int,
        val mediaCount: Int,
    ) : Event

    /**
     * Captures the picker's paging details: can give an estimate of how far the user scrolled and
     * the items loaded in.
     */
    data class LogPhotopickerPageInfo(
        override val dispatcherToken: String,
        val sessionId: Int,
        val pageNumber: Int,
        val itemsLoadedInPage: Int,
    ) : Event

    /** Logs picker media sync information: both sync start/end and incremental syncs. */
    data class ReportPhotopickerMediaGridSyncInfo(
        override val dispatcherToken: String,
        val sessionId: Int,
        val mediaCollectionInfoStartTime: Int,
        val mediaCollectionInfoEndTime: Int,
        val mediaSyncStartTime: Int,
        val mediaSyncEndTime: Int,
        val incrementalMediaSyncStartTime: Int,
        val incrementalMediaSyncEndTime: Int,
        val incrementalDeletedMediaSyncStartTime: Int,
        val incrementalDeletedMediaSyncEndTime: Int,
    ) : Event

    /** Logs sync information for picker albums: both the album details and its content */
    data class ReportPhotopickerAlbumSyncInfo(
        override val dispatcherToken: String,
        val sessionId: Int,
        val getAlbumsStartTime: Int,
        val getAlbumsEndTime: Int,
        val getAlbumMediaStartTime: Int,
        val getAlbumMediaEndTime: Int,
    ) : Event

    /** Logs information about the picker's search functionality */
    data class ReportPhotopickerSearchInfo(
        override val dispatcherToken: String,
        val sessionId: Int,
        val searchMethod: Telemetry.SearchMethod,
        val startTime: Int,
        val endTime: Int,
    ) : Event

    /** Logs details about the requests made for extracting search data */
    data class ReportSearchDataExtractionDetails(
        override val dispatcherToken: String,
        val sessionId: Int,
        val unprocessedImagesCount: Int,
        val processingStartTime: Int,
        val processingEndTime: Int,
        val isProcessingSuccessful: Boolean,
        val isResponseReceived: Boolean,
    ) : Event

    /** Logs information about the embedded photopicker(implementation details) */
    data class ReportEmbeddedPhotopickerInfo(
        override val dispatcherToken: String,
        val sessionId: Int,
        val isSurfacePackageCreationSuccessful: Boolean,
        val surfacePackageDeliveryStartTime: Int,
        val surfacePackageDeliveryEndTime: Int,
    ) : Event
}

/**
 * Holds the abstractions classes for all the enum protos to be used in the [Event] classes defined
 * above.
 */
interface Telemetry {

    /*
      Number of items allowed to be picked
    */
    @Suppress("ktlint:standard:max-line-length")
    enum class PickerSelection(val selection: Int) {
        SINGLE(
            MediaProviderStatsLog
                .PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_PERMITTED_SELECTION__SINGLE
        ),
        MULTIPLE(
            MediaProviderStatsLog
                .PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_PERMITTED_SELECTION__MULTIPLE
        ),
        UNSET_PICKER_SELECTION(
            MediaProviderStatsLog
                .PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_PERMITTED_SELECTION__UNSET_PICKER_PERMITTED_SELECTION
        ),
    }

    /*
     The user profile the picker is currently opened in
    */
    enum class UserProfile(val profile: Int) {
        WORK(MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__USER_PROFILE__WORK),
        PERSONAL(MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__USER_PROFILE__PERSONAL),
        PRIVATE_SPACE(
            MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__USER_PROFILE__PRIVATE_SPACE
        ),
        UNKNOWN(MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__USER_PROFILE__UNKNOWN),
        UNSET_USER_PROFILE(
            MediaProviderStatsLog
                .PHOTOPICKER_SESSION_INFO_REPORTED__USER_PROFILE__UNSET_USER_PROFILE
        ),
    }

    /*
    Holds the picker state at the moment
    */
    enum class PickerStatus(val status: Int) {
        OPENED(MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_STATUS__OPENED),
        CANCELED(MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_STATUS__CANCELED),
        CONFIRMED(
            MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_STATUS__CONFIRMED
        ),
        UNSET_PICKER_STATUS(
            MediaProviderStatsLog
                .PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_STATUS__UNSET_PICKER_STATUS
        ),
    }

    /*
    Defines the kind of picker that was opened
    */

    enum class PickerMode(val mode: Int) {
        REGULAR_PICKER(
            MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_MODE__REGULAR_PICKER
        ),
        EMBEDDED_PICKER(
            MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_MODE__EMBEDDED_PICKER
        ),
        PERMISSION_MODE_PICKER(
            MediaProviderStatsLog
                .PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_MODE__PERMISSION_MODE_PICKER
        ),
        UNSET_PICKER_MODE(
            MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_MODE__UNSET_PICKER_MODE
        ),
    }

    /*
    Captures how the picker was closed
    */

    enum class PickerCloseMethod(val method: Int) {
        SWIPE_DOWN(
            MediaProviderStatsLog.PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_CLOSE_METHOD__SWIPE_DOWN
        ),
        CROSS_BUTTON(
            MediaProviderStatsLog
                .PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_CLOSE_METHOD__CROSS_BUTTON
        ),
        BACK_BUTTON(
            MediaProviderStatsLog
                .PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_CLOSE_METHOD__BACK_BUTTON
        ),
        SELECTION_CONFIRMED(
            MediaProviderStatsLog
                .PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_CLOSE_METHOD__PICKER_SELECTION_CONFIRMED
        ),
        UNSET_PICKER_CLOSE_METHOD(
            MediaProviderStatsLog
                .PHOTOPICKER_SESSION_INFO_REPORTED__PICKER_CLOSE_METHOD__UNSET_PICKER_CLOSE_METHOD
        ),
    }

    /*
    The size of the picker on the screen
    */
    enum class PickerSize(val size: Int) {
        COLLAPSED(MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SCREEN_SIZE__COLLAPSED),
        EXPANDED(MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SCREEN_SIZE__EXPANDED),
        UNSET_PICKER_SIZE(
            MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SCREEN_SIZE__UNSET_PICKER_SIZE
        ),
    }

    /*
    The intent action that launches the picker
    */
    enum class PickerIntentAction(val intentAction: Int) {
        ACTION_PICK_IMAGES(
            MediaProviderStatsLog
                .PHOTOPICKER_API_INFO_REPORTED__PICKER_INTENT_ACTION__ACTION_PICK_IMAGES
        ),
        ACTION_GET_CONTENT(
            MediaProviderStatsLog
                .PHOTOPICKER_API_INFO_REPORTED__PICKER_INTENT_ACTION__ACTION_GET_CONTENT
        ),
        ACTION_USER_SELECT(
            MediaProviderStatsLog
                .PHOTOPICKER_API_INFO_REPORTED__PICKER_INTENT_ACTION__ACTION_USER_SELECT
        ),
        UNSET_PICKER_INTENT_ACTION(
            MediaProviderStatsLog
                .PHOTOPICKER_API_INFO_REPORTED__PICKER_INTENT_ACTION__UNSET_PICKER_INTENT_ACTION
        ),
    }

    /*
    Different media item types in the picker
    */
    enum class MediaType(val type: Int) {
        PHOTO(MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_TYPE__PHOTO),
        VIDEO(MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_TYPE__VIDEO),
        PHOTO_VIDEO(
            MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_TYPE__PHOTO_VIDEO
        ),
        GIF(MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_TYPE__GIF),
        LIVE_PHOTO(
            MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_TYPE__LIVE_PHOTO
        ),
        OTHER(MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_TYPE__OTHER),
        UNSET_MEDIA_TYPE(
            MediaProviderStatsLog
                .PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_TYPE__UNSET_MEDIA_TYPE
        ),
    }

    /*
    Different picker tabs
    */
    enum class SelectedTab(val tab: Int) {
        PHOTOS(MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_TAB__PHOTOS),
        ALBUMS(MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_TAB__ALBUMS),
        COLLECTIONS(MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_TAB__COLLECTIONS),
        UNSET_SELECTED_TAB(
            MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_TAB__UNSET_SELECTED_TAB
        ),
    }

    /*
    Different picker albums
    */
    enum class SelectedAlbum(val album: Int) {
        FAVOURITES(MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_ALBUM__FAVORITES),
        CAMERA(MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_ALBUM__CAMERA),
        DOWNLOADS(MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_ALBUM__DOWNLOADS),
        SCREENSHOTS(
            MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_ALBUM__SCREENSHOTS
        ),
        VIDEOS(MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_ALBUM__VIDEOS),
        UNDEFINED_LOCAL(
            MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_ALBUM__UNDEFINED_LOCAL
        ),
        UNDEFINED_CLOUD(
            MediaProviderStatsLog.PHOTOPICKER_API_INFO_REPORTED__SELECTED_ALBUM__UNDEFINED_CLOUD
        ),
        UNSET_SELECTED_ALBUM(
            MediaProviderStatsLog
                .PHOTOPICKER_API_INFO_REPORTED__SELECTED_ALBUM__UNSET_SELECTED_ALBUM
        ),
    }

    /*
    Holds multiple user interactions with the picker
    */
    enum class UiEvent(val event: Int) {
        PICKER_MENU_CLICKED(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_MENU_CLICK
        ),
        ENTER_PICKER_PREVIEW_MODE(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__ENTER_PICKER_PREVIEW_MODE
        ),
        SWITCH_PICKER_TAB(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__SWITCH_PICKER_TAB
        ),
        SWITCH_USER_PROFILE(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__SWITCH_USER_PROFILE
        ),
        PICKER_MAIN_GRID_INTERACTION(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_MAIN_GRID_INTERACTION
        ),
        PICKER_ALBUMS_INTERACTION(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_ALBUMS_INTERACTION
        ),
        PICKER_CLICK_ADD_BUTTON(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_CLICK_ADD_BUTTON
        ),
        PICKER_CLICK_VIEW_SELECTED(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_CLICK_VIEW_SELECTED
        ),
        PICKER_LONG_SELECT_MEDIA_ITEM(
            MediaProviderStatsLog
                .PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_LONG_SELECT_MEDIA_ITEM
        ),
        EXPAND_PICKER(MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__EXPAND_PICKER),
        COLLAPSE_PICKER(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__COLLAPSE_PICKER
        ),
        PROFILE_SWITCH_BUTTON_CLICK(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PROFILE_SWITCH_BUTTON_CLICK
        ),
        ACTION_BAR_HOME_BUTTON_CLICK(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__ACTION_BAR_HOME_BUTTON_CLICK
        ),
        PICKER_BACK_GESTURE_CLICK(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_BACK_GESTURE_CLICK
        ),
        PICKER_MENU_CLICK(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_MENU_CLICK
        ),
        MAIN_GRID_OPEN(MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__MAIN_GRID_OPEN),
        ALBUM_FAVOURITES_OPEN(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__ALBUM_FAVOURITES_OPEN
        ),
        ALBUM_CAMERA_OPEN(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__ALBUM_CAMERA_OPEN
        ),
        ALBUM_DOWNLOADS_OPEN(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__ALBUM_DOWNLOADS_OPEN
        ),
        ALBUM_SCREENSHOTS_OPEN(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__ALBUM_SCREENSHOTS_OPEN
        ),
        ALBUM_VIDEOS_OPEM(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__ALBUM_VIDEOS_OPEM
        ),
        ALBUM_FROM_CLOUD_OPEN(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__ALBUM_FROM_CLOUD_OPEN
        ),
        UI_LOADED_PHOTOS(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__UI_LOADED_PHOTOS
        ),
        UI_LOADED_ALBUMS(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__UI_LOADED_ALBUMS
        ),
        UI_LOADED_ALBUM_CONTENTS(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__UI_LOADED_ALBUM_CONTENTS
        ),
        CREATE_SURFACE_CONTROLLER_START(
            MediaProviderStatsLog
                .PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__CREATE_SURFACE_CONTROLLER_START
        ),
        CREATE_SURFACE_CONTROLLER_END(
            MediaProviderStatsLog
                .PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__CREATE_SURFACE_CONTROLLER_END
        ),
        PICKER_PRELOADING_START(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_PRELOADING_START
        ),
        PICKER_PRELOADING_FINISHED(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_PRELOADING_FINISHED
        ),
        PICKER_PRELOADING_FAILED(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_PRELOADING_FAILED
        ),
        PICKER_PRELOADING_CANCELLED(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_PRELOADING_CANCELLED
        ),
        PICKER_BROWSE_DOCUMENTS_UI(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__PICKER_BROWSE_DOCUMENTS_UI
        ),
        ENTER_PICKER_SEARCH(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__ENTER_PICKER_SEARCH
        ),
        SELECT_SEARCH_CATEGORY(
            MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__SELECT_SEARCH_CATEGORY
        ),
        UNSET_UI_EVENT(MediaProviderStatsLog.PHOTOPICKER_UIEVENT_LOGGED__UI_EVENT__UNSET_UI_EVENT),
    }

    /*
    Holds the selection status of the media items
    */
    enum class MediaStatus(val status: Int) {
        SELECTED(
            MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_STATUS__SELECTED
        ),
        UNSELECTED(
            MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_STATUS__UNSELECTED
        ),
        UNSET_MEDIA_STATUS(
            MediaProviderStatsLog
                .PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_STATUS__UNSET_MEDIA_STATUS
        ),
    }

    /*
    Holds the location of the media item
    */
    enum class MediaLocation(val location: Int) {
        MAIN_GRID(
            MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_LOCATION__MAIN_GRID
        ),
        ALBUM(MediaProviderStatsLog.PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_LOCATION__ALBUM),
        UNSET_MEDIA_LOCATION(
            MediaProviderStatsLog
                .PHOTOPICKER_MEDIA_ITEM_STATUS_REPORTED__MEDIA_LOCATION__UNSET_MEDIA_LOCATION
        ),
    }

    /*
    Defines how the user entered the preview mode
    */
    enum class PreviewModeEntry(val entry: Int) {
        VIEW_SELECTED(
            MediaProviderStatsLog.PHOTOPICKER_PREVIEW_INFO_LOGGED__PREVIEW_MODE_ENTRY__VIEW_SELECTED
        ),
        LONG_PRESS(
            MediaProviderStatsLog.PHOTOPICKER_PREVIEW_INFO_LOGGED__PREVIEW_MODE_ENTRY__LONG_PRESS
        ),
        UNSET_PREVIEW_MODE_ENTRY(
            MediaProviderStatsLog
                .PHOTOPICKER_PREVIEW_INFO_LOGGED__PREVIEW_MODE_ENTRY__UNSET_PREVIEW_MODE_ENTRY
        ),
    }

    /*
    Defines different video playback user interactions
    */
    @Suppress("ktlint:standard:max-line-length")
    enum class VideoPlayBackInteractions(val interaction: Int) {
        PLAY(MediaProviderStatsLog.PHOTOPICKER_PREVIEW_INFO_LOGGED__VIDEO_INTERACTIONS__PLAY),
        PAUSE(MediaProviderStatsLog.PHOTOPICKER_PREVIEW_INFO_LOGGED__VIDEO_INTERACTIONS__PAUSE),
        MUTE(MediaProviderStatsLog.PHOTOPICKER_PREVIEW_INFO_LOGGED__VIDEO_INTERACTIONS__MUTE),
        UNSET_VIDEO_PLAYBACK_INTERACTION(
            MediaProviderStatsLog
                .PHOTOPICKER_PREVIEW_INFO_LOGGED__VIDEO_INTERACTIONS__UNSET_VIDEO_PLAYBACK_INTERACTION
        ),
    }

    /*
    Picker menu item options
    */
    enum class MenuItemSelected(val item: Int) {
        BROWSE(
            MediaProviderStatsLog.PHOTOPICKER_MENU_INTERACTION_LOGGED__MENU_ITEM_SELECTED__BROWSE
        ),
        CLOUD_SETTINGS(
            MediaProviderStatsLog
                .PHOTOPICKER_MENU_INTERACTION_LOGGED__MENU_ITEM_SELECTED__CLOUD_SETTINGS
        ),
        UNSET_MENU_ITEM_SELECTED(
            MediaProviderStatsLog
                .PHOTOPICKER_MENU_INTERACTION_LOGGED__MENU_ITEM_SELECTED__UNSET_MENU_ITEM_SELECTED
        ),
    }

    /*
    Holds the different kind of banners displayed in the picker
    */
    enum class BannerType(val type: Int) {
        CLOUD_MEDIA_AVAILABLE(
            MediaProviderStatsLog
                .PHOTOPICKER_BANNER_INTERACTION_LOGGED__BANNER_TYPE__CLOUD_MEDIA_AVAILABLE
        ),
        ACCOUNT_UPDATED(
            MediaProviderStatsLog
                .PHOTOPICKER_BANNER_INTERACTION_LOGGED__BANNER_TYPE__ACCOUNT_UPDATED
        ),
        CHOOSE_ACCOUNT(
            MediaProviderStatsLog.PHOTOPICKER_BANNER_INTERACTION_LOGGED__BANNER_TYPE__CHOOSE_ACCOUNT
        ),
        CHOOSE_APP(
            MediaProviderStatsLog.PHOTOPICKER_BANNER_INTERACTION_LOGGED__BANNER_TYPE__CHOOSE_APP
        ),
        UNSET_BANNER_TYPE(
            MediaProviderStatsLog
                .PHOTOPICKER_BANNER_INTERACTION_LOGGED__BANNER_TYPE__UNSET_BANNER_TYPE
        );

        companion object {

            /**
             * Attempts to map a [BannerDeclaration] to the [BannerType] enum for logging banner
             * related data. At worst, will return [UNSET_BANNER_TYPE] for a banner without a
             * mapping.
             *
             * @param declaration The [BannerDeclaration] to convert to a [BannerType]
             * @return The corresponding [BannerType] or [UNSET_BANNER_TYPE] if a mapping isn't
             *   found.
             */
            fun fromBannerDeclaration(declaration: BannerDeclaration): BannerType {
                return when (declaration.id) {
                    BannerDefinitions.CLOUD_CHOOSE_ACCOUNT.id -> BannerType.CHOOSE_ACCOUNT
                    BannerDefinitions.CLOUD_CHOOSE_PROVIDER.id -> BannerType.CHOOSE_APP
                    BannerDefinitions.CLOUD_MEDIA_AVAILABLE.id -> BannerType.CLOUD_MEDIA_AVAILABLE
                    BannerDefinitions.CLOUD_UPDATED_ACCOUNT.id -> BannerType.ACCOUNT_UPDATED
                    // TODO(b/357010907): add a BannerType enum for the PRIVACY_EXPLAINER
                    BannerDefinitions.PRIVACY_EXPLAINER.id -> BannerType.UNSET_BANNER_TYPE
                    else -> BannerType.UNSET_BANNER_TYPE
                }
            }
        }
    }

    /*
    Different user interactions with the above defined banners
    */
    @Suppress("ktlint:standard:max-line-length")
    enum class UserBannerInteraction(val interaction: Int) {
        CLICK_BANNER_ACTION_BUTTON(
            MediaProviderStatsLog
                .PHOTOPICKER_BANNER_INTERACTION_LOGGED__USER_BANNER_INTERACTION__CLICK_BANNER_ACTION_BUTTON
        ),
        CLICK_BANNER_DISMISS_BUTTON(
            MediaProviderStatsLog
                .PHOTOPICKER_BANNER_INTERACTION_LOGGED__USER_BANNER_INTERACTION__CLICK_BANNER_DISMISS_BUTTON
        ),
        CLICK_BANNER(
            MediaProviderStatsLog
                .PHOTOPICKER_BANNER_INTERACTION_LOGGED__USER_BANNER_INTERACTION__CLICK_BANNER
        ),
        UNSET_BANNER_INTERACTION(
            MediaProviderStatsLog
                .PHOTOPICKER_BANNER_INTERACTION_LOGGED__BANNER_TYPE__UNSET_BANNER_TYPE
        ),
    }

    /*
    Different ways of searching in the picker
    */
    enum class SearchMethod(val method: Int) {
        SEARCH_QUERY(
            MediaProviderStatsLog.PHOTOPICKER_SEARCH_INFO_REPORTED__SEARCH_METHOD__SEARCH_QUERY
        ),
        SUGGESTED_SEARCHES(
            MediaProviderStatsLog
                .PHOTOPICKER_SEARCH_INFO_REPORTED__SEARCH_METHOD__SUGGESTED_SEARCHES
        ),
        UNSET_SEARCH_METHOD(
            MediaProviderStatsLog
                .PHOTOPICKER_SEARCH_INFO_REPORTED__SEARCH_METHOD__UNSET_SEARCH_METHOD
        ),
    }
}
