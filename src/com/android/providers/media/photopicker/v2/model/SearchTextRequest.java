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

import static java.util.Objects.requireNonNull;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;


/**
 * Represents a user initiated search request by entering search text.
 */
public class SearchTextRequest extends SearchRequest {
    @NonNull
    private final String mSearchText;

    public SearchTextRequest(
            @Nullable List<String> mimeTypes,
            @NonNull String searchText) {
        super(mimeTypes);

        mSearchText = requireNonNull(searchText);
    }

    public SearchTextRequest(
            @Nullable List<String> mimeTypes,
            @NonNull String searchText,
            @Nullable String resumeKey) {
        super(mimeTypes, resumeKey);

        mSearchText = requireNonNull(searchText);
    }

    @NonNull
    public String getSearchText() {
        return mSearchText;
    }
}
