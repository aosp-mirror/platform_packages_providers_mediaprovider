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

import static android.provider.CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_EVENT_CALLBACK;
import static android.provider.CloudMediaProviderContract.METHOD_CREATE_SURFACE_CONTROLLER;
import static android.provider.CloudMediaProviderContract.METHOD_GET_ACCOUNT_INFO;
import static android.provider.CloudMediaProviderContract.METHOD_GET_MEDIA_INFO;
import static android.provider.CloudMediaProviderContract.URI_PATH_ALBUM;
import static android.provider.CloudMediaProviderContract.URI_PATH_DELETED_MEDIA;
import static android.provider.CloudMediaProviderContract.URI_PATH_MEDIA;
import static android.provider.CloudMediaProviderContract.URI_PATH_MEDIA_EXACT;
import static android.provider.CloudMediaProviderContract.URI_PATH_MEDIA_INFO;
import static android.provider.CloudMediaProviderContract.URI_PATH_SURFACE_CONTROLLER;

import android.annotation.DurationMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.FileNotFoundException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

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
 *
 * @hide
 */
public abstract class CloudMediaProvider extends ContentProvider {
    private static final String TAG = "CloudMediaProvider";

    private static final int MATCH_MEDIAS = 1;
    private static final int MATCH_MEDIA_ID = 2;
    private static final int MATCH_DELETED_MEDIAS = 3;
    private static final int MATCH_ALBUMS = 4;
    private static final int MATCH_MEDIA_INFO = 5;
    private static final int MATCH_SURFACE_CONTROLLER = 6;

