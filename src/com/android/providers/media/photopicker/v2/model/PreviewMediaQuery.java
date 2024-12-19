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
import static com.android.providers.media.MediaGrants.OWNER_PACKAGE_NAME_COLUMN;
import static com.android.providers.media.MediaGrants.PACKAGE_USER_ID_COLUMN;
import static com.android.providers.media.photopicker.PickerSyncController.getPackageNameFromUid;
import static com.android.providers.media.photopicker.PickerSyncController.uidToUserId;
import static com.android.providers.media.photopicker.data.PickerDbFacade.KEY_LOCAL_ID;
import static com.android.providers.media.photopicker.v2.PickerDataLayerV2.CURRENT_DE_SELECTIONS_TABLE;
import static com.android.providers.media.photopicker.v2.PickerDataLayerV2.CURRENT_GRANTS_TABLE;
import static com.android.providers.media.photopicker.v2.PickerDataLayerV2.DE_SELECTIONS_TABLE;
import static com.android.providers.media.photopicker.v2.PickerDataLayerV2.addWhereClausesForPackageAndUserIdSelection;
import static com.android.providers.media.photopicker.v2.PickerDataLayerV2.getPackageSelectionWhereClause;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.MediaGrants;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SelectSQLiteQueryBuilder;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/**
 * This is a convenience class for Preview media content related SQL queries performed on the Picker
 * Database.
 */
public class PreviewMediaQuery extends MediaQuery {
    private final ArrayList<String> mCurrentSelection;
    private final ArrayList<String> mCurrentDeSelection;

    public PreviewMediaQuery(
            @NonNull Bundle queryArgs) {
        super(queryArgs);

        // This is not required for preview.
        mShouldPopulateItemsBeforeCount = false;
        mCurrentSelection = queryArgs.getStringArrayList("current_selection");
        mCurrentDeSelection = queryArgs.getStringArrayList("current_de_selection");
    }

    public ArrayList<String> getCurrentSelection() {
        return mCurrentSelection;
    }

    public ArrayList<String> getCurrentDeSelection() {
        return mCurrentDeSelection;
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

        addIdSelectionClause(queryBuilder);
    }

    private void addIdSelectionClause(@NonNull SelectSQLiteQueryBuilder queryBuilder) {
        StringBuilder idSelectionPlaceholder = new StringBuilder();
        if (mCurrentSelection != null && !mCurrentSelection.isEmpty()) {
            idSelectionPlaceholder.append("local_id IN  (");
            String joinedIds = String.join(",", mCurrentSelection);
            idSelectionPlaceholder.append(joinedIds);
            idSelectionPlaceholder.append(")");
        }

        if (!idSelectionPlaceholder.toString().isEmpty()) {
            idSelectionPlaceholder.append(" OR ");
        }

        idSelectionPlaceholder.append(
                String.format(
                        Locale.ROOT,
                        "(%s.%s IS NOT NULL AND %s.%s IS NULL)",
                        // current_media_grants.file_id IS NOT NULL
                        CURRENT_GRANTS_TABLE, MediaGrants.FILE_ID_COLUMN,
                        // current_de_selections.file_id IS NULL
                        CURRENT_DE_SELECTIONS_TABLE, MediaGrants.FILE_ID_COLUMN));
        queryBuilder.appendWhereStandalone(idSelectionPlaceholder.toString());
    }

