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

package android.provider;

import static android.provider.CloudMediaProviderContract.METHOD_GET_ACCOUNT_INFO;
import static android.provider.CloudMediaProviderContract.METHOD_GET_MEDIA_INFO;
import static android.provider.CloudMediaProviderContract.URI_PATH_ALBUM;
import static android.provider.CloudMediaProviderContract.URI_PATH_DELETED_MEDIA;
import static android.provider.CloudMediaProviderContract.URI_PATH_MEDIA;
import static android.provider.CloudMediaProviderContract.URI_PATH_MEDIA_EXACT;
import static android.provider.CloudMediaProviderContract.URI_PATH_MEDIA_INFO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;

/**
 * Base class for a cloud media provider. A cloud media provider offers read-only access to durable
 * media files, specifically photos and videos stored on a local disk, or files in a cloud storage
 * service. To create a cloud media provider, extend this class, implement the abstract methods,
 * and add it to your manifest like this:
 *
 * <pre class="prettyprint">&lt;manifest&gt;
 *    ...
 *    &lt;application&gt;
 *        ...
 *        &lt;provider
 *            android:name="com.example.MyCloudProvider"
 *            android:authorities="com.example.mycloudprovider"
 *            android:exported="true"
 *            android:permission="com.android.providers.media.permission.MANAGE_CLOUD_MEDIA_PROVIDERS"
 *            &lt;intent-filter&gt;
 *                &lt;action android:name="android.content.action.CLOUD_MEDIA_PROVIDER" /&gt;
 *            &lt;/intent-filter&gt;
 *        &lt;/provider&gt;
 *        ...
 *    &lt;/application&gt;
 *&lt;/manifest&gt;</pre>
 * <p>
 * When defining your provider, you must protect it with the
 * {@link CloudMediaProviderContract#MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION}, which is a permission
 * only the system can obtain, trying to define an unprotected {@link CloudMediaProvider} will
 * result in a {@link SecurityException}.
 * <p>
 * Applications cannot use a cloud media provider directly; they must go through
 * {@link MediaStore#ACTION_PICK_IMAGES} which requires a user to actively navigate and select
 * media items. When a user selects a media item through that UI, the system issues narrow URI
 * permission grants to the requesting application.
 * <h3>Media items</h3>
 * <p>
 * A media item must be an openable stream (with a specific MIME type). Media items can belong to
 * zero or more albums. Albums cannot contain other albums.
 * <p>
 * Each item under a provider is uniquely referenced by its media or album id, which must not
 * change without changing the provider version as returned by {@link #onGetMediaInfo}.
 *
 * @see MediaStore#ACTION_PICK_IMAGES
 */
public abstract class CloudMediaProvider extends ContentProvider {
    private static final String TAG = "CloudMediaProvider";

    private static final int MATCH_MEDIAS = 1;
    private static final int MATCH_MEDIA_ID = 2;
    private static final int MATCH_DELETED_MEDIAS = 3;
    private static final int MATCH_ALBUMS = 4;
    private static final int MATCH_MEDIA_INFO = 5;

    private final UriMatcher mMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    private volatile int mMediaStoreAuthorityAppId;

    /**
     * Implementation is provided by the parent class. Cannot be overridden.
     */
    @Override
    public final void attachInfo(@NonNull Context context, @NonNull ProviderInfo info) {
        registerAuthority(info.authority);

        super.attachInfo(context, info);
    }

    private void registerAuthority(String authority) {
        mMatcher.addURI(authority, URI_PATH_MEDIA, MATCH_MEDIAS);
        mMatcher.addURI(authority, URI_PATH_MEDIA_EXACT, MATCH_MEDIA_ID);
        mMatcher.addURI(authority, URI_PATH_DELETED_MEDIA, MATCH_DELETED_MEDIAS);
        mMatcher.addURI(authority, URI_PATH_ALBUM, MATCH_ALBUMS);
        mMatcher.addURI(authority, URI_PATH_MEDIA_INFO, MATCH_MEDIA_INFO);
    }

