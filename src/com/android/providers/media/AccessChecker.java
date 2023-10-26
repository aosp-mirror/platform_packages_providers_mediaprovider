/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_PLAYLIST;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_SUBTITLE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
import static android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME;

import static com.android.providers.media.LocalUriMatcher.AUDIO_ALBUMART;
import static com.android.providers.media.LocalUriMatcher.AUDIO_ALBUMART_ID;
import static com.android.providers.media.LocalUriMatcher.AUDIO_ALBUMS;
import static com.android.providers.media.LocalUriMatcher.AUDIO_ALBUMS_ID;
import static com.android.providers.media.LocalUriMatcher.AUDIO_ARTISTS;
import static com.android.providers.media.LocalUriMatcher.AUDIO_ARTISTS_ID;
import static com.android.providers.media.LocalUriMatcher.AUDIO_ARTISTS_ID_ALBUMS;
import static com.android.providers.media.LocalUriMatcher.AUDIO_GENRES;
import static com.android.providers.media.LocalUriMatcher.AUDIO_GENRES_ALL_MEMBERS;
import static com.android.providers.media.LocalUriMatcher.AUDIO_GENRES_ID;
import static com.android.providers.media.LocalUriMatcher.AUDIO_GENRES_ID_MEMBERS;
import static com.android.providers.media.LocalUriMatcher.AUDIO_MEDIA;
import static com.android.providers.media.LocalUriMatcher.AUDIO_MEDIA_ID;
import static com.android.providers.media.LocalUriMatcher.AUDIO_MEDIA_ID_GENRES;
import static com.android.providers.media.LocalUriMatcher.AUDIO_MEDIA_ID_GENRES_ID;
import static com.android.providers.media.LocalUriMatcher.AUDIO_PLAYLISTS;
import static com.android.providers.media.LocalUriMatcher.AUDIO_PLAYLISTS_ID;
import static com.android.providers.media.LocalUriMatcher.AUDIO_PLAYLISTS_ID_MEMBERS;
import static com.android.providers.media.LocalUriMatcher.AUDIO_PLAYLISTS_ID_MEMBERS_ID;
import static com.android.providers.media.LocalUriMatcher.DOWNLOADS;
import static com.android.providers.media.LocalUriMatcher.DOWNLOADS_ID;
import static com.android.providers.media.LocalUriMatcher.FILES;
import static com.android.providers.media.LocalUriMatcher.FILES_ID;
import static com.android.providers.media.LocalUriMatcher.IMAGES_MEDIA;
import static com.android.providers.media.LocalUriMatcher.IMAGES_MEDIA_ID;
import static com.android.providers.media.LocalUriMatcher.IMAGES_THUMBNAILS;
import static com.android.providers.media.LocalUriMatcher.IMAGES_THUMBNAILS_ID;
import static com.android.providers.media.LocalUriMatcher.VIDEO_MEDIA;
import static com.android.providers.media.LocalUriMatcher.VIDEO_MEDIA_ID;
import static com.android.providers.media.LocalUriMatcher.VIDEO_THUMBNAILS;
import static com.android.providers.media.LocalUriMatcher.VIDEO_THUMBNAILS_ID;
import static com.android.providers.media.MediaGrants.PACKAGE_USER_ID_COLUMN;
import static com.android.providers.media.MediaProvider.INCLUDED_DEFAULT_DIRECTORIES;
import static com.android.providers.media.util.DatabaseUtils.bindSelection;

import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;

/**
 * Class responsible for performing all access checks (read/write access states for calling package)
 * and generating relevant SQL statements
 */
public class AccessChecker {
    private static final String NO_ACCESS_SQL = "0";

