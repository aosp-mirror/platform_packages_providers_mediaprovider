/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.providers.media.metrics.MPUiEventLoggerImpl;

/**
 * Logger for the Non UI Events triggered indirectly by some UI event(s).
 */
public class NonUiEventLogger {
    enum NonUiEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "User changed the active Photo picker cloud provider")
        PHOTO_PICKER_CLOUD_PROVIDER_CHANGED(1135),
        @UiEvent(doc = "Photo Picker uri is queried with an unknown column")
        PHOTO_PICKER_QUERY_UNKNOWN_COLUMN(1227),
        @UiEvent(doc = "Triggered a full sync in photo picker")
        PHOTO_PICKER_FULL_SYNC_START(1442),
        @UiEvent(doc = "Triggered an incremental sync in photo picker")
        PHOTO_PICKER_INCREMENTAL_SYNC_START(1443),
        @UiEvent(doc = "Triggered an album media sync in photo picker")
        PHOTO_PICKER_ALBUM_MEDIA_SYNC_START(1444),
        @UiEvent(doc = "Triggered get media collection info in photo picker")
        PHOTO_PICKER_GET_MEDIA_COLLECTION_INFO_START(1448),
        @UiEvent(doc = "Triggered get albums in photo picker")
        PHOTO_PICKER_GET_ALBUMS_START(1449),
        @UiEvent(doc = "Ended an add media sync in photo picker")
        PHOTO_PICKER_ADD_MEDIA_SYNC_END(1445),
        @UiEvent(doc = "Ended a remove media sync in photo picker")
        PHOTO_PICKER_REMOVE_MEDIA_SYNC_END(1446),
        @UiEvent(doc = "Ended an add album media sync in photo picker")
        PHOTO_PICKER_ADD_ALBUM_MEDIA_SYNC_END(1447),
        @UiEvent(doc = "Ended get media collection info in photo picker")
        PHOTO_PICKER_GET_MEDIA_COLLECTION_INFO_END(1450),
        @UiEvent(doc = "Ended get albums in photo picker")
        PHOTO_PICKER_GET_ALBUMS_END(1451),
        @UiEvent(doc = "Read grants added count.")
        PHOTO_PICKER_GRANTS_ADDED_COUNT(1528),
        @UiEvent(doc = "Read grants revoked count.")
        PHOTO_PICKER_GRANTS_REVOKED_COUNT(1529),
        @UiEvent(doc = "Total initial grants count.")
        PHOTO_PICKER_INIT_GRANTS_COUNT(1530);

        private final int mId;

        NonUiEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    private static final int INSTANCE_ID_MAX = 1 << 15;
    private static final InstanceIdSequence INSTANCE_ID_SEQUENCE =
            new InstanceIdSequence(INSTANCE_ID_MAX);
    private static final UiEventLogger LOGGER = new MPUiEventLoggerImpl();

    /**
     * Generate and {@return} a new unique instance id to group some events for aggregated metrics
     */
    public static InstanceId generateInstanceId() {
        return INSTANCE_ID_SEQUENCE.newInstanceId();
    }

    /**
     * Log metrics to notify that the user has changed the active cloud provider
     * @param cloudProviderUid     new active cloud provider uid
     * @param cloudProviderPackage new active cloud provider package name
     */
    public static void logPickerCloudProviderChanged(int cloudProviderUid,
            String cloudProviderPackage) {
        LOGGER.log(NonUiEvent.PHOTO_PICKER_CLOUD_PROVIDER_CHANGED, cloudProviderUid,
                cloudProviderPackage);
    }

    /**
     * Log metrics to notify that a picker uri was queried for an unknown column (that is not
     * supported yet)
     * @param callingUid              the uid of the app initiating the picker query
     * @param callingPackageAndColumn the package name of the app initiating the picker query,
     *                                followed by the unknown column name, separated by a ':'
     */
    public static void logPickerQueriedWithUnknownColumn(int callingUid,
            String callingPackageAndColumn) {
        LOGGER.log(NonUiEvent.PHOTO_PICKER_QUERY_UNKNOWN_COLUMN, callingUid,
                callingPackageAndColumn);
    }

    /**
     * Log metrics to notify that a full sync started
     * @param instanceId an identifier for the current sync
     * @param uid        the uid of the MediaProvider logging this metric
     * @param authority  the authority of the provider syncing with
     */
    public static void logPickerFullSyncStart(InstanceId instanceId, int uid, String authority) {
        LOGGER.logWithInstanceId(NonUiEvent.PHOTO_PICKER_FULL_SYNC_START, uid, authority,
                instanceId);
    }

    /**
     * Log metrics to notify that an incremental sync started
     * @param instanceId an identifier for the current sync
     * @param uid        the uid of the MediaProvider logging this metric
     * @param authority  the authority of the provider syncing with
     */
    public static void logPickerIncrementalSyncStart(InstanceId instanceId, int uid,
            String authority) {
        LOGGER.logWithInstanceId(NonUiEvent.PHOTO_PICKER_INCREMENTAL_SYNC_START, uid, authority,
                instanceId);
    }

    /**
     * Log metrics to notify that an album media sync started
     * @param instanceId an identifier for the current sync
     * @param uid        the uid of the MediaProvider logging this metric
     * @param authority  the authority of the provider syncing with
     */
    public static void logPickerAlbumMediaSyncStart(InstanceId instanceId, int uid,
            String authority) {
        LOGGER.logWithInstanceId(NonUiEvent.PHOTO_PICKER_ALBUM_MEDIA_SYNC_START, uid, authority,
                instanceId);
    }

    /**
     * Log metrics to notify get media collection info triggered
     * @param instanceId an identifier for the current query session
     * @param uid        the uid of the MediaProvider logging this metric
     * @param authority  the authority of the provider
     */
    public static void logPickerGetMediaCollectionInfoStart(InstanceId instanceId, int uid,
            String authority) {
        LOGGER.logWithInstanceId(NonUiEvent.PHOTO_PICKER_GET_MEDIA_COLLECTION_INFO_START, uid,
                authority, instanceId);
    }

    /**
     * Log metrics to notify get albums triggered
     * @param instanceId an identifier for the current query session
     * @param uid        the uid of the MediaProvider logging this metric
     * @param authority  the authority of the provider
     */
    public static void logPickerGetAlbumsStart(InstanceId instanceId, int uid, String authority) {
        LOGGER.logWithInstanceId(NonUiEvent.PHOTO_PICKER_GET_ALBUMS_START, uid, authority,
                instanceId);
    }

    /**
     * Log metrics to notify that an add media sync ended
     * @param instanceId an identifier for the current sync
     * @param uid        the uid of the MediaProvider logging this metric
     * @param authority  the authority of the provider syncing with
     * @param count      the number of items synced
     */
    public static void logPickerAddMediaSyncCompletion(InstanceId instanceId, int uid,
            String authority, int count) {
        LOGGER.logWithInstanceIdAndPosition(NonUiEvent.PHOTO_PICKER_ADD_MEDIA_SYNC_END, uid,
                authority, instanceId, count);
    }

    /**
     * Log metrics to notify that a remove media sync ended
     * @param instanceId an identifier for the current sync
     * @param uid        the uid of the MediaProvider logging this metric
     * @param authority  the authority of the provider syncing with
     * @param count      the number of items synced
     */
    public static void logPickerRemoveMediaSyncCompletion(InstanceId instanceId, int uid,
            String authority, int count) {
        LOGGER.logWithInstanceIdAndPosition(NonUiEvent.PHOTO_PICKER_REMOVE_MEDIA_SYNC_END, uid,
                authority, instanceId, count);
    }

    /**
     * Log metrics to notify that an add album media sync ended
     * @param instanceId an identifier for the current sync
     * @param uid        the uid of the MediaProvider logging this metric
     * @param authority  the authority of the provider syncing with
     * @param count      the number of items synced
     */
    public static void logPickerAddAlbumMediaSyncCompletion(InstanceId instanceId, int uid,
            String authority, int count) {
        LOGGER.logWithInstanceIdAndPosition(NonUiEvent.PHOTO_PICKER_ADD_ALBUM_MEDIA_SYNC_END, uid,
                authority, instanceId, count);
    }

    /**
     * Log metrics to notify get media collection info ended
     * @param instanceId an identifier for the current query session
     * @param uid        the uid of the MediaProvider logging this metric
     * @param authority  the authority of the provider
     */
    public static void logPickerGetMediaCollectionInfoEnd(InstanceId instanceId, int uid,
            String authority) {
        LOGGER.logWithInstanceId(NonUiEvent.PHOTO_PICKER_GET_MEDIA_COLLECTION_INFO_END, uid,
                authority, instanceId);
    }

    /**
     * Log metrics to notify get albums ended
     * @param instanceId an identifier for the current query session
     * @param uid        the uid of the MediaProvider logging this metric
     * @param authority  the authority of the provider
     * @param count      the number of albums fetched
     */
    public static void logPickerGetAlbumsEnd(InstanceId instanceId, int uid, String authority,
            int count) {
        LOGGER.logWithInstanceIdAndPosition(NonUiEvent.PHOTO_PICKER_GET_ALBUMS_END, uid, authority,
                instanceId, count);
    }

    /**
     * Log metrics for count of grants added for a package.
     * @param instanceId   an identifier for the current session
     * @param uid          the uid of the MediaProvider logging this metric
     * @param packageName  the package name receiving the grant.
     * @param count        the number of items for which the grants have been added.
     */
    public static void logPickerChoiceGrantsAdditionCount(InstanceId instanceId, int uid,
            String packageName, int count) {
        LOGGER.logWithInstanceIdAndPosition(NonUiEvent.PHOTO_PICKER_GRANTS_ADDED_COUNT, uid,
                packageName, instanceId, count);
    }

    /**
     * Log metrics for count of grants revoked for a package.
     * @param instanceId   an identifier for the current session
     * @param uid          the uid of the MediaProvider logging this metric
     * @param packageName  the package name for which the grants are being revoked.
     * @param count        the number of items for which the grants have been revoked.
     */
    public static void logPickerChoiceGrantsRemovedCount(InstanceId instanceId, int uid,
            String packageName, int count) {
        LOGGER.logWithInstanceIdAndPosition(NonUiEvent.PHOTO_PICKER_GRANTS_REVOKED_COUNT, uid,
                packageName, instanceId, count);
    }

    /**
     * Log metrics for total count of grants previously added for the package.
     * @param instanceId   an identifier for the current session
     * @param uid          the uid of the MediaProvider logging this metric
     * @param packageName  the package name for which the grants are being initialized.
     * @param count        the number of items for which the grants have been initialized.
     */
    public static void logPickerChoiceInitGrantsCount(InstanceId instanceId, int uid,
            String packageName, int count) {
        LOGGER.logWithInstanceIdAndPosition(NonUiEvent.PHOTO_PICKER_INIT_GRANTS_COUNT, uid,
                packageName, instanceId, count);
    }

}