    /**
     * Returns account related information for the media collection.
     * <p>
     * This is useful for the OS to populate a settings page with account information and allow
     * users configure their media collection account.
     *
     * @param extras containing keys to filter result:
     * <ul>
     * <li> {@link CloudMediaProviderContract.AccountInfo#ACTIVE_ACCOUNT_NAME}
     * <li> {@link CloudMediaProviderContract.AccountInfo#ACCOUNT_CONFIGURATION_INTENT}
     * </ul>
     *
     * @return {@link Bundle} containing {@link CloudMediaProviderContract.AccountInfo}
     */
    @NonNull
    public Bundle onGetAccountInfo(@Nullable Bundle extras) {
        throw new UnsupportedOperationException("getAccountInfo not supported");
    }

        /**
     * Returns metadata about the media collection itself.
     * <p>
     * This is useful for the OS to determine if its cache of media items in the collection is
     * still valid and if a full or incremental sync is required with {@link #onQueryMedia}.
     * <p>
     * This method might be called by the OS frequently and is performance critical, hence it should
     * avoid long running operations.
     * <p>
     * If the provider handled any filters in {@code extras}, it must add the key to the
     * {@link ContentResolver#EXTRA_HONORED_ARGS} as part of the returned {@link Bundle}.
     *
     * @param extras containing keys to filter result:
     * <ul>
     * <li> {@link CloudMediaProviderContract#EXTRA_FILTER_ALBUM}
     * </ul>
     *
     * @return {@link Bundle} containing {@link CloudMediaProviderContract.MediaInfo}
     */
    @SuppressWarnings("unused")
    @NonNull
    public abstract Bundle onGetMediaInfo(@Nullable Bundle extras);

    /**
     * Returns a {@link Cursor} to a single media item containing the columns representing the media
     * item identified by the {@link CloudMediaProviderContract.MediaColumns#ID} with
     * {@code mediaId}.
     *
     * @param mediaId the media item to return
     * @return cursor representing single media item containing all
     * {@link CloudMediaProviderContract.MediaColumns}
     */
    @SuppressWarnings("unused")
    @NonNull
    public abstract Cursor onQueryMedia(@NonNull String mediaId);

    /**
     * Returns a cursor representing all media items in the media collection optionally filtered by
     * {@code extras} and sorted in reverse chronological order of
     * {@link CloudMediaProviderContract.MediaColumns#DATE_TAKEN_MS}, i.e. most recent items first.
     * <p>
     * If the cloud media provider handled any filters in {@code extras}, it must add the key to
     * the {@link ContentResolver#EXTRA_HONORED_ARGS} as part of the returned
     * {@link Cursor#setExtras} {@link Bundle}.
     *
     * @param extras containing keys to filter media items:
     * <ul>
     * <li> {@link CloudMediaProviderContract#EXTRA_GENERATION}
     * <li> {@link CloudMediaProviderContract#EXTRA_PAGE_TOKEN}
     * <li> {@link CloudMediaProviderContract#EXTRA_FILTER_ALBUM}
     * </ul>
     * @return cursor representing media items containing all
     * {@link CloudMediaProviderContract.MediaColumns} columns
     */
    @SuppressWarnings("unused")
    @NonNull
    public abstract Cursor onQueryMedia(@Nullable Bundle extras);

    /**
     * Returns a {@link Cursor} representing all deleted media items in the entire media collection
     * within the current provider version as returned by {@link #onGetMediaInfo}. These items can
     * be optionally filtered by {@code extras}.
     * <p>
     * If the provider handled any filters in {@code extras}, it must add the key to
     * the {@link ContentResolver#EXTRA_HONORED_ARGS} as part of the returned
     * {@link Cursor#setExtras} {@link Bundle}.
     *
     * @param extras containing keys to filter deleted media items:
     * <ul>
     * <li> {@link CloudMediaProviderContract#EXTRA_GENERATION}
     * <li> {@link CloudMediaProviderContract#EXTRA_PAGE_TOKEN}
     * </ul>
     * @return cursor representing deleted media items containing just the
     * {@link CloudMediaProviderContract.MediaColumns#ID} column
     */
    @SuppressWarnings("unused")
    @NonNull
    public abstract Cursor onQueryDeletedMedia(@Nullable Bundle extras);