    /**
     * Returns {@code true} if given {@code callingIdentity} has full access to the given collection
     *
     * @param callingIdentity {@link LocalCallingIdentity} of the caller to verify permission state
     * @param uriType the collection info for which the requested access is,
     *                e.g., Images -> {@link MediaProvider}#IMAGES_MEDIA.
     * @param forWrite type of the access requested. Read / write access to the file / collection.
     */
    public static boolean hasAccessToCollection(LocalCallingIdentity callingIdentity, int uriType,
            boolean forWrite) {
        switch (uriType) {
            case AUDIO_MEDIA_ID:
            case AUDIO_MEDIA:
            case AUDIO_PLAYLISTS_ID:
            case AUDIO_PLAYLISTS:
            case AUDIO_ARTISTS_ID:
            case AUDIO_ARTISTS:
            case AUDIO_ARTISTS_ID_ALBUMS:
            case AUDIO_ALBUMS_ID:
            case AUDIO_ALBUMS:
            case AUDIO_ALBUMART_ID:
            case AUDIO_ALBUMART:
            case AUDIO_GENRES_ID:
            case AUDIO_GENRES:
            case AUDIO_MEDIA_ID_GENRES_ID:
            case AUDIO_MEDIA_ID_GENRES:
            case AUDIO_GENRES_ID_MEMBERS:
            case AUDIO_GENRES_ALL_MEMBERS:
            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
            case AUDIO_PLAYLISTS_ID_MEMBERS: {
                return callingIdentity.checkCallingPermissionAudio(forWrite);
            }
            case IMAGES_MEDIA:
            case IMAGES_MEDIA_ID:
            case IMAGES_THUMBNAILS_ID:
            case IMAGES_THUMBNAILS: {
                return callingIdentity.checkCallingPermissionImages(forWrite);
            }
            case VIDEO_MEDIA_ID:
            case VIDEO_MEDIA:
            case VIDEO_THUMBNAILS_ID:
            case VIDEO_THUMBNAILS: {
                return callingIdentity.checkCallingPermissionVideo(forWrite);
            }
            case DOWNLOADS_ID:
            case DOWNLOADS:
            case FILES_ID:
            case FILES: {
                // Allow apps with legacy read access to read all files.
                return !forWrite
                        && callingIdentity.isCallingPackageLegacyRead();
            }
            default: {
                throw new UnsupportedOperationException(
                        "Unknown or unsupported type: " + uriType);
            }
        }
    }

    /**
     * Returns {@code true} if the request is for read access to a collection that contains
     * visual media files and app has READ_MEDIA_VISUAL_USER_SELECTED permission.
     *
     * @param callingIdentity {@link LocalCallingIdentity} of the caller to verify permission state
     * @param uriType the collection info for which the requested access is,
     *                e.g., Images -> {@link MediaProvider}#IMAGES_MEDIA.
     * @param forWrite type of the access requested. Read / write access to the file / collection.
     */
    public static boolean hasUserSelectedAccess(@NonNull LocalCallingIdentity callingIdentity,
            int uriType, boolean forWrite) {
        if (forWrite) {
            // Apps only get read access via media_grants. For write access on user selected items,
            // app needs to get uri grants.
            return false;
        }

        switch (uriType) {
            case IMAGES_MEDIA:
            case IMAGES_MEDIA_ID:
            case IMAGES_THUMBNAILS_ID:
            case IMAGES_THUMBNAILS:
            case VIDEO_MEDIA_ID:
            case VIDEO_MEDIA:
            case VIDEO_THUMBNAILS_ID:
            case VIDEO_THUMBNAILS:
            case DOWNLOADS_ID:
            case DOWNLOADS:
            case FILES_ID:
            case FILES: {
                return callingIdentity.checkCallingPermissionUserSelected();
            }
            default: return false;
        }
    }

    /**
     * Returns where clause for access on user selected permission.
     *
     * <p><strong>NOTE:</strong> This method assumes that app has necessary permissions and returns
     * the where clause without checking any permission state of the app.
     */
    @NonNull
    public static String getWhereForUserSelectedAccess(
            @NonNull LocalCallingIdentity callingIdentity, int uriType) {
        switch (uriType) {
            case IMAGES_MEDIA:
            case IMAGES_MEDIA_ID:
            case VIDEO_MEDIA_ID:
            case VIDEO_MEDIA:
            case DOWNLOADS_ID:
            case DOWNLOADS:
            case FILES_ID:
            case FILES: {
                return getWhereForUserSelectedMatch(callingIdentity, MediaColumns._ID);
            }
            case IMAGES_THUMBNAILS_ID:
            case IMAGES_THUMBNAILS: {
                return getWhereForUserSelectedMatch(callingIdentity, "image_id");
            }
            case VIDEO_THUMBNAILS_ID:
            case VIDEO_THUMBNAILS: {
                return getWhereForUserSelectedMatch(callingIdentity, "video_id");
            }
            default:
                throw new UnsupportedOperationException(
                        "Unknown or unsupported type: " + uriType);
        }
    }