    /**
     * Returns the table that should be used in the query operations including any joins that are
     * required with other tables in the database.
     */
    @Override
    public String getTableWithRequiredJoins(String table,
            @NonNull Context appContext, int callingPackageUid, String intentAction) {
        Objects.requireNonNull(appContext);
        if (callingPackageUid == -1) {
            throw new IllegalArgumentException("Calling package uid in"
                    + "ACTION_USER_SELECT_IMAGES_FOR_APP mode should not be -1. Invalid UID");
        }
        int userId = uidToUserId(callingPackageUid);
        String[] packageNames = getPackageNameFromUid(appContext,
                callingPackageUid);
        Objects.requireNonNull(packageNames);

        // The following joins for the table is performed for the preview request.
        // Media items needs to be filtered based on:
        // 1. if they are selected by the user in the current session
        // 2. if they have been pre-granted i.e. their grant is in media_grants table AND they have
        //     not be de-selected by the user in the current session i.e. the item is not part of
        //     the de_selections table.
        // To find such a union of items, the current selection mentioned in point one can be
        // handled with a where clause on media table itself but for point 2 to be satisfied the
        // media table needs to be joined with media_grants and de_selections tables.
        // These tables can contain data for multiple apps hence they need to be separately
        // filtered with the help of a sub-query based on the current calling package name and
        // userId.

        String filterQueryBasedOnPackageNameAndUserId = "(SELECT %s.%s FROM %s "
                + "WHERE "
                + " %s AND "
                + "%s = %d) "
                + "AS %s";

        String filteredMediaGrantsTable = String.format(Locale.ROOT,
                filterQueryBasedOnPackageNameAndUserId,
                MEDIA_GRANTS_TABLE,
                FILE_ID_COLUMN,
                MEDIA_GRANTS_TABLE,
                getPackageSelectionWhereClause(packageNames, MEDIA_GRANTS_TABLE),
                PACKAGE_USER_ID_COLUMN,
                userId,
                CURRENT_GRANTS_TABLE);

        String filteredDeSelectionsTable = String.format(Locale.ROOT,
                filterQueryBasedOnPackageNameAndUserId,
                DE_SELECTIONS_TABLE,
                FILE_ID_COLUMN,
                DE_SELECTIONS_TABLE,
                getPackageSelectionWhereClause(packageNames, DE_SELECTIONS_TABLE),
                PACKAGE_USER_ID_COLUMN,
                userId,
                CURRENT_DE_SELECTIONS_TABLE);

        return String.format(
                Locale.ROOT,
                "%s LEFT JOIN %s"
                        + " ON %s.%s = %s.%s "
                        + "LEFT JOIN %s"
                        + " ON %s.%s = %s.%s",
                table,
                filteredMediaGrantsTable,
                table,
                KEY_LOCAL_ID,
                CURRENT_GRANTS_TABLE,
                MediaGrants.FILE_ID_COLUMN,
                filteredDeSelectionsTable,
                table,
                KEY_LOCAL_ID,
                CURRENT_DE_SELECTIONS_TABLE,
                FILE_ID_COLUMN
        );
    }

    /**
     * Insert ids in 'de_selection' table in the picker.db to be used for exclusions in the query
     * operation.
     */
    public static void insertDeSelections(
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            int callingUid,
            @NonNull ArrayList<String> currentDeSelection
    ) {

        final SQLiteDatabase database = syncController.getDbFacade().getDatabase();
        try {
            database.beginTransaction();
            SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables(DE_SELECTIONS_TABLE);
            String[] ownerPackageName = getPackageNameFromUid(appContext,
                    callingUid);
            int userId = uidToUserId(callingUid);
            addWhereClausesForPackageAndUserIdSelection(userId, ownerPackageName,
                    DE_SELECTIONS_TABLE, qb);
            qb.delete(database, null, null);

            qb = new SQLiteQueryBuilder();
            qb.setTables(DE_SELECTIONS_TABLE);

            if (!currentDeSelection.isEmpty()) {
                ContentValues cv = new ContentValues();
                for (int i = 0; i < currentDeSelection.size(); i++) {
                    cv.clear();
                    cv.put(FILE_ID_COLUMN, currentDeSelection.get(i));
                    cv.put(OWNER_PACKAGE_NAME_COLUMN, ownerPackageName[0]);
                    cv.put(PACKAGE_USER_ID_COLUMN, userId);
                    qb.insert(database, cv);
                }
            }

            if (database.inTransaction()) {
                database.setTransactionSuccessful();
            }
        } finally {
            if (database.inTransaction()) {
                database.endTransaction();
            }
        }
    }
}
