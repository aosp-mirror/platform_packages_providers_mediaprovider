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
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;
import static com.android.providers.media.util.FileUtils.toFuseFile;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.photopicker.data.ExternalDbFacade;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.data.PickerDbFacade;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for Picker Uris, it handles (includes permission checks, incoming args
 * validations etc) and redirects picker URIs to the correct resolver.
 */
public class PickerUriResolver {
    private static final String TAG = "PickerUriResolver";

    private static final String PICKER_SEGMENT = "picker";
    private static final String PICKER_INTERNAL_SEGMENT = "picker_internal";
    /** A uri with prefix "content://media/picker" is considered as a picker uri */
    public static final Uri PICKER_URI = MediaStore.AUTHORITY_URI.buildUpon().
            appendPath(PICKER_SEGMENT).build();
    /**
     * Internal picker URI with prefix "content://media/picker_internal" to retrieve merged
     * and deduped cloud and local items.
     */
    public static final Uri PICKER_INTERNAL_URI = MediaStore.AUTHORITY_URI.buildUpon().
            appendPath(PICKER_INTERNAL_SEGMENT).build();

    public static final String MEDIA_PATH = "media";
    public static final String ALBUM_PATH = "albums";

    private final Context mContext;
    private final PickerDbFacade mDbFacade;

