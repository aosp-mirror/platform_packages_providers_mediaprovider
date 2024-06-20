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

import com.android.providers.media.util.MimeUtils;

import java.util.ArrayList;

/**
 * This is a convenience class for Videos album related SQL queries performed on the Picker
 * Database.
 */
public class VideoMediaQuery extends MediaQuery {
    public VideoMediaQuery(@NonNull Bundle queryArgs, int pageSize) {
        this(queryArgs);

        mPageSize = pageSize;
    }

    public VideoMediaQuery(@NonNull Bundle queryArgs) {
        super(queryArgs);

        if (mMimeTypes == null) {
            // If there are no MIME type filters applied, add all videos MIME type filter.
            mMimeTypes = new ArrayList<String>();
            mMimeTypes.add("video/*");
        } else {
            // If there are MIME type filters applied, only keep videos MIME type filters.
            mMimeTypes.removeIf(mimeType -> !MimeUtils.isVideoMimeType(mimeType));
        }
    }

    /**
     * @return true if any videos can be displayed after applying the input MIME type filters,
     * otherwise return false.
     */
    public boolean shouldDisplayVideosAlbum() {
        // Only display Videos album when at least one valid Videos filter is applied.
        if (mMimeTypes != null && mMimeTypes.isEmpty()) {
            return false;
        }
        return true;
    }
}
