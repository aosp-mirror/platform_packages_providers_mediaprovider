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
        PHOTO_PICKER_OPEN_WORK_PROFILE(943);

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

    public void logPickerOpenPersonal(InstanceId instanceId,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_OPEN_PERSONAL_PROFILE,
                0,
                callingPackage,
                instanceId);
    }

    public void logPickerOpenWork(InstanceId instanceId,
            String callingPackage) {
        logger.logWithInstanceId(
                PhotoPickerEvent.PHOTO_PICKER_OPEN_WORK_PROFILE,
                0,
                callingPackage,
                instanceId);
    }
}
