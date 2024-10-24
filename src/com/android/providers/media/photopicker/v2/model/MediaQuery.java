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

import static com.android.providers.media.MediaGrants.FILE_ID_COLUMN;
import static com.android.providers.media.MediaGrants.MEDIA_GRANTS_TABLE;
import static com.android.providers.media.MediaGrants.PACKAGE_USER_ID_COLUMN;
import static com.android.providers.media.photopicker.PickerSyncController.getPackageNameFromUid;
import static com.android.providers.media.photopicker.PickerSyncController.uidToUserId;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_CLOUD_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_DATE_TAKEN_MS;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_IS_VISIBLE;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_LOCAL_ID;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_MIME_TYPE;
import static com.android.providers.media.photopicker.v2.PickerDataLayerV2.CURRENT_GRANTS_TABLE;
import static com.android.providers.media.photopicker.v2.sqlite.MediaProjection.prependTableName;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.MediaGrants;
import com.android.providers.media.photopicker.v2.PickerDataLayerV2;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SelectSQLiteQueryBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Encapsulates all query arguments of a Media query i.e. query to fetch Media Items from Picker DB.
 */
public class MediaQuery {
    private final long mDateTakenMs;
    private final long mPickerId;
    @Nullable
    private final String mIntentAction;
    @NonNull
    private final List<String> mProviders;
    private final int mCallingPackageUid;
    // If this is not null or empty, only fetch the rows that match at least one of the
    // given mime types.
    @Nullable
    protected List<String> mMimeTypes;
    protected int mPageSize;
    // If this is true, only fetch the rows from Picker Database where the IS_VISIBLE flag is on.
    protected boolean mShouldDedupe;
    protected boolean mShouldPopulateItemsBeforeCount;

    public MediaQuery(Bundle queryArgs) {
        mPickerId = queryArgs.getLong("picker_id", Long.MAX_VALUE);
        mDateTakenMs = queryArgs.getLong("date_taken_millis", Long.MAX_VALUE);
        mPageSize = queryArgs.getInt("page_size", Integer.MAX_VALUE);
        mIntentAction = queryArgs.getString("intent_action");

        // Make deep copies of the arrays to avoid leaking changes made to the arrays.
        mProviders = new ArrayList<>(
                Objects.requireNonNull(queryArgs.getStringArrayList("providers")));
        mMimeTypes = queryArgs.getStringArrayList("mime_types") != null
                ? new ArrayList<>(queryArgs.getStringArrayList("mime_types")) : null;

        // This is true by default.
        mShouldDedupe = true;
        mCallingPackageUid = queryArgs.getInt(Intent.EXTRA_UID, -1);

        // This is true by default. When this is true, include items before count in the resultant
        // query cursor extras when the data is being served from the Picker DB cache.
        mShouldPopulateItemsBeforeCount = true;
    }

    @NonNull
    public Integer getPageSize() {
        return mPageSize;
    }

    @NonNull
    public List<String> getProviders() {
        return mProviders;
    }

    @Nullable
    public List<String> getMimeTypes() {
        return mMimeTypes;
    }

    @Nullable
    public String getIntentAction() {
        return mIntentAction;
    }

    public int getCallingPackageUid() {
        return mCallingPackageUid;
    }

    public boolean shouldPopulateItemsBeforeCount() {
        return mShouldPopulateItemsBeforeCount;
    }

    /**
     * Create and return a bundle for extras for CMP queries made from Media Provider.
     */
    @NonNull
    public Bundle prepareCMPQueryArgs() {
        final Bundle queryArgs = new Bundle();
        if (mMimeTypes != null) {
            queryArgs.putStringArray(Intent.EXTRA_MIME_TYPES, mMimeTypes.toArray(new String[0]));
        }
        return queryArgs;
    }

    /**
     * Returns the table that should be used in the query operations including any joins that are
     * required with other tables in the database.
     */
    public String getTableWithRequiredJoins(String table,
            @NonNull Context appContext, int callingPackageUid, String intentAction) {

        if (!MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.equals(intentAction)) {
            // No joins are required for a ACTION_USER_SELECT_IMAGES_FOR_APP action query.
            return table;
        }
        Objects.requireNonNull(appContext);
        if (callingPackageUid == -1) {
            throw new IllegalArgumentException("Calling package uid in"
                    + "ACTION_USER_SELECT_IMAGES_FOR_APP mode should not be -1. Invalid UID");
        }

        int userId = uidToUserId(callingPackageUid);
        String[] packageNames = getPackageNameFromUid(appContext,
                callingPackageUid);
        Objects.requireNonNull(packageNames);
        StringBuilder packageSelection =
                PickerDataLayerV2.getPackageSelectionWhereClause(packageNames, MEDIA_GRANTS_TABLE);

        // The following join is performed for the query media operation to obtain information on
        // which items are preGranted.
        String filterQueryBasedOnPackageNameAndUserId =
                "(SELECT %s.%s FROM %s "
                + "WHERE "
                + " %s AND "
                + "%s = %d) "
                + "AS %s";

        String filteredMediaGrantsTable = String.format(
                Locale.ROOT,
                filterQueryBasedOnPackageNameAndUserId,
                MEDIA_GRANTS_TABLE,
                FILE_ID_COLUMN,
                MEDIA_GRANTS_TABLE,
                packageSelection,
                PACKAGE_USER_ID_COLUMN,
                userId,
                CURRENT_GRANTS_TABLE);

        return String.format(
                Locale.ROOT,
                "%s LEFT JOIN %s"
                        + " ON %s.%s = %s.%s ",
                table,
                filteredMediaGrantsTable,
                table,
                KEY_LOCAL_ID,
                CURRENT_GRANTS_TABLE,
                MediaGrants.FILE_ID_COLUMN
        );
    }

