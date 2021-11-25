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

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;

import java.util.UUID;

/**
 * Defines the contract between a cloud media provider and the OS.
 * <p>
 * To create a cloud media provider, extend {@link CloudMediaProvider}, which
 * provides a foundational implementation of this contract.
 *
 * @see CloudMediaProvider
 */
public final class CloudMediaProviderContract {
    private static final String TAG = "CloudMediaProviderContract";

    private CloudMediaProviderContract() {}

    /**
     * {@link Intent} action used to identify {@link CloudMediaProvider} instances. This
     * is used in the {@code <intent-filter>} of the {@code <provider>}.
     */
    public static final String PROVIDER_INTERFACE = "android.content.action.CLOUD_MEDIA_PROVIDER";

    /**
     * Permission required to protect {@link CloudMediaProvider} instances. Providers should
     * require this in the {@code permission} attribute in their {@code <provider>} tag.
     * The OS will not connect to a provider without this protection.
     */
    public static final String MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION =
            "com.android.providers.media.permission.MANAGE_CLOUD_MEDIA_PROVIDERS";

    /** Constants related to a media item, including {@link Cursor} column names */
    public static final class MediaColumns {
        private MediaColumns() {}

        /**
         * Unique ID of a media item. This ID is both provided by and interpreted
         * by a {@link CloudMediaProvider}, and should be treated as an opaque
         * value by client applications.
         *
         * <p>
         * Each media item must have a unique ID within a provider.
         *
         * <p>
         * A provider must always return stable IDs, since they will be used to
         * issue long-term URI permission grants when an application interacts
         * with {@link MediaStore#ACTION_PICK_IMAGES}.
         * <p>
         * Type: STRING
         */
        public static final String ID = "id";

        /**
         * Timestamp when a media item was capture, in milliseconds since
         * January 1, 1970 00:00:00.0 UTC.
         * <p>
         * Implementations should extract this data from the metadata embedded in the media
         * file. If this information is not available, a reasonable heuristic can be used, e.g.
         * the time the media file was added to the media collection.
         * <p>
         * Type: LONG
         *
         * @see CloudMediaProviderContract.AlbumColumns#DATE_TAKEN_MS
         * @see System#currentTimeMillis()
         */
        public static final String DATE_TAKEN_MS = "date_taken_ms";

        /**
         * Generation number associated with a media item.
         * <p>
         * Providers should associate a monotonically increasing generation number to each media
         * item which is expected to increase for each atomic modification on the media item. This
         * is useful for the OS to quickly identify that a media item has changed since a previous
         * point in time. Note that this does not need to be unique across all media items, i.e.,
         * multiple media items can have the same GENERATION_MODIFIED value. However, the
         * modification of a media item should increase the {@link MediaInfo#MEDIA_GENERATION}.
         * <p>
         * Type: LONG
         *
         * @see MediaInfo#MEDIA_GENERATION
         */
        public static final String GENERATION_MODIFIED = "generation_modified";

        /**
         * Concrete MIME type of a media file. For example, "image/png" or
         * "video/mp4".
         * <p>
         * Type: STRING
         */
        public static final String MIME_TYPE = "mime_type";

        /**
         * Mime-type extension representing special format for a media item.
         *
         * Photo Picker requires special format tagging for media items.
         * This is essential as media items can have various formats like
         * Motion Photos, GIFs etc, which are not identifiable by
         * {@link #MIME_TYPE}.
         * <p>
         * Type: INTEGER
         */
        public static final String STANDARD_MIME_TYPE_EXTENSION = "standard_mime_type_extension";

        /**
         * Constant for the {@link #STANDARD_MIME_TYPE_EXTENSION} column indicating
         * that the media item doesn't have any special format associated with it.
         */
        public static final int STANDARD_MIME_TYPE_EXTENSION_NONE = 0;

        /**
         * Constant for the {@link #STANDARD_MIME_TYPE_EXTENSION} column indicating
         * that the media item is a GIF.
         */
        public static final int STANDARD_MIME_TYPE_EXTENSION_GIF = 1;

        /**
         * Constant for the {@link #STANDARD_MIME_TYPE_EXTENSION} column indicating
         * that the media item is a Motion Photo.
         */
        public static final int STANDARD_MIME_TYPE_EXTENSION_MOTION_PHOTO = 2;

