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

package com.android.providers.media.photopicker.v2;

import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.MEDIA_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.PICKER_INTERNAL_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.PICKER_V2_PATH_SEGMENT;
import static com.android.providers.media.photopicker.v2.PickerUriResolverV2.UPDATE_PATH_SEGMENT;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

public class PickerNotificationSender {
    private static final Uri MEDIA_UPDATE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(MediaStore.AUTHORITY)
            .appendPath(PICKER_INTERNAL_PATH_SEGMENT)
            .appendPath(PICKER_V2_PATH_SEGMENT)
            .appendPath(MEDIA_PATH_SEGMENT)
            .appendPath(UPDATE_PATH_SEGMENT)
            .build();

    /**
     * Send media update notification to the registered {@link android.database.ContentObserver}-s.
     * @param context The application context.
     */
    public static void notifyMediaChange(@NonNull Context context) {
        context.getContentResolver().notifyChange(MEDIA_UPDATE_URI, /* observer= */ null);
    }
}
