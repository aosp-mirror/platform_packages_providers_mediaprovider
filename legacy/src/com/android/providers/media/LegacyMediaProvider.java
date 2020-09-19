/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.providers.media.DatabaseHelper.EXTERNAL_DATABASE_NAME;
import static com.android.providers.media.DatabaseHelper.INTERNAL_DATABASE_NAME;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.util.ArraySet;

import androidx.annotation.NonNull;

import com.android.providers.media.util.Logging;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * Very limited subset of {@link MediaProvider} which only surfaces
 * {@link android.provider.MediaStore.Files} data.
 */
public class LegacyMediaProvider extends ContentProvider {
    private DatabaseHelper mInternalDatabase;
    private DatabaseHelper mExternalDatabase;

    public static final String START_LEGACY_MIGRATION_CALL = "start_legacy_migration";
    public static final String FINISH_LEGACY_MIGRATION_CALL = "finish_legacy_migration";

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        // Sanity check our setup
        if (!info.exported) {
            throw new SecurityException("Provider must be exported");
        }
        if (!android.Manifest.permission.WRITE_MEDIA_STORAGE.equals(info.readPermission)
                || !android.Manifest.permission.WRITE_MEDIA_STORAGE.equals(info.writePermission)) {
            throw new SecurityException("Provider must be protected by WRITE_MEDIA_STORAGE");
        }

        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        final Context context = getContext();

        final File persistentDir = context.getDir("logs", Context.MODE_PRIVATE);
        Logging.initPersistent(persistentDir);

        mInternalDatabase = new DatabaseHelper(context, INTERNAL_DATABASE_NAME,
                true, false, true, null, null, null, null, null);
        mExternalDatabase = new DatabaseHelper(context, EXTERNAL_DATABASE_NAME,
                false, false, true, null, null, null, null, null);

        return true;
    }

    private @NonNull DatabaseHelper getDatabaseForUri(Uri uri) {
        final String volumeName = MediaStore.getVolumeName(uri);
        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL:
                return Objects.requireNonNull(mInternalDatabase, "Missing internal database");
            default:
                return Objects.requireNonNull(mExternalDatabase, "Missing external database");
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        final String appendedSelection = getAppendedSelection(selection, uri);
        final DatabaseHelper helper = getDatabaseForUri(uri);
        return helper.runWithoutTransaction((db) -> {
            return db.query(getTableName(uri), projection, appendedSelection, selectionArgs,
                    null, null, sortOrder);
        });
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
                throws OperationApplicationException {
        // Open transactions on databases for requested volumes
        final Set<DatabaseHelper> transactions = new ArraySet<>();
        try {
            for (ContentProviderOperation op : operations) {
                final DatabaseHelper helper = getDatabaseForUri(op.getUri());
                if (!transactions.contains(helper)) {
                    helper.beginTransaction();
                    transactions.add(helper);
                }
            }

            final ContentProviderResult[] result = super.applyBatch(operations);
            for (DatabaseHelper helper : transactions) {
                helper.setTransactionSuccessful();
            }
            return result;
        } finally {
            for (DatabaseHelper helper : transactions) {
                helper.endTransaction();
            }
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!uri.getBooleanQueryParameter("silent", false)) {
            try {
                final File file = new File(values.getAsString(MediaColumns.DATA));
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        final DatabaseHelper helper = getDatabaseForUri(uri);
        final long id = helper.runWithTransaction((db) -> {
            return db.insert(getTableName(uri), null, values);
        });
        return ContentUris.withAppendedId(uri, id);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private static final int AUDIO_PLAYLISTS_ID_MEMBERS = 112;
    private static final int FILES_ID = 701;
    private static final UriMatcher BASIC_URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        final UriMatcher basicUriMatcher = BASIC_URI_MATCHER;
        basicUriMatcher.addURI(MediaStore.AUTHORITY_LEGACY, "*/audio/playlists/#/members",
                AUDIO_PLAYLISTS_ID_MEMBERS);
        basicUriMatcher.addURI(MediaStore.AUTHORITY_LEGACY, "*/file/#", FILES_ID);
    };

    private static String getAppendedSelection(String selection, Uri uri) {
        String whereClause = "";
        final int match = BASIC_URI_MATCHER.match(uri);
        switch (match) {
            case AUDIO_PLAYLISTS_ID_MEMBERS:
                whereClause = "playlist_id=" + uri.getPathSegments().get(3);
                break;
            case FILES_ID:
                whereClause = "_id=" + uri.getPathSegments().get(2);
                break;
            default:
                // No additional whereClause required
        }
        if (selection == null || selection.isEmpty()) {
            return whereClause;
        } else if (whereClause.isEmpty()) {
            return selection;
        } else {
            return  whereClause + " AND " + selection;
        }
    }

    private static String getTableName(Uri uri) {
        final int playlistMatch = BASIC_URI_MATCHER.match(uri);
        if (playlistMatch == AUDIO_PLAYLISTS_ID_MEMBERS) {
            return "audio_playlists_map";
        } else {
            // Return the "files" table by default for all other Uris.
            return "files";
        }
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        switch (method) {
            case START_LEGACY_MIGRATION_CALL: {
                // Nice to know, but nothing actionable
                break;
            }
            case FINISH_LEGACY_MIGRATION_CALL: {
                // We're only going to hear this once, since we've either
                // successfully migrated legacy data, or we're never going to
                // try again, so it's time to clean things up
                final String volumeName = arg;
                switch (volumeName) {
                    case MediaStore.VOLUME_INTERNAL: {
                        mInternalDatabase.close();
                        getContext().deleteDatabase(INTERNAL_DATABASE_NAME);
                        break;
                    }
                    default: {
                        mExternalDatabase.close();
                        getContext().deleteDatabase(EXTERNAL_DATABASE_NAME);
                        break;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        Logging.dumpPersistent(writer);
    }
}