        /**
         * Size of a media file, in bytes.
         * <p>
         * Type: LONG
         */
        public static final String SIZE_BYTES = "size_bytes";

        /**
         * {@link MediaStore} URI of a media file if the file is available locally on the device.
         * <p>
         * If it's a cloud-only media file, this field should not be set.
         * Any of the following URIs can be used: {@link MediaStore.Files},
         * {@link MediaStore.Images} or {@link MediaStore.Video} e.g.
         * {@code content://media/file/45}.
         * <p>
         * Implementations don't need to handle the {@link MediaStore} URI becoming invalid after
         * the local item has been deleted or modified. If the URI becomes invalid or the
         * local and cloud file content diverges, the OS will treat the cloud media item as a
         * cloud-only item.
         * <p>
         * Type: STRING
         */
        public static final String MEDIA_STORE_URI = "media_store_uri";

        /**
         * Duration of a video file in ms. If the file is an image for which duration is not
         * applicable, this field can be left empty or set to {@code zero}.
         * <p>
         * Type: LONG
         */
        public static final String DURATION_MS = "duration_ms";

        /**
         * Whether the item has been favourited in the media collection. If {@code non-zero}, this
         * media item will appear in the favourites category in the Photo Picker.
         * <p>
         * Type: INTEGER
         */
        public static final String IS_FAVORITE = "is_favorite";

        /**
         * Authority of the media item
         * <p>
         * Type: STRING
         *
         * @hide
         */
        public static final String AUTHORITY = "authority";

        /**
         * File path of the media item
         * <p>
         * Type: STRING
         *
         * @hide
         */
        public static final String DATA = "data";
    }

    /** Constants related to an album item, including {@link Cursor} column names */
    public static final class AlbumColumns {
        private AlbumColumns() {}

        /**
         * Unique ID of an album. This ID is both provided by and interpreted
         * by a {@link CloudMediaProvider}.
         * <p>
         * Each album item must have a unique ID within a provider and a given version.
         * <p>
         * A provider should return durable IDs, since they will be used to cache
         * album information in the OS.
         * <p>
         * Type: STRING
         */
        public static final String ID = "id";


        /**
         * Display name of a an album, used as the primary title displayed to a
         * user.
         * <p>
         * Type: STRING
         */
        public static final String DISPLAY_NAME = "display_name";

        /**
         * Timestamp of the most recently taken photo in an album, in milliseconds since
         * January 1, 1970 00:00:00.0 UTC.
         * <p>
         * Type: LONG
         *
         * @see CloudMediaProviderContract.MediaColumns#DATE_TAKEN_MS
         * @see System#currentTimeMillis()
         */
        public static final String DATE_TAKEN_MS = "date_taken_ms";

        /**
         * Media id to use as the album cover photo.
         * <p>
         * If this field is not provided, albums will be shown in the Photo Picker without a cover
         * photo.
         * <p>
         * Type: LONG
         *
         * @see CloudMediaProviderContract.MediaColumns#ID
         */
        public static final String MEDIA_COVER_ID = "album_media_cover_id";

        /**
         * Total count of all media within the album, including photos and videos.
         * <p>
         * If this field is not provided, albums will be shown without a count in the Photo Picker
         * <p>
         * Type: LONG
         */
        public static final String MEDIA_COUNT = "album_media_count";

        /**
         * Type of album: {@link #TYPE_LOCAL}, {@link TYPE_CLOUD}, {@link TYPE_FAVORITES},
         * {@link TYPE_UNRELIABLE_VOLUME}
         * <p>
         * Type: STRING
         *
         * @hide
         */
        public static final String TYPE = "type";

        /**
         * Constant representing a type of album from a local provider except favorites
         *
         * @hide
         */
        public static final String TYPE_LOCAL = "LOCAL";

        /**
         * Constant representing a type of album from a cloud provider
         *
         * @hide
         */
        public static final String TYPE_CLOUD = null;

        /**
         * Constant representing a type of album from merged favorites of a local and cloud provider
         *
         * @hide
         */
        public static final String TYPE_FAVORITES = "FAVORITES";

        /**
         * Constant representing a type of album from an unreliable volume
         *
         * @hide
         */
        public static final String TYPE_UNRELIABLE_VOLUME = "UNRELIABLE_VOLUME";
    }

    /** Constants related to the entire media collection */
    public static final class MediaInfo {
        private MediaInfo() {}