    PickerUriResolver(Context context, PickerDbFacade dbFacade) {
        mContext = context;
        mDbFacade = dbFacade;
    }

    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal,
            int callingPid, int callingUid) throws FileNotFoundException {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw new SecurityException("PhotoPicker Uris can only be accessed to read."
                    + " Uri: " + uri);
        }

        checkUriPermission(uri, callingPid, callingUid);

        final ContentResolver resolver = getContentResolverForUserId(uri);
        if (PickerDbFacade.isPickerDbEnabled()) {
            if (canHandleUriInUser(uri)) {
                return openPickerFile(uri);
            }
            return resolver.openFile(uri, mode, signal);
        }

        final long token = Binder.clearCallingIdentity();
        try {
            uri = getRedactedFileUriFromPickerUri(uri, resolver);
            return resolver.openFile(uri, "r", signal);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts,
            CancellationSignal signal, int callingPid, int callingUid)
            throws FileNotFoundException {
        checkUriPermission(uri, callingPid, callingUid);

        final ContentResolver resolver = getContentResolverForUserId(uri);
        if (PickerDbFacade.isPickerDbEnabled()) {
            if (canHandleUriInUser(uri)) {
                return new AssetFileDescriptor(openPickerFile(uri), 0,
                        AssetFileDescriptor.UNKNOWN_LENGTH);
            }
            return resolver.openTypedAssetFile(uri, mimeTypeFilter, opts, signal);
        }

        final long token = Binder.clearCallingIdentity();
        try {
            uri = getRedactedFileUriFromPickerUri(uri, resolver);
            return resolver.openTypedAssetFile(uri, mimeTypeFilter, opts, signal);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public Cursor query(Uri uri, String[] projection, Bundle queryArgs, CancellationSignal signal,
            int callingPid, int callingUid) {
        checkUriPermission(uri, callingPid, callingUid);

        try {
            return queryInternal(uri, projection, queryArgs, signal);
        } catch (FileNotFoundException e) {
            Log.d(TAG, "File not found for uri: " + uri, e);
            return new MatrixCursor(projection == null ? new String[] {} : projection);
        }
    }

    // TODO(b/191362529): Restrict projection values when we start querying picker db.
    // Add PickerColumns and add checks for projection.
    private Cursor queryInternal(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal signal) throws FileNotFoundException {
        final ContentResolver resolver = getContentResolverForUserId(uri);

        if (PickerDbFacade.isPickerDbEnabled()) {
            if (canHandleUriInUser(uri)) {
                return queryPickerUri(uri);
            }
            return resolver.query(uri, /* projection */ null, /* queryArgs */ null,
                    /* cancellationSignal */ null);
        }

        final long token = Binder.clearCallingIdentity();
        try {
            // Support query similar to as we support for redacted mediastore file uris.
            return resolver.query(getRedactedFileUriFromPickerUri(uri, resolver), projection,
                    queryArgs, signal);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public String getType(@NonNull Uri uri) {
        // There's no permission check because ContentProviders allow anyone to check the mimetype
        // of a URI

        try (Cursor cursor = queryInternal(uri, new String[]{MediaStore.MediaColumns.MIME_TYPE},
                        /* queryArgs */ null, /* signal */ null)) {
            if (cursor != null && cursor.getCount() == 1 && cursor.moveToFirst()) {
                return getCursorString(cursor,
                        CloudMediaProviderContract.MediaColumns.MIME_TYPE);
            }
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException(e.getMessage());
        }

        throw new IllegalArgumentException("Failed to getType for uri: " + uri);
    }

    public static Uri getMediaUri(String authority) {
        return Uri.parse("content://" + authority + "/"
                + CloudMediaProviderContract.URI_PATH_MEDIA);
    }

    public static Uri getDeletedMediaUri(String authority) {
        return Uri.parse("content://" + authority + "/"
                + CloudMediaProviderContract.URI_PATH_DELETED_MEDIA);
    }

    public static Uri getMediaInfoUri(String authority) {
        return Uri.parse("content://" + authority + "/"
                + CloudMediaProviderContract.URI_PATH_MEDIA_INFO);
    }

    public static Uri getAccountInfoUri(String authority) {
        return Uri.parse("content://" + authority + "/"
                + CloudMediaProviderContract.URI_PATH_ACCOUNT_INFO);
    }

    public static Uri getAlbumUri(String authority) {
        return Uri.parse("content://" + authority + "/"
                + CloudMediaProviderContract.URI_PATH_ALBUM);
    }

    private ParcelFileDescriptor openPickerFile(Uri uri) throws FileNotFoundException {
        final File file = getPickerFileFromUri(uri);
        if (file == null) {
            throw new FileNotFoundException("File not found for uri: " + uri);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @VisibleForTesting
    File getPickerFileFromUri(Uri uri) {
        try (Cursor cursor = queryPickerUri(uri)) {
            if (cursor != null && cursor.getCount() == 1 && cursor.moveToFirst()) {
                String path = getCursorString(cursor, CloudMediaProviderContract.MediaColumns.DATA);
                return toFuseFile(new File(path));
            }
        }
        return null;
    }

    @VisibleForTesting
    Cursor queryPickerUri(Uri uri) {
        uri = unwrapProviderUri(uri);
        return mDbFacade.queryMediaId(uri.getHost(), uri.getLastPathSegment());
    }

    public static Uri wrapProviderUri(Uri uri, int userId) {
        final List<String> segments = uri.getPathSegments();
        if (segments.size() != 2) {
            throw new IllegalArgumentException("Unexpected provider URI: " + uri);
        }

        Uri.Builder builder = initializeUriBuilder(MediaStore.AUTHORITY);
        builder.appendPath(PICKER_SEGMENT);
        builder.appendPath(String.valueOf(userId));
        builder.appendPath(uri.getHost());

        for (int i = 0; i < segments.size(); i++) {
            builder.appendPath(segments.get(i));
        }

        return builder.build();
    }

    @VisibleForTesting
    static Uri unwrapProviderUri(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 5) {
            throw new IllegalArgumentException("Unexpected picker provider URI: " + uri);
        }

        // segments.get(0) == 'picker'
        final String userId = segments.get(1);
        final String host = segments.get(2);
        segments = segments.subList(3, segments.size());

        Uri.Builder builder = initializeUriBuilder(userId + "@" + host);

        for (int i = 0; i < segments.size(); i++) {
            builder.appendPath(segments.get(i));
        }
        return builder.build();
    }

    private static Uri.Builder initializeUriBuilder(String authority) {
        final Uri.Builder builder = Uri.EMPTY.buildUpon();
        builder.scheme("content");
        builder.encodedAuthority(authority);

        return builder;
    }

    /**
     * @return {@link MediaStore.Files} Uri that always redacts sensitive data
     */
    private Uri getRedactedFileUriFromPickerUri(Uri uri, ContentResolver contentResolver) {
        // content://media/picker/<user-id>/<media-id>
        final long id = Long.parseLong(uri.getPathSegments().get(2));
        final Uri res = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id);
        return getRedactedUri(contentResolver, res);
    }

    @VisibleForTesting
    Uri getRedactedUri(ContentResolver contentResolver, Uri uri) {
        if (SdkLevel.isAtLeastS()) {
            return getRedactedUriFromMediaStoreAPI(contentResolver, uri);
        } else {
            // TODO (b/201994830): directly call redacted uri code logic or explore other solution.
            // Devices running on Android R cannot call getRedacted() as the API is added in
            // Android S.
            return uri;
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static Uri getRedactedUriFromMediaStoreAPI(ContentResolver contentResolver, Uri uri) {
        return MediaStore.getRedactedUri(contentResolver, uri);
    }

    @VisibleForTesting
    static int getUserId(Uri uri) {
        // content://media/picker/<user-id>/<media-id>/...
        return Integer.parseInt(uri.getPathSegments().get(1));
    }

    private void checkUriPermission(Uri uri, int pid, int uid) {
        if (!isSelf(uid) && mContext.checkUriPermission(uri, pid, uid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION) != PERMISSION_GRANTED) {
            throw new SecurityException("Calling uid ( " + uid + " ) does not have permission to " +
                    "access picker uri: " + uri);
        }
    }

    private boolean isSelf(int uid) {
        return UserHandle.getAppId(android.os.Process.myUid()) == UserHandle.getAppId(uid);
    }

    private boolean canHandleUriInUser(Uri uri) {
        // If MPs user_id matches the URIs user_id, we can handle this URI in this MP user,
        // otherwise, we'd have to re-route to MP matching URI user_id
        return getUserId(uri) == mContext.getUser().getIdentifier();
    }

    @VisibleForTesting
    ContentResolver getContentResolverForUserId(Uri uri) throws FileNotFoundException {
        final UserId userId = UserId.of(UserHandle.of(getUserId(uri)));
        try {
            return userId.getContentResolver(mContext);
        } catch (NameNotFoundException e) {
            throw new FileNotFoundException("File not found due to unavailable content resolver "
                    + "for uri: " + uri + " ; error: " + e);
        }
    }
}
