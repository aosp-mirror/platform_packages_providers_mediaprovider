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
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;

/**
 * Very limited subset of {@link MediaProvider} which only surfaces
 * {@link android.provider.MediaStore.Files} data.
 */
public class LegacyMediaProvider extends ContentProvider {
    private DatabaseHelper mInternalDatabase;
    private DatabaseHelper mExternalDatabase;

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

        mInternalDatabase = new DatabaseHelper(context, INTERNAL_DATABASE_NAME,
                true, false, true, null, null);
        mExternalDatabase = new DatabaseHelper(context, EXTERNAL_DATABASE_NAME,
                false, false, true, null, null);

        return true;
    }

    private @NonNull DatabaseHelper getDatabaseForUri(Uri uri) {
        final String volumeName = MediaStore.getVolumeName(uri);
        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL:
                return mInternalDatabase;
            default:
                return mExternalDatabase;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        final DatabaseHelper helper = getDatabaseForUri(uri);
        return helper.getReadableDatabase().query("files", projection, selection, selectionArgs,
                null, null, sortOrder);
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        try {
            new File(values.getAsString(MediaColumns.DATA)).createNewFile();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        final DatabaseHelper helper = getDatabaseForUri(uri);
        final long id = helper.getWritableDatabase().insert("files", null, values);
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
}
