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

import android.provider.CloudMediaProviderContract.SearchSuggestionType;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Represents a user initiated search request by choosing a search suggestion.
 */
public class SearchSuggestionRequest extends SearchRequest {
    @NonNull
    private final SearchSuggestion mSearchSuggestion;

    public SearchSuggestionRequest(
            @Nullable List<String> mimeTypes,
            @Nullable String searchText,
            @Nullable String mediaSetId,
            @Nullable String suggestionAuthority,
            @SearchSuggestionType String searchSuggestionType
    ) {
        this(mimeTypes, searchText, mediaSetId, suggestionAuthority, searchSuggestionType,
                /* localSyncResumeKey */ null,
                /* localAuthority */ null,
                /* cloudSyncResumeKey */ null,
                /* cloudAuthority */ null);
    }

    public SearchSuggestionRequest(
            @Nullable List<String> mimeTypes,
            @Nullable String searchText,
            @Nullable String mediaSetId,
            @Nullable String suggestionAuthority,
            @SearchSuggestionType String searchSuggestionType,
            @Nullable String localSyncResumeKey,
            @Nullable String localAuthority,
            @Nullable String cloudSyncResumeKey,
            @Nullable String cloudAuthority
    ) {
        this(mimeTypes, searchText, mediaSetId, suggestionAuthority, searchSuggestionType,
                localSyncResumeKey, localAuthority, cloudSyncResumeKey, cloudAuthority,
                /* coverMediaSetId */ null);
    }

    public SearchSuggestionRequest(
            @Nullable List<String> mimeTypes,
            @Nullable String searchText,
            @Nullable String mediaSetId,
            @Nullable String suggestionAuthority,
            @SearchSuggestionType String searchSuggestionType,
            @Nullable String localSyncResumeKey,
            @Nullable String localAuthority,
            @Nullable String cloudSyncResumeKey,
            @Nullable String cloudAuthority,
            @Nullable String coverMediaId) {
        super(mimeTypes, localSyncResumeKey, localAuthority, cloudSyncResumeKey, cloudAuthority);

        mSearchSuggestion = new SearchSuggestion(searchText, mediaSetId, suggestionAuthority,
                searchSuggestionType, coverMediaId);
    }

    @NonNull
    public SearchSuggestion getSearchSuggestion() {
        return mSearchSuggestion;
    }
}
