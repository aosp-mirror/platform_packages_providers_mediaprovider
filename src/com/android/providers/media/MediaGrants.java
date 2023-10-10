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

import static com.android.providers.media.LocalUriMatcher.PICKER_ID;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.providers.media.photopicker.PickerSyncController;

import java.util.List;
import java.util.Objects;

/**
 * Manager class for the {@code media_grants} table in the {@link
 * DatabaseHelper#EXTERNAL_DATABASE_NAME} database file.
 *
 * <p>Manages media grants for files in the {@code files} table based on package name.
 */
class MediaGrants {
    public static final String TAG = "MediaGrants";
    public static final String MEDIA_GRANTS_TABLE = "media_grants";
    public static final String FILE_ID_COLUMN = "file_id";
    public static final String PACKAGE_USER_ID_COLUMN = "package_user_id";
    public static final String OWNER_PACKAGE_NAME_COLUMN =
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME;

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

                        mQueryBuilder.insert(db, values);
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
     * @param packageName   the package name to clear media grants for.
     * @param reason        a logged reason why the grants are being cleared.
     * @param user          the user for which the grants need to be modified.
     *
     * @return              the number of grants removed.
     */
    int removeAllMediaGrantsForPackage(String packageName, String reason,
            @NonNull Integer user)
            throws IllegalArgumentException {
        Objects.requireNonNull(packageName);
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException(
                    "Removing grants requires a non empty package name.");
        }
        return mExternalDatabase.runWithTransaction(
                (db) -> {
                    int grantsRemoved =
                            mQueryBuilder.delete(
                                    db, String.format(
                                            "%s = ? AND %s = ?", OWNER_PACKAGE_NAME_COLUMN,
                                            PACKAGE_USER_ID_COLUMN),
                                    new String[]{packageName, String.valueOf(user)});
                    Log.d(TAG,
                            String.format("Removed %s media_grants for %s user for %s. Reason: %s",
                                    grantsRemoved, String.valueOf(user),
                                    packageName,
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
}
