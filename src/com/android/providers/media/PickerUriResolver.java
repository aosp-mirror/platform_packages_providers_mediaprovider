/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.photopicker.data.model.UserId;

import java.io.FileNotFoundException;

/**
 * Utility class for Picker Uris, it handles (includes permission checks, incoming args
 * validations etc) and redirects picker URIs to the correct resolver.
 */
public class PickerUriResolver {
    private Context mContext;

    /** A uri with prefix "content://media/picker" is considered as a picker uri */
    public static final @NonNull Uri URI_PREFIX = MediaStore.AUTHORITY_URI.buildUpon().
            appendPath("picker").build();

    PickerUriResolver(Context context) {
        mContext = context;
    }

    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal,
            int callingPid, int callingUid) throws FileNotFoundException {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw new SecurityException("PhotoPicker Uris can only be accessed to read."
                    + " Uri: " + uri);
        }
        if (mContext.checkUriPermission(uri, callingPid, callingUid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION) != PERMISSION_GRANTED) {
            throw new SecurityException("Calling uid ( " + callingUid + " ) does not have "
                    + "permission to access picker uri: " + uri);
        }
        final UserId userId = getUserId(uri);
        final ContentResolver resolver = userId.getContentResolver(mContext);
        final long token = Binder.clearCallingIdentity();
        try {
            return resolver.openFile(getRedactedFilesUriFromPickerUri(uri, resolver), "r", signal);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts,
            CancellationSignal signal, int callingPid, int callingUid)
            throws FileNotFoundException{
        if (mContext.checkUriPermission(uri, callingPid, callingUid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION) != PERMISSION_GRANTED) {
            throw new SecurityException("Calling uid ( " + callingUid + " ) does not have "
                    + "permission to access picker uri: " + uri);
        }
        final UserId userId = getUserId(uri);
        final ContentResolver resolver = userId.getContentResolver(mContext);
        final long token = Binder.clearCallingIdentity();
        try {
            return resolver.openTypedAssetFile(getRedactedFilesUriFromPickerUri(uri, resolver),
                    mimeTypeFilter, opts, signal);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

    }

    /**
     * @return {@link MediaStore.Files} Uri that always redacts sensitive data
     */
    private static Uri getRedactedFilesUriFromPickerUri(Uri uri, ContentResolver contentResolver) {
        // content://media/picker/<user-id>/<media-id>
        final long id = Long.parseLong(uri.getPathSegments().get(2));
        final Uri res = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id);
        return getRedactedUri(contentResolver, res);
    }

    private static Uri getRedactedUri(ContentResolver contentResolver, Uri uri) {
        if (SdkLevel.isAtLeastS()) {
            return getRedactedUriFromMediaStoreAPI(contentResolver, uri);
        } else {
            // TODO (b/168783994): directly call redacted uri code logic or explore other solution.
            // Devices running on Android R cannot call getRedacted() as the API is added in
            // Android S.
            return uri;
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static Uri getRedactedUriFromMediaStoreAPI(ContentResolver contentResolver, Uri uri) {
        return MediaStore.getRedactedUri(contentResolver, uri);
    }

    private static UserId getUserId(Uri uri) {
        // content://media/picker/<user-id>/<media-id>
        final int user = Integer.parseInt(uri.getPathSegments().get(1));
        return UserId.of(UserHandle.of(user));
    }
}