    /**
     * Returns a cursor representing all album items in the media collection optionally filtered
     * by {@code extras} and sorted in reverse chronological order of
     * {@link CloudMediaProviderContract.AlbumColumns#DATE_TAKEN_MS}, i.e. most recent items first.
     * <p>
     * If the provider handled any filters in {@code extras}, it must add the key to
     * the {@link ContentResolver#EXTRA_HONORED_ARGS} as part of the returned
     * {@link Cursor#setExtras} {@link Bundle}.
     *
     * @param extras containing keys to filter album items:
     * <ul>
     * <li> {@link CloudMediaProviderContract#EXTRA_GENERATION}
     * <li> {@link CloudMediaProviderContract#EXTRA_PAGE_TOKEN}
     * </ul>
     * @return cursor representing album items containing all
     * {@link CloudMediaProviderContract.AlbumColumns} columns
     */
    @SuppressWarnings("unused")
    @NonNull
    public Cursor onQueryAlbums(@Nullable Bundle extras) {
        throw new UnsupportedOperationException("queryAlbums not supported");
    }

    /**
     * Returns a thumbnail of {@code size} for a media item identified by {@code mediaId}.
     * <p>
     * This is expected to be a much lower resolution version than the item returned by
     * {@link #onOpenMedia}.
     * <p>
     * If you block while downloading content, you should periodically check
     * {@link CancellationSignal#isCanceled()} to abort abandoned open requests.
     *
     * @param mediaId the media item to return
     * @param size the dimensions of the thumbnail to return. The returned file descriptor doesn't
     * have to match the {@code size} precisely because the OS will adjust the dimensions before
     * usage. Implementations can return close approximations especially if the approximation is
     * already locally on the device and doesn't require downloading from the cloud.
     * @param signal used by the OS to signal if the request should be cancelled
     * @return read-only file descriptor for accessing the thumbnail for the media file
     *
     * @see #onOpenMedia
     */
    @SuppressWarnings("unused")
    @NonNull
    public abstract AssetFileDescriptor onOpenThumbnail(@NonNull String mediaId,
            @NonNull Point size, @Nullable CancellationSignal signal) throws FileNotFoundException;

    /**
     * Returns the full size media item identified by {@code mediaId}.
     * <p>
     * If you block while downloading content, you should periodically check
     * {@link CancellationSignal#isCanceled()} to abort abandoned open requests.
     *
     * @param mediaId the media item to return
     * @param signal used by the OS to signal if the request should be cancelled
     * @return read-only file descriptor for accessing the media file
     *
     * @see #onOpenThumbnail
     */
    @SuppressWarnings("unused")
    @NonNull
    public abstract ParcelFileDescriptor onOpenMedia(@NonNull String mediaId,
            @Nullable CancellationSignal signal) throws FileNotFoundException;

    /**
     * Implementation is provided by the parent class. Cannot be overridden.
     */
    @Override
    @NonNull
    public final Bundle call(@NonNull String method, @Nullable String arg,
            @Nullable Bundle extras) {
        if (!method.startsWith("android:")) {
            // Ignore non-platform methods
            return super.call(method, arg, extras);
        }

        try {
            return callUnchecked(method, arg, extras);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private Bundle callUnchecked(String method, String arg, Bundle extras)
            throws FileNotFoundException {
        if (METHOD_GET_MEDIA_INFO.equals(method)) {
            return onGetMediaInfo(extras);
        } else if (METHOD_GET_ACCOUNT_INFO.equals(method)) {
            return onGetAccountInfo(extras);
        } else {
            throw new UnsupportedOperationException("Method not supported " + method);
        }
    }

    /**
     * Implementation is provided by the parent class. Cannot be overridden.
     *
     * @see #onOpenMedia
     */
    @NonNull
    @Override
    public final ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        return openFile(uri, mode, null);
    }

    /**
     * Implementation is provided by the parent class. Cannot be overridden.
     *
     * @see #onOpenMedia
     */
    @NonNull
    @Override
    public final ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode,
            @Nullable CancellationSignal signal) throws FileNotFoundException {
        String mediaId = uri.getLastPathSegment();

        return onOpenMedia(mediaId, signal);
    }