        /**
         * Media collection version identifier
         * <p>
         * The only requirement on the value of a version is uniqueness on a device, i.e. a
         * a version should never be reused on a device.
         * <p>
         * This value will not be interpreted by the OS, however it will be used to check the validity
         * of cached data and URI grants to client apps. Anytime the media or album ids get re-indexed,
         * the version should change so that the OS can clear its cache and more importantly, revoke
         * any URI grants to apps.
         * <p>
         * Apps are recommended to generate unique versions with, {@link UUID#randomUUID}. This is
         * preferred to using a simple monotonic sequence because the provider data could get cleared
         * and it might have to re-index media items on the device without any history of it's last
         * version. With random UUIDs, if data gets cleared, a new one can easily be generated safely.
         * <p>
         * Type: STRING
         *
         * @see CloudMediaProvider#onGetMediaInfo
         */
        public static final String MEDIA_VERSION = "media_version";

        /**
         * Maximum generation number of media items in the entire media collection.
         * <p>
         * Providers should associate a monotonically increasing generation number to each media
         * item change (insertion/deletion/update). This is useful for the OS to quickly identify
         * exactly which media items have changed since a previous point in time.
         * <p>
         * Type: LONG
         *
         * @see CloudMediaProviderContract#EXTRA_GENERATION
         * @see CloudMediaProvider#onGetMediaInfo
         * @see CloudMediaProviderContract.MediaColumns#GENERATION_MODIFIED
         */
        public static final String MEDIA_GENERATION = "media_generation";

        /**
         * Total count of the media items in the entire media collection.
         * <p>
         * Along with the {@link #MEDIA_GENERATION} this helps the OS identify if there have been
         * changes to media items in the media collection.
         * <p>
         * Type: LONG
         *
         * @see CloudMediaProvider#onGetMediaInfo
         */
        public static final String MEDIA_COUNT = "media_count";
    }

    /** Constants related to the account information */
    public static final class AccountInfo {
        private AccountInfo() {}

        /**
         * Name of the account owning the media collection synced from the cloud provider.
         * <p>
         * Type: STRING
         *
         * @see CloudMediaProvider#onGetAccountInfo
         */
        public static final String ACTIVE_ACCOUNT_NAME = "active_account_name";

        /**
         * {@link Intent} Intent to launch an {@link Activity} to allow users configure their media
         * collection account information like the active account.
         * <p>
         * Type: PARCELABLE
         *
         * @see CloudMediaProvider#onGetAccountInfo
         */
        public static final String ACCOUNT_CONFIGURATION_INTENT = "account_configuration_intent";
    }

    /**
     * Opaque pagination token to retrieve the next page (cursor) from a media or album query.
     * <p>
     * Providers can optionally set this token as part of the {@link Cursor#setExtras}
     * {@link Bundle}. If a token is set, the OS can pass it as a {@link Bundle} parameter when
     * querying for media or albums to fetch subsequent pages. The provider can keep returning
     * pagination tokens until the last page at which point it should not set a token on the
     * {@link Cursor}.
     * <p>
     * If the provider handled the page token as part of the query, they must add
     * the {@link #EXTRA_PAGE_TOKEN} key to the array of {@link ContentResolver#EXTRA_HONORED_ARGS}
     * as part of the returned {@link Cursor#setExtras} {@link Bundle}.
     *
     * @see CloudMediaProvider#onQueryMedia
     * @see CloudMediaProvider#onQueryAlbums
     * <p>
     * Type: STRING
     */
    public static final String EXTRA_PAGE_TOKEN = "android.provider.extra.PAGE_TOKEN";

