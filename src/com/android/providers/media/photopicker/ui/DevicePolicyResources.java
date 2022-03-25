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

package com.android.providers.media.photopicker.ui;

import android.app.admin.DevicePolicyManager;

/**
 * Class containing the required identifiers to update device management resources.
 *
 * <p>See {@link DevicePolicyManager#getDrawable} and {@link DevicePolicyManager#getString}.
 */
public class DevicePolicyResources {

    /**
     * Class containing the identifiers used to update device management-related system strings.
     */
    public static final class Strings {
        private static final String PREFIX = "MediaProvider.";

        /**
         * The text shown to switch to the work profile in PhotoPicker.
         */
        public static final String SWITCH_TO_WORK_MESSAGE =
                PREFIX + "SWITCH_TO_WORK_MESSAGE";

        /**
         * The text shown to switch to the personal profile in PhotoPicker.
         */
        public static final String SWITCH_TO_PERSONAL_MESSAGE =
                PREFIX + "SWITCH_TO_PERSONAL_MESSAGE";

        /**
         * The title for error dialog in PhotoPicker when the admin blocks cross user
         * interaction for the intent.
         */
        public static final String BLOCKED_BY_ADMIN_TITLE =
                PREFIX + "BLOCKED_BY_ADMIN_TITLE";

        /**
         * The message for error dialog in PhotoPicker when the admin blocks cross user
         * interaction from the personal profile.
         */
        public static final String BLOCKED_FROM_PERSONAL_MESSAGE =
                PREFIX + "BLOCKED_FROM_PERSONAL_MESSAGE";

        /**
         * The message for error dialog in PhotoPicker when the admin blocks cross user
         * interaction from the work profile.
         */
        public static final String BLOCKED_FROM_WORK_MESSAGE =
                PREFIX + "BLOCKED_FROM_WORK_MESSAGE";

        /**
         * The title of the error dialog in PhotoPicker when the user tries to switch to work
         * content, but work profile is off.
         */
        public static final String WORK_PROFILE_PAUSED_TITLE =
                PREFIX + "WORK_PROFILE_PAUSED_TITLE";

        /**
         * The message of the error dialog in PhotoPicker when the user tries to switch to work
         * content, but work profile is off.
         */
        public static final String WORK_PROFILE_PAUSED_MESSAGE =
                PREFIX + "WORK_PROFILE_PAUSED_MESSAGE";
    }

    /**
     * Class containing the identifiers used to update device management-related system drawable.
     */
    public static final class Drawables {
        /**
         * General purpose work profile icon (i.e. generic icon badging).
         */
        public static final String WORK_PROFILE_ICON = "WORK_PROFILE_ICON";

        /**
         * Class containing the style identifiers used to update device management-related system
         * drawable.
         */
        public static final class Style {
            /**
             * A style identifier indicating that the updatable drawable is an outline.
             */
            public static final String OUTLINE = "OUTLINE";
        }
    }
}