    /**
     * Implementation is provided by the parent class. Cannot be overridden.
     *
     * @see #onOpenThumbnail
     * @see #onOpenMedia
     */
    @NonNull
    @Override
    public final AssetFileDescriptor openTypedAssetFile(@NonNull Uri uri,
            @NonNull String mimeTypeFilter, @Nullable Bundle opts) throws FileNotFoundException {
        return openTypedAssetFile(uri, mimeTypeFilter, opts, null);
    }

    /**
     * Implementation is provided by the parent class. Cannot be overridden.
     *
     * @see #onOpenThumbnail
     * @see #onOpenMedia
     */
    @NonNull
    @Override
    public final AssetFileDescriptor openTypedAssetFile(
            @NonNull Uri uri, @NonNull String mimeTypeFilter, @Nullable Bundle opts,
            @Nullable CancellationSignal signal) throws FileNotFoundException {
        String mediaId = uri.getLastPathSegment();
        final boolean wantsThumb = (opts != null) && opts.containsKey(ContentResolver.EXTRA_SIZE)
                && mimeTypeFilter.startsWith("image/");
        if (wantsThumb) {
            Point point = (Point) opts.getParcelable(ContentResolver.EXTRA_SIZE);
            return onOpenThumbnail(mediaId, point, signal);
        }
        return new AssetFileDescriptor(onOpenMedia(mediaId, signal), 0 /* startOffset */,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    /**
     * Implementation is provided by the parent class. Cannot be overridden.
     *
     * @see #onQueryMedia
     * @see #onQueryDeletedMedia
     * @see #onQueryAlbums
     */
    @NonNull
    @Override
    public final Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable Bundle queryArgs, @Nullable CancellationSignal cancellationSignal) {
        switch (mMatcher.match(uri)) {
            case MATCH_MEDIAS:
                return onQueryMedia(queryArgs);
            case MATCH_MEDIA_ID:
                return onQueryMedia(uri.getLastPathSegment());
            case MATCH_DELETED_MEDIAS:
                return onQueryDeletedMedia(queryArgs);
            case MATCH_ALBUMS:
                return onQueryAlbums(queryArgs);
            default:
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
        }
    }

    /**
     * Implementation is provided by the parent class. Throws by default, and
     * cannot be overridden.
     */
    @NonNull
    @Override
    public final String getType(@NonNull Uri uri) {
        throw new UnsupportedOperationException("getType not supported");
    }

    /**
     * Implementation is provided by the parent class. Throws by default, and
     * cannot be overridden.
     */
    @NonNull
    @Override
    public final Uri canonicalize(@NonNull Uri uri) {
        throw new UnsupportedOperationException("Canonicalize not supported");
    }

    /**
     * Implementation is provided by the parent class. Throws by default, and
     * cannot be overridden.
     */
    @NonNull
    @Override
    public final Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder) {
        // As of Android-O, ContentProvider#query (w/ bundle arg) is the primary
        // transport method. We override that, and don't ever delegate to this method.
        throw new UnsupportedOperationException("Pre-Android-O query format not supported.");
    }

    /**
     * Implementation is provided by the parent class. Throws by default, and
     * cannot be overridden.
     */
    @NonNull
    @Override
    public final Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder, @Nullable CancellationSignal cancellationSignal) {
        // As of Android-O, ContentProvider#query (w/ bundle arg) is the primary
        // transport method. We override that, and don't ever delegate to this metohd.
        throw new UnsupportedOperationException("Pre-Android-O query format not supported.");
    }

    /**
     * Implementation is provided by the parent class. Throws by default, and
     * cannot be overridden.
     */
    @NonNull
    @Override
    public final Uri insert(@NonNull Uri uri, @NonNull ContentValues values) {
        throw new UnsupportedOperationException("Insert not supported");
    }

    /**
     * Implementation is provided by the parent class. Throws by default, and
     * cannot be overridden.
     */
    @Override
    public final int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Delete not supported");
    }

    /**
     * Implementation is provided by the parent class. Throws by default, and
     * cannot be overridden.
     */
    @Override
    public final int update(@NonNull Uri uri, @NonNull ContentValues values,
            @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Update not supported");
    }
}
