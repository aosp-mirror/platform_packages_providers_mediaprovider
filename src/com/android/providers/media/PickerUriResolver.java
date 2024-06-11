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
import static android.provider.MediaStore.EXTRA_CALLING_PACKAGE_UID;

import static com.android.providers.media.AccessChecker.isRedactionNeededForPickerUri;
import static com.android.providers.media.LocalUriMatcher.PICKER_GET_CONTENT_ID;
import static com.android.providers.media.LocalUriMatcher.PICKER_ID;
import static com.android.providers.media.LocalUriMatcher.PICKER_INTERNAL_ALBUMS_ALL;
import static com.android.providers.media.LocalUriMatcher.PICKER_INTERNAL_ALBUMS_LOCAL;
import static com.android.providers.media.LocalUriMatcher.PICKER_INTERNAL_MEDIA_ALL;
import static com.android.providers.media.LocalUriMatcher.PICKER_INTERNAL_MEDIA_LOCAL;
import static com.android.providers.media.photopicker.PickerDataLayer.QUERY_CLOUD_ID_SELECTION;
import static com.android.providers.media.photopicker.PickerDataLayer.QUERY_ID_SELECTION;
import static com.android.providers.media.photopicker.PickerDataLayer.QUERY_LOCAL_ID_SELECTION;
import static com.android.providers.media.photopicker.PickerDataLayer.QUERY_SHOULD_SCREEN_SELECTION_URIS;
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
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.photopicker.PickerDataLayer;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.metrics.NonUiEventLogger;
import com.android.providers.media.util.PermissionUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for Picker Uris, it handles (includes permission checks, incoming args
 * validations etc) and redirects picker URIs to the correct resolver.
 */
public class PickerUriResolver {
    private static final String TAG = "PickerUriResolver";

    public static final String PICKER_SEGMENT = "picker";

    public static final String PICKER_GET_CONTENT_SEGMENT = "picker_get_content";
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

    public static final String REFRESH_PICKER_UI_PATH = "refresh_ui";
    public static final Uri REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI =
            PICKER_INTERNAL_URI.buildUpon().appendPath(REFRESH_PICKER_UI_PATH).build();
    public static final String INIT_PATH = "init";

    public static final String MEDIA_PATH = "media";
    public static final String ALBUM_PATH = "albums";

    public static final String LOCAL_PATH = "local";
    public static final String ALL_PATH = "all";
    public static final List<Integer> PICKER_INTERNAL_TABLES = List.of(
            PICKER_INTERNAL_MEDIA_ALL,
            PICKER_INTERNAL_MEDIA_LOCAL,
            PICKER_INTERNAL_ALBUMS_ALL,
            PICKER_INTERNAL_ALBUMS_LOCAL);
    // use this uid for when the uid is eventually going to be ignored or a test for invalid uid.
    public static final Integer DEFAULT_UID = -1;

    private final Context mContext;
    private final PickerDbFacade mDbFacade;
    private final Set<String> mAllValidProjectionColumns;
    private final String[] mAllValidProjectionColumnsArray;
    private final LocalUriMatcher mLocalUriMatcher;