    /**
     * @param queryBuilder Adds SQL query where clause based on the Media query arguments to the
     *                     given query builder.
     * @param table The SQL table that is being used to fetch media metadata.
     * @param localAuthority the authority of the local provider if we should include local media in
     *                       the query response. Otherwise, this is null.
     * @param cloudAuthority The authority of the cloud provider if we should include cloud media in
     *                      the query response. Otherwise, this is null.
     * @param reverseOrder by default, the sort order of the media query is
     *                     (Date taken DESC, Picker ID DESC). But for some queries we want to query
     *                     media in the reverse sort order i.e. (Date taken ASC, Picker id ASC).
     *                     This is true when the query is running in the reverse sort order.
     */
    public void addWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority,
            boolean reverseOrder
    ) {
        if (mShouldDedupe) {
            queryBuilder.appendWhereStandalone(
                    prependTableName(table, KEY_IS_VISIBLE) + " = 1");
        }

        if (cloudAuthority == null) {
            queryBuilder.appendWhereStandalone(
                    prependTableName(table, KEY_CLOUD_ID) + " IS NULL");
        }

        if (localAuthority == null) {
            queryBuilder.appendWhereStandalone(
                    prependTableName(table, KEY_CLOUD_ID) + " IS NOT NULL");
        }

        addMimeTypeClause(queryBuilder, table);
        addDateTakenClause(queryBuilder, table, reverseOrder);
    }

    /**
     * Adds the date taken clause to the given query builder.
     *
     * @param queryBuilder SelectSQLiteQueryBuilder to add the where clause
     * @param table The SQL table that is being used to fetch media metadata.
     * @param reverseOrder Since the media results are sorted by (Date taken DESC, Picker ID DESC),
     *                     this field is true when the query is made in the reverse order of the
     *                     expected sort order i.e. (Date taken ASC, Picker ID ASC),
     *                     and is false otherwise.
     */
    private void addDateTakenClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull PickerSQLConstants.Table table,
            boolean reverseOrder
    ) {
        if (reverseOrder) {
            queryBuilder.appendWhereStandalone(
                    String.format(Locale.ROOT,
                            "%s > %s OR (%s = %s AND %s > %s)",
                            prependTableName(table, KEY_DATE_TAKEN_MS), mDateTakenMs,
                            prependTableName(table, KEY_DATE_TAKEN_MS), mDateTakenMs,
                            prependTableName(table, KEY_ID), mPickerId));
        } else {
            queryBuilder.appendWhereStandalone(
                    String.format(Locale.ROOT,
                            "%s < %s OR (%s = %s AND %s <= %s)",
                            prependTableName(table, KEY_DATE_TAKEN_MS), mDateTakenMs,
                            prependTableName(table, KEY_DATE_TAKEN_MS), mDateTakenMs,
                            prependTableName(table, KEY_ID), mPickerId));
        }
    }

    /**
     * Adds the mime type filter clause(s) to the given query builder.
     *
     * @param queryBuilder SelectSQLiteQueryBuilder to add the where clause.
     */
    private void addMimeTypeClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull PickerSQLConstants.Table table) {
        if (mMimeTypes == null || mMimeTypes.isEmpty()) {
            return;
        }

        List<String> whereClauses = new ArrayList<>();
        for (String mimeType : mMimeTypes) {
            if (!TextUtils.isEmpty(mimeType)) {
                whereClauses.add(
                        String.format(
                                Locale.ROOT,
                                "%s LIKE '%s'",
                                prependTableName(table, KEY_MIME_TYPE),
                                mimeType.replace(/* oldChar */ '*', /* newChar */ '%')
                        )
                );
            }
        }
        queryBuilder.appendWhereStandalone(
                String.format(
                        Locale.ROOT,
                        " ( %s ) ",
                        TextUtils.join(" OR ", whereClauses)
                )
        );
    }
}
