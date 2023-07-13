/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.providers.media.photopicker.metrics;

import androidx.annotation.NonNull;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.providers.media.metrics.MPUiEventLoggerImpl;

public class PhotoPickerUiEventLogger {

    enum PhotoPickerEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Photo picker opened in personal profile")
        PHOTO_PICKER_OPEN_PERSONAL_PROFILE(942),
        @UiEvent(doc = "Photo picker opened in work profile")
        PHOTO_PICKER_OPEN_WORK_PROFILE(943),
        @UiEvent(doc = "Photo picker opened via GET_CONTENT intent")
        PHOTO_PICKER_OPEN_GET_CONTENT(1080),
        @UiEvent(doc = "Photo picker opened in half screen")
        PHOTO_PICKER_OPEN_HALF_SCREEN(1166),
        @UiEvent(doc = "Photo picker opened in full screen")
        PHOTO_PICKER_OPEN_FULL_SCREEN(1167),
        @UiEvent(doc = "Photo picker opened in single select mode")
        PHOTO_PICKER_OPEN_SINGLE_SELECT(1168),
        @UiEvent(doc = "Photo picker opened in multi select mode")
        PHOTO_PICKER_OPEN_MULTI_SELECT(1169),
        @UiEvent(doc = "Photo picker opened with the filter to show all images")
        PHOTO_PICKER_FILTER_ALL_IMAGES(1170),
        @UiEvent(doc = "Photo picker opened with the filter to show all videos")
        PHOTO_PICKER_FILTER_ALL_VIDEOS(1171),
        @UiEvent(doc = "Photo picker opened with some other specific filter")
        PHOTO_PICKER_FILTER_OTHER(1172),
        @UiEvent(doc = "DocumentsUi opened by clicking on Browse in Photo picker")
        PHOTO_PICKER_BROWSE_DOCUMENTSUI(1085),
        @UiEvent(doc = "Photo picker cancelled in work profile")
        PHOTO_PICKER_CANCEL_WORK_PROFILE(1125),
        @UiEvent(doc = "Photo picker cancelled in personal profile")
        PHOTO_PICKER_CANCEL_PERSONAL_PROFILE(1126),
        @UiEvent(doc = "Confirmed selection in Photo picker in work profile")
        PHOTO_PICKER_CONFIRM_WORK_PROFILE(1127),
        @UiEvent(doc = "Confirmed selection in Photo picker in personal profile")
        PHOTO_PICKER_CONFIRM_PERSONAL_PROFILE(1128),
        @UiEvent(doc = "Photo picker opened with an active cloud provider")
        PHOTO_PICKER_CLOUD_PROVIDER_ACTIVE(1198),
        @UiEvent(doc = "User changed the active Photo picker cloud provider")
        PHOTO_PICKER_CLOUD_PROVIDER_CHANGED(1135),
        @UiEvent(doc = "Photo Picker uri is queried with an unknown column")
        PHOTO_PICKER_QUERY_UNKNOWN_COLUMN(1227),
        @UiEvent(doc = "Clicked the mute / unmute button in a photo picker video preview")
        PHOTO_PICKER_VIDEO_PREVIEW_AUDIO_BUTTON_CLICK(1413),
        @UiEvent(doc = "Clicked the 'view selected' button in photo picker")
        PHOTO_PICKER_PREVIEW_ALL_SELECTED(1414),
        @UiEvent(doc = "Photo picker opened with the 'switch profile' button visible and enabled")
        PHOTO_PICKER_PROFILE_SWITCH_BUTTON_ENABLED(1415),
        @UiEvent(doc = "Photo picker opened with the 'switch profile' button visible but disabled")
        PHOTO_PICKER_PROFILE_SWITCH_BUTTON_DISABLED(1416),
        @UiEvent(doc = "Clicked the 'switch profile' button in photo picker")
        PHOTO_PICKER_PROFILE_SWITCH_BUTTON_CLICK(1417),
        @UiEvent(doc = "Selected a media item in the main grid")
        PHOTO_PICKER_SELECTED_ITEM_MAIN_GRID(1433),
        @UiEvent(doc = "Selected a media item in an album")
        PHOTO_PICKER_SELECTED_ITEM_ALBUM(1434),
        @UiEvent(doc = "Selected a cloud only media item")
        PHOTO_PICKER_SELECTED_ITEM_CLOUD_ONLY(1435),
        @UiEvent(doc = "Previewed a media item in the main grid")
        PHOTO_PICKER_PREVIEW_ITEM_MAIN_GRID(1436);

        private final int mId;

        PhotoPickerEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    private UiEventLogger logger;

    public PhotoPickerUiEventLogger() {
        logger = new MPUiEventLoggerImpl();
    }

