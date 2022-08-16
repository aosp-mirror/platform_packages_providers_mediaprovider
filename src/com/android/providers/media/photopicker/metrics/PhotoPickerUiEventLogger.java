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
        @UiEvent(doc = "DocumentsUi opened by clicking on Browse in Photo picker")
        PHOTO_PICKER_BROWSE_DOCUMENTSUI(1085),
        @UiEvent(doc = "Photo picker cancelled in work profile")
        PHOTO_PICKER_CANCEL_WORK_PROFILE(1125),
        @UiEvent(doc = "Photo picker cancelled in personal profile")
        PHOTO_PICKER_CANCEL_PERSONAL_PROFILE(1126),
        @UiEvent(doc = "Confirmed selection in Photo picker in work profile")
        PHOTO_PICKER_CONFIRM_WORK_PROFILE(1127),
        @UiEvent(doc = "Confirmed selection in Photo picker in personal profile")
        PHOTO_PICKER_CONFIRM_PERSONAL_PROFILE(1128);

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
}
