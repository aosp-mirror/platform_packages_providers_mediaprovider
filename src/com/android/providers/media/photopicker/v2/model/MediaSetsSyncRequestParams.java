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

/*
 A class to extract out all the required input parameters for syncing media sets with the given
 provider.
 */
public class MediaSetsSyncRequestParams {

    private final String mAuthority;
    private final String mCategoryId;
    private final String[] mMimeTypes;

    public MediaSetsSyncRequestParams(@NonNull Bundle extras) {
        Objects.requireNonNull(extras);
        mAuthority = extras.getString("authority");
        mMimeTypes = extras.getStringArray("mime_types");
        mCategoryId = extras.getString("category_id");
    }

    public String getAuthority() {
        return mAuthority;
    }

    public String getCategoryId() {
        return mCategoryId;
    }

    public String[] getMimeTypes() {
        return mMimeTypes;
    }
}