    private static final boolean DEFAULT_LOOPING_PLAYBACK_ENABLED = true;
    private static final boolean DEFAULT_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED = false;

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
        mMatcher.addURI(authority, URI_PATH_SURFACE_CONTROLLER, MATCH_SURFACE_CONTROLLER);
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
     * {@link CloudMediaProviderContract.MediaColumns#DATE_TAKEN_MILLIS}, i.e. most recent items
     * first.
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
     * {@link CloudMediaProviderContract.AlbumColumns#DATE_TAKEN_MILLIS}, i.e. most recent items
     * first.
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
     * Returns a preview of {@code size} for a media item identified by {@code mediaId}.
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
     * @param extras to modify the way the fd is opened, e.g. for video files we may request a
     * thumbnail image instead of a video with
     * {@link CloudMediaProviderContract#EXTRA_PREVIEW_THUMBNAIL}
     * @param signal used by the OS to signal if the request should be cancelled
     * @return read-only file descriptor for accessing the thumbnail for the media file
     *
     * @see #onOpenMedia
     * @see CloudMediaProviderContract#EXTRA_PREVIEW_THUMBNAIL
     */
    @SuppressWarnings("unused")
    @NonNull
    public abstract AssetFileDescriptor onOpenPreview(@NonNull String mediaId,
            @NonNull Point size, @Nullable Bundle extras, @Nullable CancellationSignal signal)
            throws FileNotFoundException;

    /**
     * Returns the full size media item identified by {@code mediaId}.
     * <p>
     * If you block while downloading content, you should periodically check
     * {@link CancellationSignal#isCanceled()} to abort abandoned open requests.
     *
     * @param mediaId the media item to return
     * @param extras to modify the way the fd is opened, there's none at the moment, but some
     * might be implemented in the future
     * @param signal used by the OS to signal if the request should be cancelled
     * @return read-only file descriptor for accessing the media file
     *
     * @see #onOpenPreview
     */
    @SuppressWarnings("unused")
    @NonNull
    public abstract ParcelFileDescriptor onOpenMedia(@NonNull String mediaId,
            @Nullable Bundle extras, @Nullable CancellationSignal signal)
            throws FileNotFoundException;

    /**
     * Returns a {@link SurfaceController} used for rendering the preview of media items, or null
     * if preview rendering is not supported.
     *
     * <p>This is meant to be called on the main thread, hence the implementation should not block
     * by performing any heavy operation.
     *
     * @param config containing configuration parameters for {@link SurfaceController}
     * <ul>
     * <li> {@link CloudMediaProviderContract#EXTRA_LOOPING_PLAYBACK_ENABLED}
     * <li> {@link CloudMediaProviderContract#EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED}
     * </ul>
     * @param callback {@link SurfaceEventCallback} to send event updates for {@link Surface} to
     *                 picker launched via {@link MediaStore#ACTION_PICK_IMAGES}
     */
    @Nullable
    public SurfaceController onCreateSurfaceController(@NonNull Bundle config,
            @NonNull SurfaceEventCallback callback) {
        return null;
    }

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
        } else if (METHOD_CREATE_SURFACE_CONTROLLER.equals(method)) {
            return onCreateSurfaceController(extras);
        }  else {
            throw new UnsupportedOperationException("Method not supported " + method);
        }
    }

    private Bundle onCreateSurfaceController(@NonNull Bundle extras) {
        Objects.requireNonNull(extras);

        final IBinder binder = extras.getBinder(EXTRA_SURFACE_EVENT_CALLBACK);
        if (binder == null) {
            throw new IllegalArgumentException("Missing surface event callback");
        }

        final SurfaceEventCallback callback =
                new SurfaceEventCallback(ICloudSurfaceEventCallback.Stub.asInterface(binder));
        final Bundle config = new Bundle();
        config.putBoolean(EXTRA_LOOPING_PLAYBACK_ENABLED, DEFAULT_LOOPING_PLAYBACK_ENABLED);
        config.putBoolean(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED,
                DEFAULT_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED);
        final SurfaceController controller = onCreateSurfaceController(config, callback);
        if (controller == null) {
            Log.d(TAG, "onCreateSurfaceController returned null");
            return Bundle.EMPTY;
        }

        Bundle result = new Bundle();
        result.putBinder(EXTRA_SURFACE_CONTROLLER,
                new SurfaceControllerWrapper(controller).asBinder());
        return result;
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

        return onOpenMedia(mediaId, /* extras */ null, signal);
    }

    /**
     * Implementation is provided by the parent class. Cannot be overridden.
     *
     * @see #onOpenPreview
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
     * @see #onOpenPreview
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
            return onOpenPreview(mediaId, point, opts, signal);
        }
        return new AssetFileDescriptor(onOpenMedia(mediaId, opts, signal), 0 /* startOffset */,
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

    /**
     * Manages rendering the preview of media items on given instances of {@link Surface}.
     *
     * <p>The methods of this class are meant to be asynchronous, and should not block by performing
     * any heavy operation.
     * <p>Note that a single SurfaceController instance would be responsible for
     * rendering multiple media items associated with multiple surfaces.
     */
    @SuppressLint("PackageLayering") // We need to pass in a Surface which can be prepared for
    // rendering a media item.
    public static abstract class SurfaceController {

        /**
         * Creates any player resource(s) needed for rendering.
         */
        public abstract void onPlayerCreate();

        /**
         * Releases any player resource(s) used for rendering.
         */
        public abstract void onPlayerRelease();

        /**
         * Indicates creation of the given {@link Surface} with given {@code surfaceId} for
         * rendering the preview of a media item with given {@code mediaId}.
         *
         * <p>This is called immediately after the surface is first created. Implementations of this
         * should start up whatever rendering code they desire.
         * <p>Note that the given media item remains associated with the given surface id till the
         * {@link Surface} is destroyed.
         *
         * @param surfaceId id which uniquely identifies the {@link Surface} for rendering
         * @param surface instance of the {@link Surface} on which the media item should be rendered
         * @param mediaId id which uniquely identifies the media to be rendered
         *
         * @see SurfaceHolder.Callback#surfaceCreated(SurfaceHolder)
         */
        public abstract void onSurfaceCreated(int surfaceId, @NonNull Surface surface,
                @NonNull String mediaId);

        /**
         * Indicates structural changes (format or size) in the {@link Surface} for rendering.
         *
         * <p>This method is always called at least once, after {@link #onSurfaceCreated}.
         *
         * @param surfaceId id which uniquely identifies the {@link Surface} for rendering
         * @param format the new {@link PixelFormat} of the surface
         * @param width the new width of the {@link Surface}
         * @param height the new height of the {@link Surface}
         *
         * @see SurfaceHolder.Callback#surfaceChanged(SurfaceHolder, int, int, int)
         */
        public abstract void onSurfaceChanged(int surfaceId, int format, int width, int height);

        /**
         * Indicates destruction of a {@link Surface} with given {@code surfaceId}.
         *
         * <p>This is called immediately before a surface is being destroyed. After returning from
         * this call, you should no longer try to access this surface.
         *
         * @param surfaceId id which uniquely identifies the {@link Surface} for rendering
         *
         * @see SurfaceHolder.Callback#surfaceDestroyed(SurfaceHolder)
         */
        public abstract void onSurfaceDestroyed(int surfaceId);

        /**
         * Start playing the preview of the media associated with the given surface id. If
         * playback had previously been paused, playback will continue from where it was paused.
         * If playback had been stopped, or never started before, playback will start at the
         * beginning.
         *
         * @param surfaceId id which uniquely identifies the {@link Surface} for rendering
         */
        public abstract void onMediaPlay(int surfaceId);

        /**
         * Pauses the playback of the media associated with the given surface id.
         *
         * @param surfaceId id which uniquely identifies the {@link Surface} for rendering
         */
        public abstract void onMediaPause(int surfaceId);

        /**
         * Seeks the media associated with the given surface id to specified timestamp.
         *
         * @param surfaceId id which uniquely identifies the {@link Surface} for rendering
         * @param timestampMillis the timestamp in milliseconds from the start to seek to
         */
        public abstract void onMediaSeekTo(int surfaceId, @DurationMillisLong long timestampMillis);

        /**
         * Changes the configuration parameters for the SurfaceController.
         *
         * @param config the updated config to change to. This can include config changes for the
         * following:
         * <ul>
         * <li> {@link CloudMediaProviderContract#EXTRA_LOOPING_PLAYBACK_ENABLED}
         * <li> {@link CloudMediaProviderContract#EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED}
         * </ul>
         */
        public abstract void onConfigChange(@NonNull Bundle config);

        /**
         * Indicates destruction of this SurfaceController object.
         *
         * <p>This SurfaceController object should no longer be in use after this method has been
         * called.
         */
        public abstract void onDestroy();
    }

    /**
     * This class is used by {@link CloudMediaProvider} to send {@link Surface} event updates to
     * picker launched via {@link MediaStore#ACTION_PICK_IMAGES}.
     *
     * @see MediaStore#ACTION_PICK_IMAGES
     */
    public static final class SurfaceEventCallback {

        /** {@hide} */
        @IntDef(flag = true, prefix = { "PLAYBACK_EVENT_" }, value = {
                PLAYBACK_EVENT_BUFFERING,
                PLAYBACK_EVENT_READY,
                PLAYBACK_EVENT_STARTED,
                PLAYBACK_EVENT_PAUSED,
                PLAYBACK_EVENT_COMPLETED,
                PLAYBACK_EVENT_ERROR_RETRIABLE_FAILURE,
                PLAYBACK_EVENT_ERROR_PERMANENT_FAILURE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface PlaybackEvent {}

        /**
         * Constant to notify that the playback is buffering
         */
        public static final int PLAYBACK_EVENT_BUFFERING = 1;

        /**
         * Constant to notify that the playback is ready to be played
         */
        public static final int PLAYBACK_EVENT_READY = 2;

        /**
         * Constant to notify that the playback has started
         */
        public static final int PLAYBACK_EVENT_STARTED = 3;

        /**
         * Constant to notify that the playback is paused.
         */
        public static final int PLAYBACK_EVENT_PAUSED = 4;

        /**
         * Constant to notify that the playback event has completed
         */
        public static final int PLAYBACK_EVENT_COMPLETED = 5;

        /**
         * Constant to notify that the playback has failed with a retriable error.
         */
        public static final int PLAYBACK_EVENT_ERROR_RETRIABLE_FAILURE = 6;

        /**
         * Constant to notify that the playback has failed with a permanent error.
         */
        public static final int PLAYBACK_EVENT_ERROR_PERMANENT_FAILURE = 7;

        private final ICloudSurfaceEventCallback mCallback;

        SurfaceEventCallback (ICloudSurfaceEventCallback callback) {
            mCallback = callback;
        }

        /**
         * This is called to notify playback event update for a {@link Surface}
         * on the picker launched via {@link MediaStore#ACTION_PICK_IMAGES}.
         *
         * @param surfaceId id which uniquely identifies a {@link Surface}
         * @param playbackEventType playback event type to notify picker about
         * @param playbackEventInfo {@link Bundle} which may contain extra information about the
         *                          playback event. There is no particular event info that
         *                          we are currently expecting. This may change if we want to
         *                          support more features for Video Preview like progress/seek
         *                          bar or show video playback error messages to the user.
         */
        public void onPlaybackEvent(int surfaceId, @PlaybackEvent int playbackEventType,
                @Nullable Bundle playbackEventInfo) {
            try {
                mCallback.onPlaybackEvent(surfaceId, playbackEventType, playbackEventInfo);
            } catch (Exception e) {
                Log.d(TAG, "Failed to notify playback event (" + playbackEventType + ") for "
                        + "surfaceId: " + surfaceId + " ; playbackEventInfo: " + playbackEventInfo,
                        e);
            }
        }
    }

    /** {@hide} */
    private static class SurfaceControllerWrapper extends ICloudMediaSurfaceController.Stub {

        final private SurfaceController mSurfaceController;

        SurfaceControllerWrapper(SurfaceController surfaceController) {
            mSurfaceController = surfaceController;
        }

        @Override
        public void onPlayerCreate() {
            Log.i(TAG, "Creating player.");
            mSurfaceController.onPlayerCreate();
        }

        @Override
        public void onPlayerRelease() {
            Log.i(TAG, "Releasing player.");
            mSurfaceController.onPlayerRelease();
        }

        @Override
        public void onSurfaceCreated(int surfaceId, @NonNull Surface surface,
                @NonNull String mediaId) {
            Log.i(TAG, "Surface prepared. SurfaceId: " + surfaceId + ". MediaId: " + mediaId);
            mSurfaceController.onSurfaceCreated(surfaceId, surface, mediaId);
        }

        @Override
        public void onSurfaceChanged(int surfaceId, int format, int width, int height) {
            Log.i(TAG, "Surface changed. SurfaceId: " + surfaceId + ". Format: " + format
                    + ". Width: " + width + ". Height: " + height);
            mSurfaceController.onSurfaceChanged(surfaceId, format, width, height);
        }

        @Override
        public void onSurfaceDestroyed(int surfaceId) {
            Log.i(TAG, "Surface released. SurfaceId: " + surfaceId);
            mSurfaceController.onSurfaceDestroyed(surfaceId);
        }

        @Override
        public void onMediaPlay(int surfaceId) {
            Log.i(TAG, "Media played. SurfaceId: " + surfaceId);
            mSurfaceController.onMediaPlay(surfaceId);
        }

        @Override
        public void onMediaPause(int surfaceId) {
            Log.i(TAG, "Media paused. SurfaceId: " + surfaceId);
            mSurfaceController.onMediaPause(surfaceId);
        }

        @Override
        public void onMediaSeekTo(int surfaceId, @DurationMillisLong long timestampMillis) {
            Log.i(TAG, "Media seeked. SurfaceId: " + surfaceId + ". Seek timestamp(ms): "
                    + timestampMillis);
            mSurfaceController.onMediaSeekTo(surfaceId, timestampMillis);
        }

        @Override
        public void onConfigChange(@NonNull Bundle config) {
            Log.i(TAG, "Config changed. Updated config params: " + config);
            mSurfaceController.onConfigChange(config);
        }

        @Override
        public void onDestroy() {
            Log.i(TAG, "Controller destroyed");
            mSurfaceController.onDestroy();
        }
    }
}
