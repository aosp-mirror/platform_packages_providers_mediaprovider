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

package com.android.providers.media.photopicker.v2.model;

import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.Objects;

public class MediaInMediaSetSyncRequestParams {
    public static final String KEY_PARENT_MEDIA_SET_AUTHORITY = "media_set_picker_authority";
    public static final String KEY_PARENT_MEDIA_SET_PICKER_ID = "media_set_picker_id";
    private final String mAuthority;
    private final Long mMediaSetPickerId;

    public MediaInMediaSetSyncRequestParams(@NonNull Bundle extras) {
        Objects.requireNonNull(extras);
        mAuthority = extras.getString(KEY_PARENT_MEDIA_SET_AUTHORITY);
        mMediaSetPickerId = extras.getLong(KEY_PARENT_MEDIA_SET_PICKER_ID);
        Objects.requireNonNull(mAuthority);
    }

    public String getAuthority() {
        return mAuthority;
    }

    public Long getMediaSetPickerId() {
        return mMediaSetPickerId;
    }
}