    public void logPickerOpenPersonal(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_OPEN_PERSONAL_PROFILE,
                callingUid,
                callingPackage,
                instanceId);
    }

    public void logPickerOpenWork(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_OPEN_WORK_PROFILE,
                callingUid,
                callingPackage,
                instanceId);
    }

    public void logPickerOpenViaGetContent(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_OPEN_GET_CONTENT,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that the picker has opened in half screen
     * @param instanceId     an identifier for the current picker session
     * @param callingUid     the uid of the app initiating the picker launch
     * @param callingPackage the package name of the app initiating the picker launch
     */
    public void logPickerOpenInHalfScreen(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_OPEN_HALF_SCREEN,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that the picker has opened in full screen
     * @param instanceId     an identifier for the current picker session
     * @param callingUid     the uid of the app initiating the picker launch
     * @param callingPackage the package name of the app initiating the picker launch
     */
    public void logPickerOpenInFullScreen(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_OPEN_FULL_SCREEN,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that the picker has opened in single select mode
     * @param instanceId     an identifier for the current picker session
     * @param callingUid     the uid of the app initiating the picker launch
     * @param callingPackage the package name of the app initiating the picker launch
     */
    public void logPickerOpenInSingleSelect(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_OPEN_SINGLE_SELECT,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that the picker has opened in multi select mode
     * @param instanceId     an identifier for the current picker session
     * @param callingUid     the uid of the app initiating the picker launch
     * @param callingPackage the package name of the app initiating the picker launch
     */
    public void logPickerOpenInMultiSelect(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_OPEN_MULTI_SELECT,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that the picker has opened with the filter to show all images
     * @param instanceId     an identifier for the current picker session
     * @param callingUid     the uid of the app initiating the picker launch
     * @param callingPackage the package name of the app initiating the picker launch
     */
    public void logPickerOpenWithFilterAllImages(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_FILTER_ALL_IMAGES,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that the picker has opened with the filter to show all videos
     * @param instanceId     an identifier for the current picker session
     * @param callingUid     the uid of the app initiating the picker launch
     * @param callingPackage the package name of the app initiating the picker launch
     */
    public void logPickerOpenWithFilterAllVideos(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_FILTER_ALL_VIDEOS,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that the picker has opened with a specific filter, other than the ones
     * tracked explicitly
     * @param instanceId     an identifier for the current picker session
     * @param callingUid     the uid of the app initiating the picker launch
     * @param callingPackage the package name of the app initiating the picker launch
     */
    public void logPickerOpenWithAnyOtherFilter(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_FILTER_OTHER,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that user has clicked on "Browse..." in Photo picker overflow menu.
     * This UI click even opens DocumentsUi.
     */
    public void logBrowseToDocumentsUi(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_BROWSE_DOCUMENTSUI,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that user has confirmed selection in personal profile
     */
    public void logPickerConfirmPersonal(InstanceId instanceId, int callingUid,
            String callingPackage, int countOfItemsConfirmed) {
        logger.logWithInstanceIdAndPosition(
                PhotoPickerEvent.PHOTO_PICKER_CONFIRM_PERSONAL_PROFILE,
                callingUid,
                callingPackage,
                instanceId,
                countOfItemsConfirmed);
    }

    /**
     * Log metrics to notify that user has confirmed selection in work profile
     */
    public void logPickerConfirmWork(InstanceId instanceId, int callingUid,
            String callingPackage, int countOfItemsConfirmed) {
        logger.logWithInstanceIdAndPosition(
                PhotoPickerEvent.PHOTO_PICKER_CONFIRM_WORK_PROFILE,
                callingUid,
                callingPackage,
                instanceId,
                countOfItemsConfirmed);
    }

    /**
     * Log metrics to notify that user has cancelled picker (without any selection) in personal
     * profile
     */
    public void logPickerCancelPersonal(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_CANCEL_PERSONAL_PROFILE,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that user has cancelled picker (without any selection) in work
     * profile
     */
    public void logPickerCancelWork(InstanceId instanceId, int callingUid,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_CANCEL_WORK_PROFILE,
                callingUid,
                callingPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that the picker has opened with an active cloud provider
     * @param instanceId           an identifier for the current picker session
     * @param cloudProviderUid     the uid of the cloud provider app
     * @param cloudProviderPackage the package name of the cloud provider app
     */
    public void logPickerOpenWithActiveCloudProvider(InstanceId instanceId, int cloudProviderUid,
            String cloudProviderPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_CLOUD_PROVIDER_ACTIVE,
                cloudProviderUid,
                cloudProviderPackage,
                instanceId);
    }

    /**
     * Log metrics to notify that the user has changed the active cloud provider
     * @param cloudProviderUid     new active cloud provider uid
     * @param cloudProviderPackage new active cloud provider package name
     */
    public void logPickerCloudProviderChanged(int cloudProviderUid, String cloudProviderPackage) {
        logger.log(PhotoPickerEvent.PHOTO_PICKER_CLOUD_PROVIDER_CHANGED, cloudProviderUid,
                cloudProviderPackage);
    }

    /**
     * Log metrics to notify that a picker uri was queried for an unknown column (that is not
     * supported yet)
     * @param callingUid     the uid of the app initiating the picker query
     * @param callingPackage the package name of the app initiating the picker query
     *
     * TODO(b/251425380): Move non-UI events out of PhotoPickerUiEventLogger
     */
    public void logPickerQueriedWithUnknownColumn(int callingUid, String callingPackage) {
        logger.log(PhotoPickerEvent.PHOTO_PICKER_QUERY_UNKNOWN_COLUMN,
                callingUid,
                callingPackage);
    }

    /**
     * Log metrics to notify that the user has clicked the mute / unmute button in a video preview
     * @param instanceId an identifier for the current picker session
     */
    public void logVideoPreviewMuteButtonClick(InstanceId instanceId) {
        logWithInstance(PhotoPickerEvent.PHOTO_PICKER_VIDEO_PREVIEW_AUDIO_BUTTON_CLICK, instanceId);
    }

    /**
     * Log metrics to notify that the user has clicked the 'view selected' button
     * @param instanceId        an identifier for the current picker session
     * @param selectedItemCount the number of items selected for preview all
     */
    public void logPreviewAllSelected(InstanceId instanceId, int selectedItemCount) {
        logger.logWithInstanceIdAndPosition(PhotoPickerEvent.PHOTO_PICKER_PREVIEW_ALL_SELECTED,
                /* uid */ 0, /* packageName */ null, instanceId, selectedItemCount);
    }

    /**
     * Log metrics to notify that the 'switch profile' button is visible & enabled
     * @param instanceId an identifier for the current picker session
     */
    public void logProfileSwitchButtonEnabled(InstanceId instanceId) {
        logWithInstance(PhotoPickerEvent.PHOTO_PICKER_PROFILE_SWITCH_BUTTON_ENABLED, instanceId);
    }

    /**
     * Log metrics to notify that the 'switch profile' button is visible but disabled
     * @param instanceId an identifier for the current picker session
     */
    public void logProfileSwitchButtonDisabled(InstanceId instanceId) {
        logWithInstance(PhotoPickerEvent.PHOTO_PICKER_PROFILE_SWITCH_BUTTON_DISABLED, instanceId);
    }

    /**
     * Log metrics to notify that the user has clicked the 'switch profile' button
     * @param instanceId an identifier for the current picker session
     */
    public void logProfileSwitchButtonClick(InstanceId instanceId) {
        logWithInstance(PhotoPickerEvent.PHOTO_PICKER_PROFILE_SWITCH_BUTTON_CLICK, instanceId);
    }

    /**
     * Log metrics to notify that the user selected a media item in the main grid
     * @param instanceId an identifier for the current picker session
     * @param position   the position of the album in the recycler view
     */
    public void logSelectedMainGridItem(InstanceId instanceId, int position) {
        logger.logWithInstanceIdAndPosition(PhotoPickerEvent.PHOTO_PICKER_SELECTED_ITEM_MAIN_GRID,
                /* uid */ 0, /* packageName */ null, instanceId, position);
    }

    /**
     * Log metrics to notify that the user selected a media item in an album
     * @param instanceId an identifier for the current picker session
     * @param position   the position of the album in the recycler view
     */
    public void logSelectedAlbumItem(InstanceId instanceId, int position) {
        logger.logWithInstanceIdAndPosition(PhotoPickerEvent.PHOTO_PICKER_SELECTED_ITEM_ALBUM,
                /* uid */ 0, /* packageName */ null, instanceId, position);
    }

    /**
     * Log metrics to notify that the user has selected a cloud only media item
     * @param instanceId an identifier for the current picker session
     * @param position   the position of the album in the recycler view
     */
    public void logSelectedCloudOnlyItem(InstanceId instanceId, int position) {
        logger.logWithInstanceIdAndPosition(PhotoPickerEvent.PHOTO_PICKER_SELECTED_ITEM_CLOUD_ONLY,
                /* uid */ 0, /* packageName */ null, instanceId, position);
    }

    /**
     * Log metrics to notify that the user has previewed an item in the main grid
     * @param specialFormat the special format of the previewed item (used to identify special
     *                      categories like motion photos)
     * @param mimeType      the mime type of the previewed item
     * @param instanceId    an identifier for the current picker session
     * @param position      the position of the album in the recycler view
     */
    public void logPreviewedMainGridItem(
            int specialFormat, String mimeType, InstanceId instanceId, int position) {
        logger.logWithInstanceIdAndPosition(PhotoPickerEvent.PHOTO_PICKER_PREVIEW_ITEM_MAIN_GRID,
                specialFormat, mimeType, instanceId, position);
    }

    private void logWithInstance(@NonNull UiEventLogger.UiEventEnum event, InstanceId instance) {
        logger.logWithInstanceId(event, /* uid */ 0, /* packageName */ null, instance);
    }
}
