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

import android.provider.CloudMediaProviderContract.SearchSuggestionType;

import androidx.annotation.Nullable;

/**
 * Represents a Search Suggestion.
 */
public class SearchSuggestion {
    @Nullable
    private final String mSearchText;
    @Nullable
    private final String mMediaSetId;
    @Nullable
    private final String mAuthority;
    @SearchSuggestionType
    private final String mSearchSuggestionType;
    @Nullable
    private final String mCoverMediaId;

    public SearchSuggestion(
            @Nullable String searchText,
            @Nullable String mediaSetId,
            @Nullable String authority,
            @SearchSuggestionType String searchSuggestionType,
            @Nullable String coverMediaId) {
        mSearchText = searchText;
        mMediaSetId = mediaSetId;
        mAuthority = authority;
        mSearchSuggestionType = requireNonNull(searchSuggestionType);
        mCoverMediaId = coverMediaId;
    }

    @Nullable
    public String getSearchText() {
        return mSearchText;
    }

    @Nullable
    public String getMediaSetId() {
        return mMediaSetId;
    }

    @Nullable
    public String getAuthority() {
        return mAuthority;
    }

    @SearchSuggestionType
    public String getSearchSuggestionType() {
        return mSearchSuggestionType;
    }

    @Nullable
    public String getCoverMediaId() {
        return mCoverMediaId;
    }
}