    /**
     * Returns where clause for constrained access.
     *
     * Where clause is generated based on the given collection type{@code uriType} and access
     * permissions of the app. Generated where clause may include one or more combinations of
     * below checks -
     * * Match {@link MediaColumns#OWNER_PACKAGE_NAME} with calling package's package name.
     * * Match ringtone or alarm or notification files to allow legacy use-cases
     * * Match media files if app has corresponding read / write permissions on media files
     * * Match files in primary storage if app has legacy write permissions
     * * Match default directories in case of use-cases like System gallery
     *
     * This method assumes global access permission checks and full access checks for the collection
     * is already checked. The method returns where clause assuming app doesn't have global access
     * permission to the given collection type.
     *
     * @param callingIdentity {@link LocalCallingIdentity} of the caller to verify permission state
     * @param uriType the collection info for which the requested access is,
     *                e.g., Images -> {@link MediaProvider}#IMAGES_MEDIA.
     * @param forWrite type of the access requested. Read / write access to the file / collection.
     * @param extras bundle containing {@link MediaProvider#INCLUDED_DEFAULT_DIRECTORIES} info if
     *               there is any.
     */
    @NonNull
    public static String getWhereForConstrainedAccess(
            @NonNull LocalCallingIdentity callingIdentity, int uriType,
            boolean forWrite, @NonNull Bundle extras) {
        switch (uriType) {
            case AUDIO_MEDIA_ID:
            case AUDIO_MEDIA: {
                // Apps without Audio permission can only see their own
                // media, but we also let them see ringtone-style media to
                // support legacy use-cases.
                return getWhereForOwnerPackageMatch(callingIdentity)
                        + " OR is_ringtone=1 OR is_alarm=1 OR is_notification=1";
            }
            case AUDIO_PLAYLISTS_ID:
            case AUDIO_PLAYLISTS:
            case IMAGES_MEDIA:
            case IMAGES_MEDIA_ID:
            case VIDEO_MEDIA_ID:
            case VIDEO_MEDIA: {
                return getWhereForOwnerPackageMatch(callingIdentity);
            }
            case AUDIO_ARTISTS_ID:
            case AUDIO_ARTISTS:
            case AUDIO_ARTISTS_ID_ALBUMS:
            case AUDIO_ALBUMS_ID:
            case AUDIO_ALBUMS:
            case AUDIO_ALBUMART_ID:
            case AUDIO_ALBUMART:
            case AUDIO_GENRES_ID:
            case AUDIO_GENRES:
            case AUDIO_MEDIA_ID_GENRES_ID:
            case AUDIO_MEDIA_ID_GENRES:
            case AUDIO_GENRES_ID_MEMBERS:
            case AUDIO_GENRES_ALL_MEMBERS:
            case AUDIO_PLAYLISTS_ID_MEMBERS_ID:
            case AUDIO_PLAYLISTS_ID_MEMBERS: {
                // We don't have a great way to filter parsed metadata by
                // owner, so callers need to hold READ_MEDIA_AUDIO
                return NO_ACCESS_SQL;
            }
            case IMAGES_THUMBNAILS_ID:
            case IMAGES_THUMBNAILS: {
                return "image_id IN (SELECT _id FROM images WHERE "
                        + getWhereForOwnerPackageMatch(callingIdentity) + ")";
            }
            case VIDEO_THUMBNAILS_ID:
            case VIDEO_THUMBNAILS: {
                return "video_id IN (SELECT _id FROM video WHERE "
                        + getWhereForOwnerPackageMatch(callingIdentity) + ")";
            }
            case DOWNLOADS_ID:
            case DOWNLOADS: {
                final ArrayList<String> options = new ArrayList<>();
                // Allow access to owned files
                options.add(getWhereForOwnerPackageMatch(callingIdentity));

                if (shouldAllowLegacyWrite(callingIdentity, forWrite)) {
                    // b/130766639: We're willing to let apps interact with well-defined MediaStore
                    // collections on secondary storage devices, but we continue to hold
                    // firm that any other legacy access to secondary storage devices must
                    // be read-only.
                    options.add(getWhereForExternalPrimaryMatch());
                }

                return TextUtils.join(" OR ", options);
            }
            case FILES_ID:
            case FILES: {
                final ArrayList<String> options = new ArrayList<>();
                // Allow access to owned files
                options.add(getWhereForOwnerPackageMatch(callingIdentity));

                if (shouldAllowLegacyWrite(callingIdentity, forWrite)) {
                    // b/130766639: We're willing to let apps interact with well-defined MediaStore
                    // collections on secondary storage devices, but we continue to hold
                    // firm that any other legacy access to secondary storage devices must
                    // be read-only.
                    options.add(getWhereForExternalPrimaryMatch());
                }

                // Allow access to media files if the app has corresponding read/write media
                // permission
                if (hasAccessToCollection(callingIdentity, AUDIO_MEDIA, forWrite)) {
                    options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_AUDIO));
                    options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_PLAYLIST));
                    options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_SUBTITLE));
                }
                if (hasAccessToCollection(callingIdentity, VIDEO_MEDIA, forWrite)) {
                    options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_VIDEO));
                    options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_SUBTITLE));
                }
                if (hasAccessToCollection(callingIdentity, IMAGES_MEDIA, forWrite)) {
                    options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_IMAGE));
                }

                // Allow access to file in directories. This si particularly used only for
                // SystemGallery use-case
                final String defaultDirectorySql = getWhereForDefaultDirectoryMatch(extras);
                if (defaultDirectorySql != null) {
                    options.add(defaultDirectorySql);
                }

                return TextUtils.join(" OR ", options);
            }
            default:
                throw new UnsupportedOperationException(
                        "Unknown or unsupported type: " + uriType);
        }
    }

    private static boolean shouldAllowLegacyWrite(LocalCallingIdentity callingIdentity,
            boolean forWrite) {
        return forWrite && callingIdentity.isCallingPackageLegacyWrite();
    }

    /**
     * Returns where clause to match {@link MediaColumns#OWNER_PACKAGE_NAME} with package names of
     * the given {@code callingIdentity}
     */
    public static String getWhereForOwnerPackageMatch(LocalCallingIdentity callingIdentity) {
        return OWNER_PACKAGE_NAME + " IN " + callingIdentity.getSharedPackagesAsString();
    }

    /**
     * Generates the where clause for a user_id media grant match.
     *
     * @param callingIdentity - the current caller.
     * @return where clause to match {@link MediaGrants#PACKAGE_USER_ID_COLUMN} with user id of the
     *         given {@code callingIdentity}
     */
    public static String getWhereForUserIdMatch(LocalCallingIdentity callingIdentity) {
        return PACKAGE_USER_ID_COLUMN + "=" + callingIdentity.uid / MediaStore.PER_USER_RANGE;
    }

    @VisibleForTesting
    static String getWhereForMediaTypeMatch(int mediaType) {
        return bindSelection("media_type=?", mediaType);
    }

    @VisibleForTesting
    static String getWhereForExternalPrimaryMatch() {
        return bindSelection("volume_name=?", MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    private static String getWhereForUserSelectedMatch(
            @NonNull LocalCallingIdentity callingIdentity, String id) {

        return String.format(
                "%s IN (SELECT file_id from media_grants WHERE %s AND %s)",
                id,
                getWhereForOwnerPackageMatch(callingIdentity),
                getWhereForUserIdMatch(callingIdentity));
    }

    /**
     * @see MediaProvider#INCLUDED_DEFAULT_DIRECTORIES
     */
    @Nullable
    private static String getWhereForDefaultDirectoryMatch(@NonNull Bundle extras) {
        final ArrayList<String> includedDefaultDirs = extras.getStringArrayList(
                INCLUDED_DEFAULT_DIRECTORIES);
        final ArrayList<String> options = new ArrayList<>();
        if (includedDefaultDirs != null) {
            for (String defaultDir : includedDefaultDirs) {
                options.add(FileColumns.RELATIVE_PATH + " LIKE '" + defaultDir + "/%'");
            }
        }

        if (options.size() > 0) {
            return TextUtils.join(" OR ", options);
        }
        return null;
    }
}
