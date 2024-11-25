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

package com.android.providers.media.photopicker.v2.sqlite;

import static com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants.DEFAULT_SEARCH_HISTORY_SUGGESTIONS_LIMIT;
import static com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants.DEFAULT_SEARCH_SUGGESTIONS_LIMIT;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This is a convenience class for Search suggestions related query input parameters.
 */
public class SearchSuggestionsQuery {
    private static final String TAG = "SearchSuggestionsQuery";
    private int mLimit;
    private int mHistoryLimit;
    @NonNull
    private final String mPrefix;
    @NonNull
    private final List<String> mProviderAuthorities;
    private final boolean mIsZeroState;

    public SearchSuggestionsQuery(
            @NonNull String prefix,
            @NonNull List<String> providers) {
        mLimit = DEFAULT_SEARCH_SUGGESTIONS_LIMIT;
        mHistoryLimit = DEFAULT_SEARCH_HISTORY_SUGGESTIONS_LIMIT;
        mPrefix = prefix.trim();
        mProviderAuthorities = providers;
        mIsZeroState = mPrefix.isEmpty();
    }



    public SearchSuggestionsQuery(
            @NonNull Bundle queryArgs) {
        mLimit = queryArgs.getInt("limit", DEFAULT_SEARCH_SUGGESTIONS_LIMIT);
        mHistoryLimit = queryArgs.getInt(
                "history_limit", DEFAULT_SEARCH_HISTORY_SUGGESTIONS_LIMIT);
        mPrefix = Objects.requireNonNull(queryArgs.getString("prefix")).trim();
        mProviderAuthorities = new ArrayList<>(
                Objects.requireNonNull(queryArgs.getStringArrayList("providers")));
        mIsZeroState = mPrefix.isEmpty();

        if (mLimit <= 0) {
            Log.e(TAG, "Limit should be a positive integer - changing it to default limit.");
            mLimit = DEFAULT_SEARCH_SUGGESTIONS_LIMIT;
        }
        if (mHistoryLimit <= 0) {
            Log.e(TAG, "History limit should be a positive integer - "
                    + "changing it to default limit.");
            mHistoryLimit = DEFAULT_SEARCH_HISTORY_SUGGESTIONS_LIMIT;
        }
    }

    public int getLimit() {
        return mLimit;
    }

    public int getHistoryLimit() {
        return mHistoryLimit;
    }

    @NonNull
    public String getPrefix() {
        return mPrefix;
    }

    @NonNull
    public List<String> getProviderAuthorities() {
        return mProviderAuthorities;
    }

    public boolean isZeroState() {
        return mIsZeroState;
    }
}
