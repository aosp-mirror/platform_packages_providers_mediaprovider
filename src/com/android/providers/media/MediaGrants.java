/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.providers.media;

import static android.provider.MediaStore.MediaColumns.DATA;

import static com.android.providers.media.LocalUriMatcher.PICKER_ID;
import static com.android.providers.media.util.DatabaseUtils.replaceMatchAnyChar;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.providers.media.photopicker.PickerSyncController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Manager class for the {@code media_grants} table in the {@link
 * DatabaseHelper#EXTERNAL_DATABASE_NAME} database file.
 *
 * <p>Manages media grants for files in the {@code files} table based on package name.
 */
public class MediaGrants {
    public static final String TAG = "MediaGrants";
    public static final String MEDIA_GRANTS_TABLE = "media_grants";
    public static final String FILE_ID_COLUMN = "file_id";
    public static final String PACKAGE_USER_ID_COLUMN = "package_user_id";
    public static final String OWNER_PACKAGE_NAME_COLUMN =
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME;

    private static final String CREATE_TEMPORARY_TABLE_QUERY = "CREATE TEMPORARY TABLE ";
    private static final String MEDIA_GRANTS_AND_FILES_JOIN_TABLE_NAME = "media_grants LEFT JOIN "
            + "files ON media_grants.file_id = files._id";

    private static final String WHERE_MEDIA_GRANTS_PACKAGE_NAME_IN =
            "media_grants." + MediaGrants.OWNER_PACKAGE_NAME_COLUMN + " IN ";

    private static final String WHERE_MEDIA_GRANTS_USER_ID =
            "media_grants." + MediaGrants.PACKAGE_USER_ID_COLUMN + " = ? ";

    private static final String WHERE_ITEM_IS_NOT_TRASHED =
            "files." + MediaStore.Files.FileColumns.IS_TRASHED + " = ? ";

    private static final String WHERE_ITEM_IS_NOT_PENDING =
            "files." + MediaStore.Files.FileColumns.IS_PENDING + " = ? ";

    private static final String WHERE_MEDIA_TYPE =
            "files." + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ";

    private static final String WHERE_MIME_TYPE =
            "files." + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE ? ";

    private static final String WHERE_VOLUME_NAME_IN =
            "files." + MediaStore.Files.FileColumns.VOLUME_NAME + " IN ";

    private static final String TEMP_TABLE_NAME_FOR_DELETION =
            "temp_table_for_media_grants_deletion";

    private static final String TEMP_TABLE_FOR_DELETION_FILE_ID_COLUMN_NAME =
            "temp_table_for_media_grants_deletion.file_id";

    private static final String ARG_VALUE_FOR_FALSE = "0";

    private static final int VISUAL_MEDIA_TYPE_COUNT = 2;
    private SQLiteQueryBuilder mQueryBuilder = new SQLiteQueryBuilder();
    private DatabaseHelper mExternalDatabase;
    private LocalUriMatcher mUriMatcher;

    public MediaGrants(DatabaseHelper externalDatabaseHelper) {
        mExternalDatabase = externalDatabaseHelper;
        mUriMatcher = new LocalUriMatcher(MediaStore.AUTHORITY);
        mQueryBuilder.setTables(MEDIA_GRANTS_TABLE);
    }

    /**
     * Adds media_grants for the provided URIs for the provided package name.
     *
     * @param packageName     the package name that will receive access.
     * @param uris            list of content {@link android.net.Uri} that are recognized by
     *                        mediaprovider.
     * @param packageUserId   the user_id of the package
     */
    void addMediaGrantsForPackage(String packageName, List<Uri> uris, int packageUserId)
            throws IllegalArgumentException {

        Objects.requireNonNull(packageName);
        Objects.requireNonNull(uris);

        mExternalDatabase.runWithTransaction(
                (db) -> {
                    for (Uri uri : uris) {

                        if (!isUriAllowed(uri)) {
                            throw new IllegalArgumentException(
                                    "Illegal Uri, cannot create media grant for malformed uri: "
                                            + uri.toString());
                        }

                        Long id = ContentUris.parseId(uri);
                        final ContentValues values = new ContentValues();
                        values.put(OWNER_PACKAGE_NAME_COLUMN, packageName);
                        values.put(FILE_ID_COLUMN, id);
                        values.put(PACKAGE_USER_ID_COLUMN, packageUserId);

                        try {
                            mQueryBuilder.insert(db, values);
                        } catch (SQLiteConstraintException exception) {
                            // no-op
                            // this may happen due to the presence of a foreign key between the
                            // media_grants and files table. An SQLiteConstraintException
                            // exception my occur if: while inserting the grant for a file, the
                            // file itself is deleted. In this situation no operation is required.
                        }
                    }

                    Log.d(
                            TAG,
                            String.format(
                                    "Successfully added %s media_grants for %s.",
                                    uris.size(), packageName));

                    return null;
                });
    }

    /**
     * Returns the cursor for file data of items for which the passed package has READ_GRANTS.
     *
     * @param packageNames  the package name that has access.
     * @param packageUserId the user_id of the package
     */
    Cursor getMediaGrantsForPackages(String[] packageNames, int packageUserId,
            String[] mimeTypes, String[] availableVolumes)
            throws IllegalArgumentException {
        Objects.requireNonNull(packageNames);
        return mExternalDatabase.runWithoutTransaction((db) -> {
            final SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
            queryBuilder.setDistinct(true);
            queryBuilder.setTables(MEDIA_GRANTS_AND_FILES_JOIN_TABLE_NAME);
            String[] selectionArgs = buildSelectionArg(queryBuilder,
                    QueryFilterBuilder.newInstance()
                            .setPackageNameSelection(packageNames)
                            .setUserIdSelection(packageUserId)
                            .setIsNotTrashedSelection(true)
                            .setIsNotPendingSelection(true)
                            .setIsOnlyVisualMediaType(true)
                            .setMimeTypeSelection(mimeTypes)
                            .setAvailableVolumes(availableVolumes)
                            .build());

            return queryBuilder.query(db,
                    new String[]{DATA, FILE_ID_COLUMN}, null, selectionArgs, null, null, null, null,
                    null);
        });
    }

    int removeMediaGrantsForPackage(@NonNull String[] packages, @NonNull List<Uri> uris,
            int packageUserId) {
        Objects.requireNonNull(packages);
        Objects.requireNonNull(uris);
        if (packages.length == 0) {
            throw new IllegalArgumentException(
                    "Removing grants requires a non empty package name.");
        }

        return mExternalDatabase.runWithTransaction(
                (db) -> {
                    // create a temporary table to be used as a selection criteria for local ids.
                    createTempTableWithLocalIdsAsColumn(uris, db);

                    // Create query builder and add selection args.
                    final SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
                    queryBuilder.setDistinct(true);
                    queryBuilder.setTables(MEDIA_GRANTS_TABLE);
                    String[] selectionArgs = buildSelectionArg(queryBuilder,
                            QueryFilterBuilder.newInstance()
                                    .setPackageNameSelection(packages)
                                    .setUserIdSelection(packageUserId)
                                    .setUriSelection(uris)
                                    .build());
                    // execute query.
                    int grantsRemoved = queryBuilder.delete(db, null, selectionArgs);
                    Log.d(
                            TAG,
                            String.format(
                                    "Removed %s media_grants for %s user for %s.",
                                    grantsRemoved,
                                    String.valueOf(packageUserId),
                                    Arrays.toString(packages)));
                    // Drop the temporary table.
                    deleteTempTableCreatedForLocalIdSelection(db);
                    return grantsRemoved;
                });
    }

    private static void createTempTableWithLocalIdsAsColumn(@NonNull List<Uri> uris,
            @NonNull SQLiteDatabase db) {

        // create a temporary table and insert the ids from received uris.
        db.execSQL(String.format(CREATE_TEMPORARY_TABLE_QUERY + "%s (%s INTEGER)",
                TEMP_TABLE_NAME_FOR_DELETION, FILE_ID_COLUMN));

        final SQLiteQueryBuilder queryBuilderTempTable = new SQLiteQueryBuilder();
        queryBuilderTempTable.setTables(TEMP_TABLE_NAME_FOR_DELETION);

        List<List<Uri>> listOfSelectionArgsForId = splitArrayList(uris,
                /* number of ids per query */ 50);

        StringBuilder sb = new StringBuilder();
        List<Uri> selectionArgForIdSelection;
        for (int itr = 0; itr < listOfSelectionArgsForId.size(); itr++) {
            selectionArgForIdSelection = listOfSelectionArgsForId.get(itr);
            if (itr == 0 || selectionArgForIdSelection.size() != listOfSelectionArgsForId.get(
                    itr - 1).size()) {
                sb.setLength(0);
                for (int i = 0; i < selectionArgForIdSelection.size() - 1; i++) {
                    sb.append("(?)").append(",");
                }
                sb.append("(?)");
            }
            db.execSQL("INSERT INTO " + TEMP_TABLE_NAME_FOR_DELETION + " VALUES " + sb.toString(),
                    selectionArgForIdSelection.stream().map(
                            ContentUris::parseId).collect(Collectors.toList()).stream().toArray());
        }
    }

    private static <T> List<List<T>> splitArrayList(List<T> list, int chunkSize) {
        List<List<T>> subLists = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            subLists.add(list.subList(i, Math.min(i + chunkSize, list.size())));
        }
        return subLists;
    }

    private static void deleteTempTableCreatedForLocalIdSelection(SQLiteDatabase db) {
        db.execSQL("DROP TABLE " + TEMP_TABLE_NAME_FOR_DELETION);
    }

    /**
     * Removes any existing media grants for the given package from the external database. This will
     * not alter the files or file metadata themselves.
     *
     * <p><strong>Note:</strong> Any files that are removed from the system because of any deletion
     * operation or as a result of a package being uninstalled / orphaned will lead to deletion of
     * database entry in files table. Any deletion in files table will automatically delete
     * corresponding media_grants.
     *
     * <p>The action is performed for only specific {@code user}.</p>
     *
     * @param packages      the package(s) name to clear media grants for.
     * @param reason        a logged reason why the grants are being cleared.
     * @param user          the user for which the grants need to be modified.
     *
     * @return              the number of grants removed.
     */
    int removeAllMediaGrantsForPackages(String[] packages, String reason, @NonNull Integer user)
            throws IllegalArgumentException {
        Objects.requireNonNull(packages);
        if (packages.length == 0) {
            throw new IllegalArgumentException(
                    "Removing grants requires a non empty package name.");
        }

        final SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
        queryBuilder.setDistinct(true);
        queryBuilder.setTables(MEDIA_GRANTS_TABLE);
        String[] selectionArgs = buildSelectionArg(queryBuilder, QueryFilterBuilder.newInstance()
                .setPackageNameSelection(packages)
                .setUserIdSelection(user)
                .build());
        return mExternalDatabase.runWithTransaction(
                (db) -> {
                    int grantsRemoved = queryBuilder.delete(db, null, selectionArgs);
                    Log.d(
                            TAG,
                            String.format(
                                    "Removed %s media_grants for %s user for %s. Reason: %s",
                                    grantsRemoved,
                                    String.valueOf(user),
                                    Arrays.toString(packages),
                                    reason));
                    return grantsRemoved;
                });
    }

    /**
     * Removes all existing media grants for all packages from the external database. This will not
     * alter the files or file metadata themselves.
     *
     * @return the number of grants removed.
     */
    int removeAllMediaGrants() {
        return mExternalDatabase.runWithTransaction(
                (db) -> {
                    int grantsRemoved = mQueryBuilder.delete(db, null, null);
                    Log.d(TAG, String.format("Removed %d existing media_grants", grantsRemoved));
                    return grantsRemoved;
                });
    }

    /**
     * Validates an incoming Uri to see if it's a valid media/picker uri that follows the {@link
     * MediaProvider#PICKER_ID scheme}
     *
     * @return If the uri is a valid media/picker uri.
     */
    private boolean isPickerUri(Uri uri) {
        return mUriMatcher.matchUri(uri, /* allowHidden= */ false) == PICKER_ID;
    }

    /**
     * Verifies if a URI is eligible for a media_grant.
     *
     * <p>Currently {@code MediaGrants} requires the file's id to be a local file.
     *
     * <p>This checks if the provided Uri is:
     *
     * <ol>
     *   <li>A Photopicker Uri
     *   <li>That the authority is the local picker authority and not a cloud provider.
     * </ol>
     *
     * <p>
     *
     * @param uri the uri to validate
     * @return is Allowed - true if the given Uri is supported by MediaProvider's media_grants.
     */
    private boolean isUriAllowed(Uri uri) {

        return isPickerUri(uri)
                && PickerUriResolver.unwrapProviderUri(uri)
                .getHost()
                .equals(PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);
    }

    /**
     * Add required selection arguments like comparisons and WHERE checks to the
     * {@link SQLiteQueryBuilder} qb.
     *
     * @param qb           query builder on which the conditions/filters needs to be applied.
     * @param queryFilter  representing the types of selection arguments to be applied.
     * @return array of selection args used to replace placeholders in query builder conditions.
     */
    private String[] buildSelectionArg(SQLiteQueryBuilder qb, MediaGrantsQueryFilter queryFilter) {
        List<String> selectArgs = new ArrayList<>();
        // Append where clause for package names.
        if (queryFilter.mPackageNames != null && queryFilter.mPackageNames.length > 0) {
            // Append the where clause for package name selection to the query builder.
            qb.appendWhereStandalone(
                    WHERE_MEDIA_GRANTS_PACKAGE_NAME_IN + buildPlaceholderForWhereClause(
                            queryFilter.mPackageNames.length));

            // Add package names to selection args.
            selectArgs.addAll(Arrays.asList(queryFilter.mPackageNames));
        }

        // Append Where clause for Uris
        if (queryFilter.mUris != null && !queryFilter.mUris.isEmpty()) {
            // Append the where clause for local id selection to the query builder.
            // this query would look like this example query:
            // WHERE EXISTS (SELECT 1 from temp_table_for_media_grants_deletion WHERE
            // temp_table_for_media_grants_deletion.file_id = media_grants.file_id)
            qb.appendWhereStandalone(String.format("EXISTS (SELECT %s from %s WHERE %s = %s)",
                    TEMP_TABLE_FOR_DELETION_FILE_ID_COLUMN_NAME,
                    TEMP_TABLE_NAME_FOR_DELETION,
                    TEMP_TABLE_FOR_DELETION_FILE_ID_COLUMN_NAME,
                    MediaGrants.MEDIA_GRANTS_TABLE + "." + MediaGrants.FILE_ID_COLUMN));
        }

        // Append where clause for userID.
        if (queryFilter.mUserId != null) {
            qb.appendWhereStandalone(WHERE_MEDIA_GRANTS_USER_ID);
            selectArgs.add(String.valueOf(queryFilter.mUserId));
        }

        if (queryFilter.mIsNotTrashed) {
            qb.appendWhereStandalone(WHERE_ITEM_IS_NOT_TRASHED);
            selectArgs.add(ARG_VALUE_FOR_FALSE);
        }

        if (queryFilter.mIsNotPending) {
            qb.appendWhereStandalone(WHERE_ITEM_IS_NOT_PENDING);
            selectArgs.add(ARG_VALUE_FOR_FALSE);
        }

        if (queryFilter.mIsOnlyVisualMediaType) {
            qb.appendWhereStandalone(WHERE_MEDIA_TYPE + buildPlaceholderForWhereClause(
                    VISUAL_MEDIA_TYPE_COUNT));
            selectArgs.add(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE));
            selectArgs.add(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO));
        }

        if (queryFilter.mAvailableVolumes != null && queryFilter.mAvailableVolumes.length > 0) {
            qb.appendWhereStandalone(
                    WHERE_VOLUME_NAME_IN + buildPlaceholderForWhereClause(
                            queryFilter.mAvailableVolumes.length));
            selectArgs.addAll(Arrays.asList(queryFilter.mAvailableVolumes));
        }

        addMimeTypesToQueryBuilderAndSelectionArgs(qb, selectArgs, queryFilter.mMimeTypeSelection);

        return selectArgs.toArray(new String[selectArgs.size()]);
    }

    private void addMimeTypesToQueryBuilderAndSelectionArgs(SQLiteQueryBuilder qb,
            List<String> selectionArgs, String[] mimeTypes) {
        if (mimeTypes == null) {
            return;
        }

        mimeTypes = replaceMatchAnyChar(mimeTypes);
        ArrayList<String> whereMimeTypes = new ArrayList<>();
        for (String mimeType : mimeTypes) {
            if (!TextUtils.isEmpty(mimeType)) {
                whereMimeTypes.add(WHERE_MIME_TYPE);
                selectionArgs.add(mimeType);
            }
        }

        if (whereMimeTypes.isEmpty()) {
            return;
        }
        qb.appendWhereStandalone(TextUtils.join(" OR ", whereMimeTypes));
    }

    private String buildPlaceholderForWhereClause(int numberOfItemsInSelection) {
        StringBuilder placeholder = new StringBuilder("(");
        for (int itr = 0; itr < numberOfItemsInSelection; itr++) {
            placeholder.append("?,");
        }
        placeholder.deleteCharAt(placeholder.length() - 1);
        placeholder.append(")");
        return placeholder.toString();
    }

    static final class MediaGrantsQueryFilter {

        private final List<Uri> mUris;
        private final String[] mPackageNames;
        private final Integer mUserId;

        private final boolean mIsNotTrashed;

        private final boolean mIsNotPending;

        private final boolean mIsOnlyVisualMediaType;
        private final String[] mMimeTypeSelection;

        private final String[] mAvailableVolumes;

        MediaGrantsQueryFilter(QueryFilterBuilder builder) {
            this.mUris = builder.mUris;
            this.mPackageNames = builder.mPackageNames;
            this.mUserId = builder.mUserId;
            this.mIsNotTrashed = builder.mIsNotTrashed;
            this.mIsNotPending = builder.mIsNotPending;
            this.mMimeTypeSelection = builder.mMimeTypeSelection;
            this.mIsOnlyVisualMediaType = builder.mIsOnlyVisualMediaType;
            this.mAvailableVolumes = builder.mAvailableVolumes;
        }
    }

    // Static class Builder
    static class QueryFilterBuilder {

        private List<Uri> mUris;
        private String[] mPackageNames;
        private int mUserId;

        private boolean mIsNotTrashed;

        private boolean mIsNotPending;

        private boolean mIsOnlyVisualMediaType;
        private String[] mMimeTypeSelection;

        private String[] mAvailableVolumes;

        public static QueryFilterBuilder newInstance() {
            return new QueryFilterBuilder();
        }

        private QueryFilterBuilder() {}

        // Setter methods
        public QueryFilterBuilder setUriSelection(List<Uri> uris) {
            this.mUris = uris;
            return this;
        }

        public QueryFilterBuilder setPackageNameSelection(String[] packageNames) {
            this.mPackageNames = packageNames;
            return this;
        }

        public QueryFilterBuilder setUserIdSelection(int userId) {
            this.mUserId = userId;
            return this;
        }

        public QueryFilterBuilder setIsNotTrashedSelection(boolean isNotTrashed) {
            this.mIsNotTrashed = isNotTrashed;
            return this;
        }

        public QueryFilterBuilder setIsNotPendingSelection(boolean isNotPending) {
            this.mIsNotPending = isNotPending;
            return this;
        }

        public QueryFilterBuilder setIsOnlyVisualMediaType(boolean isOnlyVisualMediaType) {
            this.mIsOnlyVisualMediaType = isOnlyVisualMediaType;
            return this;
        }

        public QueryFilterBuilder setMimeTypeSelection(String[] mimeTypeSelection) {
            this.mMimeTypeSelection = mimeTypeSelection;
            return this;
        }

        public QueryFilterBuilder setAvailableVolumes(String[] availableVolumes) {
            this.mAvailableVolumes = availableVolumes;
            return this;
        }

        // build method to deal with outer class
        // to return outer instance
        public MediaGrantsQueryFilter build() {
            return new MediaGrantsQueryFilter(this);
        }
    }
}
