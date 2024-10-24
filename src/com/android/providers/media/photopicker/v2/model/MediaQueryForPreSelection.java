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

import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_CLOUD_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_LOCAL_ID;
import static com.android.providers.media.photopicker.v2.sqlite.MediaProjection.prependTableName;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SelectSQLiteQueryBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * This is a convenience class for media content related SQL queries performed on the Picker
 * Database. Used for the queries that require filter parameters based on input pre-Selection URIs.
 */
public class MediaQueryForPreSelection extends MediaQuery {

    private static final String TAG = "MediaQuery:PreSelection";

    private final List<String> mPreSelectionUris;
    private List<String> mLocalIdSelection = new ArrayList<>();
    private List<String> mCloudIdSelection = new ArrayList<>();

    public MediaQueryForPreSelection(
            @NonNull Bundle queryArgs) {
        super(queryArgs);
        mPreSelectionUris = queryArgs.getStringArrayList("pre_selection_uris");
    }

    public List<String> getPreSelectionUris() {
        return mPreSelectionUris;
    }

    @Override
    public void addWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            boolean reverseOrder
    ) {
        super.addWhereClause(queryBuilder, table, localAuthority, cloudAuthority, reverseOrder);

        String idSelection = addIdSelectionClause(table, cloudAuthority);
        queryBuilder.appendWhereStandalone(idSelection);
    }

    private String addIdSelectionClause(
            @NonNull PickerSQLConstants.Table table,
            @Nullable String cloudAuthority) {

        StringBuilder idSelectionClause = new StringBuilder();

        idSelectionClause.append(prependTableName(table, KEY_LOCAL_ID)).append(" IN (\'");
        idSelectionClause.append(String.join("\',\'", mLocalIdSelection));
        idSelectionClause.append("\')");

        if (cloudAuthority != null) {
            if (!idSelectionClause.toString().isEmpty()) {
                idSelectionClause.append(" OR ");
            }
            idSelectionClause.append(prependTableName(table, KEY_CLOUD_ID)).append(" IN (\'");
            idSelectionClause.append(String.join("\',\'", mCloudIdSelection));
            idSelectionClause.append("\')");
        }

        return idSelectionClause.toString();
    }

    /**
     * Filters URIs received for preSelection based on permission, authority and validity checks.
     */
    public void processUrisForSelection(@Nullable List<String> inputUrisAsStrings,
            @Nullable String localProvider,
            @Nullable String cloudProvider,
            boolean isLocalOnly,
            @NonNull Context appContext,
            int callingPackageUid) {

        if (inputUrisAsStrings == null) {
            // If no input selection is present then return;
            return;
        }

        Set<Uri> inputUris = screenArgsForPermissionCheckIfAny(
                inputUrisAsStrings, appContext, callingPackageUid);

        populateLocalAndCloudIdListsForSelection(
                inputUris, localProvider, cloudProvider, isLocalOnly);
    }

    private Set<Uri> screenArgsForPermissionCheckIfAny(
            @NonNull List<String> inputUris, @NonNull Context appContext, int callingPackageUid) {

        if (/* uid not found */ callingPackageUid == 0
                || /* uid is invalid */ callingPackageUid == -1) {
            // if calling uid is absent or is invalid then throw an error
            throw new IllegalArgumentException("Filtering Uris for Selection: "
                    + "Uid absent or invalid");
        }

        Set<Uri> accessibleUris = new HashSet<>();
        // perform checks and filtration.
        for (String uriAsString : inputUris) {
            Uri uriForSelection = Uri.parse(uriAsString);
            try {
                // verify if the calling package have permission to the requested uri.
                PickerUriResolver.checkUriPermission(appContext,
                        uriForSelection, /* pid */ -1, callingPackageUid);
                accessibleUris.add(uriForSelection);
            } catch (SecurityException se) {
                Log.d(TAG,
                        "Filtering Uris for Selection: package does not have permission for "
                                + "the uri: "
                                + uriAsString);
            }
        }
        return accessibleUris;
    }

    private void populateLocalAndCloudIdListsForSelection(
            @NonNull Set<Uri> inputUris, @Nullable String localProvider,
            @Nullable String cloudProvider, boolean isLocalOnly) {
        ArrayList<String> localIds = new ArrayList<>();
        ArrayList<String> cloudIds = new ArrayList<>();
        for (Uri uriForSelection : inputUris) {
            try {
                // unwrap picker uri to get host and id.
                Uri uri = PickerUriResolver.unwrapProviderUri(uriForSelection);
                if (localProvider != null && localProvider.equals(uri.getHost())) {
                    // Adds the last segment (id) to localIds if the authority matches the
                    // local authority.
                    localIds.add(uri.getLastPathSegment());
                } else if (!isLocalOnly && cloudProvider != null && cloudProvider.equals(
                        uri.getHost())) {
                    // Adds the last segment (id) to cloudIds if the authority matches the
                    // current cloud authority.
                    cloudIds.add(uri.getLastPathSegment());
                } else {
                    Log.d(TAG,
                            "Filtering Uris for Selection: Unknown authority/host for the uri: "
                                    + uriForSelection);
                }
            } catch (IllegalArgumentException illegalArgumentException) {
                Log.d(TAG, "Filtering Uris for Selection: Input uri: " + uriForSelection
                        + " is not valid.");
            }
        }
        mLocalIdSelection = localIds;
        mCloudIdSelection = cloudIds;
        if (!cloudIds.isEmpty() || !mLocalIdSelection.isEmpty()) {
            Log.d(TAG, "Id selection has been enabled in the current query operation.");
        } else {
            Log.d(TAG, "Id selection has not been enabled in the current query operation.");
        }
    }
}