    PickerUriResolver(Context context, PickerDbFacade dbFacade, ProjectionHelper projectionHelper,
            LocalUriMatcher localUriMatcher) {
        mContext = context;
        mDbFacade = dbFacade;
        mAllValidProjectionColumns = projectionHelper.getProjectionMap(
                MediaStore.PickerMediaColumns.class).keySet();
        mAllValidProjectionColumnsArray = mAllValidProjectionColumns.toArray(new String[0]);
        mLocalUriMatcher = localUriMatcher;
    }

    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal,
            LocalCallingIdentity localCallingIdentity)
            throws FileNotFoundException {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw new SecurityException("PhotoPicker Uris can only be accessed to read."
                    + " Uri: " + uri);
        }

        checkPermissionForRequireOriginalQueryParam(uri, localCallingIdentity);
        checkUriPermission(uri, localCallingIdentity.pid, localCallingIdentity.uid);

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
            CancellationSignal signal, LocalCallingIdentity localCallingIdentity,
            boolean wantsThumb)
            throws FileNotFoundException {
        checkPermissionForRequireOriginalQueryParam(uri, localCallingIdentity);
        checkUriPermission(uri, localCallingIdentity.pid, localCallingIdentity.uid);

        final ContentResolver resolver;
        try {
            resolver = getContentResolverForUserId(uri);
        } catch (IllegalStateException e) {
            // This is to be consistent with MediaProvider's response when a file is not found.
            Log.e(TAG, "No item at " + uri, e);
            throw new FileNotFoundException("No item at " + uri);
        }

        if (wantsThumb) {
            Log.d(TAG, "Thumbnail is requested for " + uri);
            // If thumbnail is requested, forward the thumbnail request to the provider
            // rather than requesting the full media file
            return openThumbnailFromProvider(resolver, uri, mimeTypeFilter, opts, signal);
        }

        if (canHandleUriInUser(uri)) {
            return new AssetFileDescriptor(openPickerFile(uri), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH);
        }
        return resolver.openTypedAssetFile(uri, mimeTypeFilter, opts, signal);
    }

    /**
     * Returns result of the query operations that can be performed on the internal picker tables
     * as a cursor.
     *
     * <p>This also caters to the filtering of queryArgs parameter for id selection if required for
     * pre-selection.
     */
    public Cursor query(Integer table, Bundle queryArgs, String localProvider,
            String cloudProvider, PickerDataLayer pickerDataLayer) {
        Bundle screenedQueryArgs;
        if (table == PICKER_INTERNAL_MEDIA_ALL || table == PICKER_INTERNAL_MEDIA_LOCAL) {
            screenedQueryArgs = processUrisForSelection(queryArgs,
                    localProvider,
                    cloudProvider,
                    /* isLocalOnly */ table == PICKER_INTERNAL_MEDIA_LOCAL);
            if (table == PICKER_INTERNAL_MEDIA_ALL) {
                return pickerDataLayer.fetchAllMedia(screenedQueryArgs);
            } else if (table == PICKER_INTERNAL_MEDIA_LOCAL) {
                return pickerDataLayer.fetchLocalMedia(screenedQueryArgs);
            }
        }
        if (table == PICKER_INTERNAL_ALBUMS_ALL) {
            return pickerDataLayer.fetchAllAlbums(queryArgs);
        } else if (table == PICKER_INTERNAL_ALBUMS_LOCAL) {
            return pickerDataLayer.fetchLocalAlbums(queryArgs);
        }
        return null;
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

    private ParcelFileDescriptor openPickerFile(Uri uri)
            throws FileNotFoundException {
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
        String pickerSegmentType = getPickerSegmentType(uri);
        uri = unwrapProviderUri(uri);
        return mDbFacade.queryMediaIdForApps(pickerSegmentType, uri.getHost(),
                uri.getLastPathSegment(), projection);
    }

    private String getPickerSegmentType(Uri uri) {
        switch (mLocalUriMatcher.matchUri(uri, /* allowHidden */ false)) {
            case PICKER_ID:
                return PICKER_SEGMENT;
            case PICKER_GET_CONTENT_ID:
                return PICKER_GET_CONTENT_SEGMENT;
        }

        return null;
    }

    /**
     * Creates a picker uri incorporating authority, user id and cloud provider.
     */
    public static Uri wrapProviderUri(Uri uri, String action, int userId) {
        final List<String> segments = uri.getPathSegments();
        if (segments.size() != 2) {
            throw new IllegalArgumentException("Unexpected provider URI: " + uri);
        }

        Uri.Builder builder = initializeUriBuilder(MediaStore.AUTHORITY);
        if (action.equalsIgnoreCase(Intent.ACTION_GET_CONTENT)) {
            builder.appendPath(PICKER_GET_CONTENT_SEGMENT);
        } else {
            builder.appendPath(PICKER_SEGMENT);
        }
        builder.appendPath(String.valueOf(userId));
        builder.appendPath(uri.getHost());

        for (int i = 0; i < segments.size(); i++) {
            builder.appendPath(segments.get(i));
        }

        return builder.build();
    }

    /**
     * Filters URIs received for preSelection based on permission, authority and validity checks.
     */
    public Bundle processUrisForSelection(Bundle queryArgs, String localProvider,
            String cloudProvider, boolean isLocalOnly) {

        List<String> inputUrisAsStrings = queryArgs.getStringArrayList(QUERY_ID_SELECTION);
        if (inputUrisAsStrings == null) {
            // If no input selection is present then return;
            return queryArgs;
        }

        boolean shouldScreenSelectionUris = queryArgs.getBoolean(
                QUERY_SHOULD_SCREEN_SELECTION_URIS);

        if (shouldScreenSelectionUris) {
            Set<Uri> inputUris = screenArgsForPermissionCheckIfAny(queryArgs, inputUrisAsStrings);

            SelectionIdsSegregationResult result = populateLocalAndCloudIdListsForSelection(
                    inputUris, localProvider, cloudProvider, isLocalOnly);
            if (!result.getLocalIds().isEmpty()) {
                queryArgs.putStringArrayList(QUERY_LOCAL_ID_SELECTION, result.getLocalIds());
            }
            if (!result.getCloudIds().isEmpty()) {
                queryArgs.putStringArrayList(QUERY_CLOUD_ID_SELECTION, result.getCloudIds());
            }
            if (!result.getCloudIds().isEmpty() || !result.getLocalIds().isEmpty()) {
                Log.d(TAG, "Id selection has been enabled in the current query operation.");
            } else {
                Log.d(TAG, "Id selection has not been enabled in the current query operation.");
            }
        } else if (isLocalOnly) {
            Set<Uri> inputUris = inputUrisAsStrings.stream().map(Uri::parse).collect(
                    Collectors.toSet());

            Log.d(TAG, "Local id selection has been enabled in the current query operation.");
            queryArgs.putStringArrayList(QUERY_LOCAL_ID_SELECTION,
                    new ArrayList<>(inputUris.stream().map(Uri::getLastPathSegment)
                            .collect(Collectors.toList())));
        } else {
            Log.wtf(TAG, "Expected the uris to be local uris when screening is disabled");
        }

        return queryArgs;
    }

    private Set<Uri> screenArgsForPermissionCheckIfAny(Bundle queryArgs, List<String> inputUris) {
        int callingUid = queryArgs.getInt(EXTRA_CALLING_PACKAGE_UID);

        if (/* uid not found */ callingUid == 0 || /* uid is invalid */ callingUid == DEFAULT_UID) {
            // if calling uid is absent or is invalid then throw an error
            throw new IllegalArgumentException("Filtering Uris for Selection: "
                    + "Uid absent or invalid");
        }

        Set<Uri> accessibleUris = new HashSet<>();
        // perform checks and filtration.
        for (String uriAsString : inputUris) {
            Uri uriForSelection = Uri.parse(uriAsString);
            try {
                // verify if the calling package have permission to the requested uri.
                checkUriPermission(uriForSelection, /* pid */ -1, callingUid);
                accessibleUris.add(uriForSelection);
            } catch (SecurityException se) {
                Log.d(TAG,
                        "Filtering Uris for Selection: package does not have permission for "
                                + "the uri: "
                                + uriAsString);
            }
        }
        return accessibleUris;
    }

    private SelectionIdsSegregationResult populateLocalAndCloudIdListsForSelection(
            Set<Uri> inputUris, String localProvider,
            String cloudProvider, boolean isLocalOnly) {
        ArrayList<String> localIds = new ArrayList<>();
        ArrayList<String> cloudIds = new ArrayList<>();
        for (Uri uriForSelection : inputUris) {
            try {
                // unwrap picker uri to get host and id.
                Uri uri = PickerUriResolver.unwrapProviderUri(uriForSelection);
                if (localProvider.equals(uri.getHost())) {
                    // Adds the last segment (id) to localIds if the authority matches the
                    // local authority.
                    localIds.add(uri.getLastPathSegment());
                } else if (!isLocalOnly && cloudProvider != null && cloudProvider.equals(
                        uri.getHost())) {
                    // Adds the last segment (id) to cloudIds if the authority matches the
                    // current cloud authority.
                    cloudIds.add(uri.getLastPathSegment());
                } else {
                    Log.d(TAG,
                            "Filtering Uris for Selection: Unknown authority/host for the uri: "
                                    + uriForSelection);
                }
            } catch (IllegalArgumentException illegalArgumentException) {
                Log.d(TAG, "Filtering Uris for Selection: Input uri: " + uriForSelection
                        + " is not valid.");
            }
        }
        return new SelectionIdsSegregationResult(localIds, cloudIds);
    }

    private static class SelectionIdsSegregationResult {
        private final ArrayList<String> mLocalIds;
        private final ArrayList<String> mCloudIds;

        SelectionIdsSegregationResult(ArrayList<String> localIds, ArrayList<String> cloudIds) {
            mLocalIds = localIds;
            mCloudIds = cloudIds;
        }

        public ArrayList<String> getLocalIds() {
            return mLocalIds;
        }

        public ArrayList<String> getCloudIds() {
            return mCloudIds;
        }
    }

    @VisibleForTesting
    static Uri unwrapProviderUri(Uri uri) {
        return unwrapProviderUri(uri, true);
    }

    private static Uri unwrapProviderUri(Uri uri, boolean addUserId) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 5) {
            throw new IllegalArgumentException("Unexpected picker provider URI: " + uri);
        }

        // segments.get(0) == 'picker'
        final String userId = segments.get(1);
        final String host = segments.get(2);
        segments = segments.subList(3, segments.size());

        Uri.Builder builder = initializeUriBuilder(addUserId ? (userId + "@" + host) : host);

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
        // Clear query parameters to check for URI permissions, apps can add requireOriginal
        // query parameter to URI, URI grants will not be present in that case.
        Uri uriWithoutQueryParams = uri.buildUpon().clearQuery().build();
        if (!isSelf(uid)
                && !PermissionUtils.checkManageCloudMediaProvidersPermission(mContext, pid, uid)
                && mContext.checkUriPermission(uriWithoutQueryParams, pid, uid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION) != PERMISSION_GRANTED) {
            throw new SecurityException("Calling uid ( " + uid + " ) does not have permission to " +
                    "access picker uri: " + uriWithoutQueryParams);
        }
    }

    private void checkPermissionForRequireOriginalQueryParam(Uri uri,
            LocalCallingIdentity localCallingIdentity) {
        String value = uri.getQueryParameter(MediaStore.PARAM_REQUIRE_ORIGINAL);
        if (value == null || value.isEmpty()) {
            return;
        }

        // Check if requireOriginal is set
        if (Integer.parseInt(value) == 1) {
            if (mLocalUriMatcher.matchUri(uri, /* allowHidden */ false) == PICKER_ID) {
                throw new UnsupportedOperationException(
                        "Require Original is not supported for Picker URI " + uri);
            }

            if (mLocalUriMatcher.matchUri(uri, /* allowHidden */ false) == PICKER_GET_CONTENT_ID
                    && isRedactionNeededForPickerUri(localCallingIdentity)) {
                throw new UnsupportedOperationException("Calling uid ( " + Binder.getCallingUid()
                        + " ) does not have ACCESS_MEDIA_LOCATION permission for requesting "
                        + "original file");
            }
        }
    }

    private boolean isSelf(int uid) {
        return UserHandle.getAppId(Process.myUid()) == UserHandle.getAppId(uid);
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
                final String callingPackageAndColumn = callingPackageName + ":" + column;
                NonUiEventLogger.logPickerQueriedWithUnknownColumn(
                        callingUid, callingPackageAndColumn);
            }
        }
    }

    private AssetFileDescriptor openThumbnailFromProvider(ContentResolver resolver, Uri uri,
            String mimeTypeFilter, Bundle opts,
            CancellationSignal signal) throws FileNotFoundException {
        Bundle newOpts = opts == null ? new Bundle() : (Bundle) opts.clone();
        newOpts.putBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, true);
        newOpts.putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB, true);

        final Uri unwrappedUri = unwrapProviderUri(uri, false);
        final long  callingIdentity = Binder.clearCallingIdentity();
        try {
            return resolver.openTypedAssetFile(unwrappedUri, mimeTypeFilter, newOpts, signal);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
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
