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
 * Represents a user initiated search request by choosing a search suggestion.
 */
public class SearchSuggestionRequest extends SearchRequest {
    @Nullable
    protected final String mSearchText;
    @NonNull
    protected final String mMediaSetId;
    @NonNull
    protected final String mAuthority;
    @NonNull
    protected final SearchSuggestionType mSearchSuggestionType;

    public SearchSuggestionRequest(
            @Nullable List<String> mimeTypes,
            @Nullable String searchText,
            @NonNull String mediaSetId,
            @NonNull String authority,
            @NonNull SearchSuggestionType searchSuggestionType,
            @Nullable String resumeKey) {
        super(mimeTypes, resumeKey);

        mSearchText = searchText;
        mMediaSetId = requireNonNull(mediaSetId);
        mAuthority = requireNonNull(authority);
        mSearchSuggestionType = requireNonNull(searchSuggestionType);
    }

    @Nullable
    public String getSearchText() {
        return mSearchText;
    }

    @NonNull
    public String getMediaSetId() {
        return mMediaSetId;
    }

    @NonNull
    public String getAuthority() {
        return mAuthority;
    }

    @NonNull
    public SearchSuggestionType getSearchSuggestionType() {
        return mSearchSuggestionType;
    }
}
