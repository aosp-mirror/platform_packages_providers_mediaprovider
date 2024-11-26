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

package com.android.providers.media.photopicker.sync;

import static android.provider.CloudMediaProviderContract.EXTRA_PROVIDER_CAPABILITIES;
import static android.provider.CloudMediaProviderContract.METHOD_GET_CAPABILITIES;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.CloudMediaProviderContract;
import android.provider.CloudMediaProviderContract.SortOrder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A client class responsible for fetching search results from
 * cloud media provider and local search provider.
 */
public class PickerSearchProviderClient {
    private static final String TAG = "PickerSearchProviderClient";

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
    public Cursor fetchSearchResultsFromCmp(
            @Nullable String suggestedMediaSetId,
            @Nullable String searchText,
            @SortOrder int sortOrder,
            int pageSize,
            @Nullable String resumePageToken,
            @Nullable CancellationSignal cancellationSignal) {
        if (suggestedMediaSetId == null && searchText == null) {
            throw new IllegalArgumentException(
                    "both suggestedMediaSet and searchText can not be null at once");
        }
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(CloudMediaProviderContract.KEY_SEARCH_TEXT, searchText);
        queryArgs.putString(CloudMediaProviderContract.KEY_MEDIA_SET_ID, suggestedMediaSetId);
        queryArgs.putInt(CloudMediaProviderContract.EXTRA_PAGE_SIZE, pageSize);
        queryArgs.putString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN, resumePageToken);
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
            int limit,
            @Nullable CancellationSignal cancellationSignal) {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(CloudMediaProviderContract.KEY_PREFIX_TEXT, requireNonNull(prefixText));
        queryArgs.putInt(CloudMediaProviderContract.EXTRA_PAGE_SIZE, limit);
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
    public Cursor fetchMediaSetsFromCmp(
            @NonNull String mediaCategoryId, @Nullable String nextPageToken, int pageSize,
            @Nullable CancellationSignal cancellationSignal) {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(CloudMediaProviderContract.KEY_MEDIA_CATEGORY_ID,
                requireNonNull(mediaCategoryId));
        queryArgs.putString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN, nextPageToken);
        queryArgs.putInt(CloudMediaProviderContract.EXTRA_PAGE_SIZE, pageSize);
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

    /**
     * Fetches the {@link android.provider.CloudMediaProviderContract.Capabilities} from the
     * cloud media provider and returns them. In case there is an issue in fetching the
     * capabilities, this method returns the default capabilities.
     */
    @NonNull
    public CloudMediaProviderContract.Capabilities fetchCapabilities() {
        try {
            final Bundle response = mContext.getContentResolver().call(
                    mCloudProviderAuthority,
                    METHOD_GET_CAPABILITIES,
                    /* arg */ null,
                    /* extras */ null);
            requireNonNull(response);

            final CloudMediaProviderContract.Capabilities capabilities =
                    response.getParcelable(EXTRA_PROVIDER_CAPABILITIES);
            requireNonNull(capabilities);

            return capabilities;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not fetch capabilities from " + mCloudProviderAuthority);

            // Return default capabilities.
            return new CloudMediaProviderContract.Capabilities.Builder().build();
        }
    }
}