    /**
     * Generation number to fetch the latest media or album metadata changes from the media
     * collection.
     * <p>
     * The provider should associate a monotonically increasing generation number to each media item
     * change (insertion/deletion/update). This is useful to quickly identify exactly which media
     * items have changed since a previous point in time.
     * <p>
     * Providers should associate a separate monotonically increasing generation number for album
     * item changes (insertion/deletion/update). Unlike the media generation number, the album
     * generation number should also record insertions and deletions to media items within the
     * album. E.g, a direct change to an albums
     * {@link CloudMediaProviderContract.AlbumColumns#DISPLAY_NAME} will increase the
     * album generation number, likewise adding a photo to that album.
     * <p>
     * Note that multiple media (or album) items can share a generation number as long as the entire
     * change appears atomic from the perspective of the query APIs. E.g. each item in a batch photo
     * sync from the cloud can have the same generation number if they all occurred within the same
     * database transaction and hence guarantee that a db query result either has all they synced
     * items or none.
     * <p>
     * This extra can be passed as a {@link Bundle} parameter to the media or album query methods
     * and the provider should only return items with a generation number that are strictly greater
     * than the filter.
     * <p>
     * If the provider supports this filter, it must support the respective
     * {@link CloudMediaProvider#onGetMediaInfo} methods to return the {@code count} and
     * {@code max generation} for media or albums.
     * <p>
     * If the provider handled the generation, they must add the
     * {@link #EXTRA_GENERATION} key to the array of {@link ContentResolver#EXTRA_HONORED_ARGS}
     * as part of the returned {@link Cursor#setExtras} {@link Bundle}.
     *
     * @see MediaInfo#MEDIA_GENERATION
     * @see CloudMediaProvider#onQueryMedia
     * @see CloudMediaProvider#onQueryAlbums
     * @see MediaStore.MediaColumns#GENERATION_MODIFIED
     * <p>
     * Type: LONG
     */
    public static final String EXTRA_GENERATION = "android.provider.extra.GENERATION";

    /**
     * Limits the query results to only media items matching the given album id.
     * <p>
     * If the provider handled the album filter, they must also add the {@link #EXTRA_FILTER_ALBUM}
     * key to the array of {@link ContentResolver#EXTRA_HONORED_ARGS} as part of the returned
     * {@link Cursor#setExtras} {@link Bundle}.
     *
     * @see CloudMediaProvider#onQueryMedia
     * <p>
     * Type: STRING
     */
    public static final String EXTRA_FILTER_ALBUM = "android.provider.extra.FILTER_ALBUM";

    /**
     * Limits the query results to only media items matching the give mimetype.
     * <p>
     * The provider should handle an asterisk in the subtype, e.g. {@code image/*} should match
     * {@code image/jpeg} and {@code image/png}.
     * <p>
     * This is only intended for the MediaProvider to implement for cross-user communication. Not
     * for third party apps.
     *
     * @see CloudMediaProvider#onQueryMedia
     * <p>
     * Type: STRING
     */
    public static final String EXTRA_FILTER_MIMETYPE = "android.provider.extra.FILTER_MIMETYPE";

    /**
     * Limits the query results to only media items less than the given file size in bytes.
     * <p>
     * This is only intended for the MediaProvider to implement for cross-user communication. Not
     * for third party apps.
     *
     * @see CloudMediaProvider#onQueryMedia
     * <p>
     * Type: LONG
     */
    public static final String EXTRA_FILTER_SIZE_BYTES = "android.provider.extra.FILTER_SIZE_BYTES";

    /**
     * Constant used to execute {@link CloudMediaProvider#onGetMediaInfo} via
     * {@link ContentProvider#call}.
     *
     * {@hide}
     */
    public static final String METHOD_GET_MEDIA_INFO = "android:getMediaInfo";

    /**
     * Constant used to execute {@link CloudMediaProvider#onGetAccountInfo} via
     * {@link ContentProvider#call}.
     *
     * {@hide}
     */
    public static final String METHOD_GET_ACCOUNT_INFO = "android:getAccountInfo";

    /**
     * URI path for {@link CloudMediaProvider#onQueryMedia}
     *
     * {@hide}
     */
    public static final String URI_PATH_MEDIA = "media";

    /**
     * URI path for {@link CloudMediaProvider#onQueryMedia}
     *
     * {@hide}
     */
    public static final String URI_PATH_MEDIA_EXACT = URI_PATH_MEDIA + "/*";

    /**
     * URI path for {@link CloudMediaProvider#onQueryDeletedMedia}
     *
     * {@hide}
     */
    public static final String URI_PATH_DELETED_MEDIA = "deleted_media";

    /**
     * URI path for {@link CloudMediaProvider#onQueryAlbums}
     *
     * {@hide}
     */
    public static final String URI_PATH_ALBUM = "album";

    /**
     * URI path for {@link CloudMediaProvider#onGetMediaInfo}
     *
     * {@hide}
     */
    public static final String URI_PATH_MEDIA_INFO = "media_info";

    /**
     * URI path for {@link CloudMediaProvider#onGetAccountInfo}
     *
     * {@hide}
     */
    public static final String URI_PATH_ACCOUNT_INFO = "account_info";
}
