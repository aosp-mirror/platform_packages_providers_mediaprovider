/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media.photopicker.data;

import static android.provider.MediaStore.AUTHORITY;
import static android.provider.MediaStore.MediaColumns.DATA;

import static com.android.providers.media.MediaGrants.FILE_ID_COLUMN;
import static com.android.providers.media.util.FileUtils.getContentUriForPath;

import android.annotation.NonNull;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider grants for items selected in previous sessions.
 */
public final class MediaGrantsProvider {
    private static final String MEDIA_GRANTS_URI_PATH = "content://media/media_grants";
    public static final String EXTRA_MIME_TYPE_SELECTION = "media_grant_mime_type_selection";

    /**
     * Fetches file Uris for items having {@link com.android.providers.media.MediaGrants} for the
     * given package. Returns an empty list if no grants are present.
     *
     * @hide
     */
    @NonNull
    public static List<Uri> fetchReadGrantedItemsUrisForPackage(
            @NonNull Context context, int packageUid, String[] mimeTypes) {
        final ContentResolver resolver = context.getContentResolver();
        try (ContentProviderClient client = resolver.acquireContentProviderClient(AUTHORITY)) {
            assert client != null;
            final Bundle extras = new Bundle();
            extras.putInt(Intent.EXTRA_UID, packageUid);
            extras.putStringArray(EXTRA_MIME_TYPE_SELECTION, mimeTypes);
            final Cursor c = client.query(Uri.parse(MEDIA_GRANTS_URI_PATH),
                    /* projection= */ null,
                    /* queryArgs= */ extras,
                    null);
            List<Uri> filesUriList = new ArrayList<>(0);
            while (c.moveToNext()) {
                final String file_path = c.getString(c.getColumnIndexOrThrow(DATA));
                final Integer file_id = c.getInt(c.getColumnIndexOrThrow(FILE_ID_COLUMN));
                filesUriList.add(getContentUriForPath(
                        file_path).buildUpon().appendPath(String.valueOf(file_id)).build());
            }
            c.close();
            return filesUriList;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }
}
