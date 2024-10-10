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

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.CloudMediaProviderContract;
import android.provider.CloudMediaProviderContract.SortOrder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A client class responsible for fetching search results from
 * cloud media provider and local search provider.
 */
public class PickerSearchProviderClient {

    @NonNull
    private final Context mContext;

    @NonNull
    private final String mCloudProviderAuthority;

    private PickerSearchProviderClient(@NonNull Context context,
            @NonNull String cloudProviderAuthority) {
        mContext = requireNonNull(context);
        mCloudProviderAuthority = requireNonNull(cloudProviderAuthority);
    }

    /**
     * Create instance of a picker search client.
     */
    public static PickerSearchProviderClient create(@NonNull Context context,
            @NonNull String cloudProviderAuthority) {
        return new PickerSearchProviderClient(context, cloudProviderAuthority);
    }

    /**
     * Method for querying CloudMediaProvider for media search result.
     * Note: This functions does not expect pagination args.
     */
    @Nullable
    public Cursor fetchSearchResultsFromCmp(@Nullable String suggestedMediaSetId,
            @Nullable String searchText, @NonNull @SortOrder int sortOrder,
            @Nullable CancellationSignal cancellationSignal) {
        if (suggestedMediaSetId == null && searchText == null) {
            throw new IllegalArgumentException(
                    "both suggestedMediaSet and searchText can not be null at once");
        }
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(CloudMediaProviderContract.KEY_SEARCH_TEXT, searchText);
        queryArgs.putString(CloudMediaProviderContract.KEY_MEDIA_SET_ID, suggestedMediaSetId);
        queryArgs.putInt(CloudMediaProviderContract.EXTRA_SORT_ORDER, sortOrder);

        return mContext.getContentResolver().query(
                getCloudUriFromPath(CloudMediaProviderContract.URI_PATH_SEARCH_MEDIA),
                null, queryArgs,  cancellationSignal);
    }

    /**
     * Method for querying CloudMediaProvider for search suggestions
     */
    @Nullable
    public Cursor fetchSearchSuggestionsFromCmp(@NonNull String prefixText,
            @Nullable CancellationSignal cancellationSignal) {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(CloudMediaProviderContract.KEY_PREFIX_TEXT, requireNonNull(prefixText));
        return mContext.getContentResolver().query(
                getCloudUriFromPath(CloudMediaProviderContract.URI_PATH_SEARCH_SUGGESTION),
                null, queryArgs,  cancellationSignal);
    }

    /**
     * Method for querying CloudMediaProvider for MediaCategories
     */
    @Nullable
    public Cursor fetchMediaCategoriesFromCmp(@Nullable String parentCategoryId,
            @Nullable CancellationSignal cancellationSignal) {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(CloudMediaProviderContract.KEY_PARENT_CATEGORY_ID, parentCategoryId);
        return mContext.getContentResolver().query(
                getCloudUriFromPath(CloudMediaProviderContract.URI_PATH_MEDIA_CATEGORY),
                null, queryArgs, cancellationSignal);
    }

    /**
     * Method for querying CloudMediaProvider for MediaSets
     */
    @Nullable
    public Cursor fetchMediaSetsFromCmp(@NonNull String mediaCategoryId,
            @Nullable CancellationSignal cancellationSignal) {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(CloudMediaProviderContract.KEY_MEDIA_CATEGORY_ID,
                requireNonNull(mediaCategoryId));
        return mContext.getContentResolver().query(
                getCloudUriFromPath(CloudMediaProviderContract.URI_PATH_MEDIA_SET),
                null, queryArgs,  cancellationSignal);
    }

    /**
     * Method for querying Medias inside a  MediaSet
     */
    @Nullable
    public Cursor fetchMediasInMediaSetFromCmp(@NonNull String mediaSetId,
            @Nullable CancellationSignal cancellationSignal) {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(CloudMediaProviderContract.KEY_MEDIA_SET_ID,
                requireNonNull(mediaSetId));
        return mContext.getContentResolver().query(
                getCloudUriFromPath(CloudMediaProviderContract.URI_PATH_MEDIA_IN_MEDIA_SET),
                null, queryArgs,  cancellationSignal);
    }

    private Uri getCloudUriFromPath(String uriPath) {
        return Uri.parse("content://" + mCloudProviderAuthority + "/" + uriPath);
    }

}
