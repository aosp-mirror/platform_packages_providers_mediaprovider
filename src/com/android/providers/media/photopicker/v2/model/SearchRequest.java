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

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Represents a user initiated search request. Each search request needs to be served with search
 * results data. This class and it's sub-classes contains properties of a search request.
 */
public abstract class SearchRequest {
    @VisibleForTesting
    public static final String DELIMITER = ";";
    // A list of mime type filters all in lowercase, sorted by natural order.
    @Nullable
    protected final List<String> mMimeTypes;
    @Nullable
    protected String mResumeKey;

    protected SearchRequest(@Nullable List<String> rawMimeTypes) {
        this (
                rawMimeTypes,
                /* resumeKey */ null
        );
    }

    protected SearchRequest(
            @Nullable String rawMimeTypes,
            @Nullable String resumeKey
    ) {
        this (getMimeTypesAsList(rawMimeTypes), resumeKey);
    }

    protected SearchRequest(
            @Nullable List<String> rawMimeTypes,
            @Nullable String resumeKey
    ) {
        if (rawMimeTypes != null) {
            mMimeTypes = new ArrayList<>();
            for (String mimeType : rawMimeTypes) {
                mMimeTypes.add(mimeType.toLowerCase(Locale.ROOT));
            }
            mMimeTypes.sort(Comparator.naturalOrder());
        } else {
            mMimeTypes = null;
        }

        mResumeKey = resumeKey;
    }

    /**
     * Returns the list of mime type filters as String. The mimetypes are all in lower case and
     * sorted by natural sort order. They're separated by a chosen delimiter
     * {@link SearchRequest#DELIMITER}. List of mime types is saved in the database as a string.
     * If the input is null, returns null.
     */
    @Nullable
    public static String getMimeTypesAsString(@Nullable List<String> mimeTypes) {
        if (mimeTypes == null) {
            return null;
        } else {
            return String.join(DELIMITER, mimeTypes).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Converts a string of mime types to list. The mimetypes in the given string are expected to be
     * separated by the chosen delimiter {@link SearchRequest#DELIMITER}.
     * If the input is null, returns null.
     */
    @Nullable
    public static List<String> getMimeTypesAsList(@Nullable String rawMimeTypes) {
        if (rawMimeTypes == null || rawMimeTypes.trim().isEmpty()) {
            return null;
        }
        return Arrays.asList(rawMimeTypes.split(DELIMITER));
    }

    @Nullable
    public List<String> getMimeTypes() {
        return mMimeTypes;
    }

    @Nullable
    public String getResumeKey() {
        return mResumeKey;
    }

    /**
     * Set the resume key for a given search request.
     */
    public void setResumeKey(@Nullable String mResumeKey) {
        this.mResumeKey = mResumeKey;
    }
}

