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
import static android.os.Process.SYSTEM_UID;

import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;
import static com.android.providers.media.util.FileUtils.toFuseFile;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.metrics.PhotoPickerUiEventLogger;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Set;

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

    public static final String LOCAL_PATH = "local";
    public static final String ALL_PATH = "all";

    private final Context mContext;
    private final PickerDbFacade mDbFacade;
    private final Set<String> mAllValidProjectionColumns;
    private final String[] mAllValidProjectionColumnsArray;

    PickerUriResolver(Context context, PickerDbFacade dbFacade, ProjectionHelper projectionHelper) {
        mContext = context;
        mDbFacade = dbFacade;
        mAllValidProjectionColumns = projectionHelper.getProjectionMap(
                MediaStore.PickerMediaColumns.class).keySet();
        mAllValidProjectionColumnsArray = mAllValidProjectionColumns.toArray(new String[0]);
    }

    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal,
            int callingPid, int callingUid) throws FileNotFoundException {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw new SecurityException("PhotoPicker Uris can only be accessed to read."
                    + " Uri: " + uri);
        }

        checkUriPermission(uri, callingPid, callingUid);

        final ContentResolver resolver;
        try {
            resolver = getContentResolverForUserId(uri);
        } catch (IllegalStateException e) {
            // This is to be consistent with MediaProvider's response when a file is not found.
            Log.e(TAG, "No item at " + uri, e);
            throw new FileNotFoundException("No item at " + uri);
        }
        if (canHandleUriInUser(uri)) {
            return openPickerFile(uri);
        }
        return resolver.openFile(uri, mode, signal);
    }

    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts,
            CancellationSignal signal, int callingPid, int callingUid)
            throws FileNotFoundException {
        checkUriPermission(uri, callingPid, callingUid);

        final ContentResolver resolver;
        try {
            resolver = getContentResolverForUserId(uri);
        } catch (IllegalStateException e) {
            // This is to be consistent with MediaProvider's response when a file is not found.
            Log.e(TAG, "No item at " + uri, e);
            throw new FileNotFoundException("No item at " + uri);
        }
        if (canHandleUriInUser(uri)) {
            return new AssetFileDescriptor(openPickerFile(uri), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH);
        }
        return resolver.openTypedAssetFile(uri, mimeTypeFilter, opts, signal);
    }

    public Cursor query(Uri uri, String[] projection, int callingPid, int callingUid,
            String callingPackageName) {
        checkUriPermission(uri, callingPid, callingUid);
        try {
            logUnknownProjectionColumns(projection, callingUid, callingPackageName);
            return queryInternal(uri, projection);
        } catch (IllegalStateException e) {
            // This is to be consistent with MediaProvider, it returns an empty cursor if the row
            // does not exist.
            Log.e(TAG, "File not found for uri: " + uri, e);
            return new MatrixCursor(projection == null ? new String[] {} : projection);
        }
    }

    private Cursor queryInternal(Uri uri, String[] projection) {
        final ContentResolver resolver = getContentResolverForUserId(uri);

        if (canHandleUriInUser(uri)) {
            if (projection == null || projection.length == 0) {
                projection = mAllValidProjectionColumnsArray;
            }

            return queryPickerUri(uri, projection);
        }
        return resolver.query(uri, projection, /* queryArgs */ null,
                /* cancellationSignal */ null);
    }

    /**
     * getType for Picker Uris
     */
    public String getType(@NonNull Uri uri, int callingPid, int callingUid) {
        // TODO (b/272265676): Remove system uid check if found unnecessary
        if (SdkLevel.isAtLeastU() && UserHandle.getAppId(callingUid) != SYSTEM_UID) {
            // Starting Android 14, there is permission check for getting types requiring query.
            // System Uid (1000) is allowed to get the types.
            checkUriPermission(uri, callingPid, callingUid);
        }

        try (Cursor cursor = queryInternal(uri, new String[]{MediaStore.MediaColumns.MIME_TYPE})) {
            if (cursor != null && cursor.getCount() == 1 && cursor.moveToFirst()) {
                return getCursorString(cursor,
                        CloudMediaProviderContract.MediaColumns.MIME_TYPE);
            }
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

    public static Uri getMediaCollectionInfoUri(String authority) {
        return Uri.parse("content://" + authority + "/"
                + CloudMediaProviderContract.URI_PATH_MEDIA_COLLECTION_INFO);
    }

    public static Uri getAlbumUri(String authority) {
        return Uri.parse("content://" + authority + "/"
                + CloudMediaProviderContract.URI_PATH_ALBUM);
    }

    public static Uri createSurfaceControllerUri(String authority) {
        return Uri.parse("content://" + authority + "/"
                + CloudMediaProviderContract.URI_PATH_SURFACE_CONTROLLER);
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
        final String[] projection = new String[] { MediaStore.PickerMediaColumns.DATA };
        try (Cursor cursor = queryPickerUri(uri, projection)) {
            if (cursor != null && cursor.getCount() == 1 && cursor.moveToFirst()) {
                String path = getCursorString(cursor, MediaStore.PickerMediaColumns.DATA);
                // First replace /sdcard with /storage/emulated path
                path = path.replaceFirst("/sdcard", "/storage/emulated/" + MediaStore.MY_USER_ID);
                // Then convert /storage/emulated patht to /mnt/user/ path
                return toFuseFile(new File(path));
            }
        }
        return null;
    }

    @VisibleForTesting
    Cursor queryPickerUri(Uri uri, String[] projection) {
        uri = unwrapProviderUri(uri);
        return mDbFacade.queryMediaIdForApps(uri.getHost(), uri.getLastPathSegment(),
                projection);
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

    private void logUnknownProjectionColumns(String[] projection, int callingUid,
            String callingPackageName) {
        if (projection == null || callingPackageName.equals(mContext.getPackageName())) {
            return;
        }

        for (String column : projection) {
            if (!mAllValidProjectionColumns.contains(column)) {
                final PhotoPickerUiEventLogger logger = new PhotoPickerUiEventLogger();
                logger.logPickerQueriedWithUnknownColumn(callingUid, callingPackageName);
            }
        }
    }

    @VisibleForTesting
    ContentResolver getContentResolverForUserId(Uri uri) {
        final UserId userId = UserId.of(UserHandle.of(getUserId(uri)));
        try {
            return userId.getContentResolver(mContext);
        } catch (NameNotFoundException e) {
            throw new IllegalStateException("Cannot find content resolver for uri: " + uri, e);
        }
    }
}
