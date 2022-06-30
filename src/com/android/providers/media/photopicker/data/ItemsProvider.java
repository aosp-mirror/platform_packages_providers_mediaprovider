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

package com.android.providers.media.photopicker.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.UserId;

/**
 * Provides image and video items from {@link MediaStore} collection to the Photo Picker.
 */
public class ItemsProvider {

    private static final String TAG = ItemsProvider.class.getSimpleName();

    private final Context mContext;

    public ItemsProvider(Context context) {
        mContext = context;
    }

    /**
     * Returns a {@link Cursor} to all images/videos based on the param passed for
     * {@code categoryType}, {@code offset}, {@code limit}, {@code mimeTypes} and {@code userId}.
     *
     * <p>
     * By default the returned {@link Cursor} sorts by latest date taken.
     *
     * @param category the category of items to return. May be cloud, local or merged albums like
     * favorites or videos.
     * @param offset the offset after which to return items.
     * @param limit the limit of number of items to return.
     * @param mimeType the mime type of item. {@code null} returns all images/videos that are
     *                 scanned by {@link MediaStore}.
     * @param userId the {@link UserId} of the user to get items as.
     *               {@code null} defaults to {@link UserId#CURRENT_USER}
     *
     * @return {@link Cursor} to images/videos on external storage that are scanned by
     * {@link MediaStore}. The returned cursor is filtered based on params passed, it {@code null}
     * if there are no such images/videos. The Cursor for each item contains {@link ItemColumns}
     */
    @Nullable
    public Cursor getItems(Category category, int offset,
            int limit, @Nullable String[] mimeTypes, @Nullable UserId userId) throws
            IllegalArgumentException {
        if (userId == null) {
            userId = UserId.CURRENT_USER;
        }

        return queryMedia(limit, mimeTypes, category, userId);
    }

    /**
     * Returns a {@link Cursor} to all non-empty categories in which images/videos are categorised.
     * This includes:
     * * A constant list of local categories for on-device images/videos: {@link Category}
     * * Albums provided by selected cloud provider
     *
     * @param mimeType the mime type of item. {@code null} returns all images/videos that are
     *                 scanned by {@link MediaStore}.
     * @param userId the {@link UserId} of the user to get categories as.
     *               {@code null} defaults to {@link UserId#CURRENT_USER}.
     *
     * @return {@link Cursor} for each category would contain the following columns in
     * their relative order:
     * categoryName: {@link CategoryColumns#NAME} The name of the category,
     * categoryCoverId: {@link CategoryColumns#COVER_ID} The id for the cover of
     *                   the category. By default this will be the most recent image/video in that
     *                   category,
     * categoryNumberOfItems: {@link CategoryColumns#NUMBER_OF_ITEMS} number of image/video items
     *                        in the category,
     */
    @Nullable
    public Cursor getCategories(@Nullable String[] mimeTypes, @Nullable UserId userId) {
        if (userId == null) {
            userId = UserId.CURRENT_USER;
        }

        return queryAlbums(mimeTypes, userId);
    }

    private Cursor queryMedia(int limit, String[] mimeTypes,
            @NonNull Category category, @NonNull UserId userId)
            throws IllegalStateException {
        final Bundle extras = new Bundle();
        try (ContentProviderClient client = userId.getContentResolver(mContext)
                .acquireUnstableContentProviderClient(MediaStore.AUTHORITY)) {
            if (client == null) {
                Log.e(TAG, "Unable to acquire unstable content provider for "
                        + MediaStore.AUTHORITY);
                return null;
            }
            extras.putInt(MediaStore.QUERY_ARG_LIMIT, limit);
            if (mimeTypes != null) {
                extras.putStringArray(MediaStore.QUERY_ARG_MIME_TYPE, mimeTypes);
            }
            extras.putString(MediaStore.QUERY_ARG_ALBUM_ID, category.getId());
            extras.putString(MediaStore.QUERY_ARG_ALBUM_AUTHORITY, category.getAuthority());

            final Uri uri = PickerUriResolver.PICKER_INTERNAL_URI.buildUpon()
                    .appendPath(PickerUriResolver.MEDIA_PATH).build();

            return client.query(uri, /* projection */ null, extras, /* cancellationSignal */ null);
        } catch (RemoteException | NameNotFoundException ignored) {
            // Do nothing, return null.
            Log.e(TAG, "Failed to query merged media with extras: "
                    + extras + ". userId = " + userId, ignored);
            return null;
        }
    }

    @Nullable
    private Cursor queryAlbums(@Nullable String[] mimeTypes, @NonNull UserId userId) {
        final Bundle extras = new Bundle();
        try (ContentProviderClient client = userId.getContentResolver(mContext)
                .acquireUnstableContentProviderClient(MediaStore.AUTHORITY)) {
            if (client == null) {
                Log.e(TAG, "Unable to acquire unstable content provider for "
                        + MediaStore.AUTHORITY);
                return null;
            }
            if (mimeTypes != null) {
                extras.putStringArray(MediaStore.QUERY_ARG_MIME_TYPE, mimeTypes);
            }

            final Uri uri = PickerUriResolver.PICKER_INTERNAL_URI.buildUpon()
                    .appendPath(PickerUriResolver.ALBUM_PATH).build();

            return client.query(uri, /* projection */ null, extras, /* cancellationSignal */ null);
        } catch (RemoteException | NameNotFoundException ignored) {
            // Do nothing, return null.
            Log.w(TAG, "Failed to query merged albums with extras: "
                    + extras + ". userId = " + userId, ignored);
            return null;
        }
    }

    public static Uri getItemsUri(String id, String authority, UserId userId) {
        final Uri uri = PickerUriResolver.getMediaUri(authority).buildUpon()
                .appendPath(id).build();

        if (userId.equals(UserId.CURRENT_USER)) {
            return uri;
        }

        return createContentUriForUser(uri, userId.getUserHandle());
    }

    private static Uri createContentUriForUser(Uri uri, UserHandle userHandle) {
        if (SdkLevel.isAtLeastS()) {
            return ContentProvider.createContentUriForUser(uri, userHandle);
        }

        return createContentUriForUserImpl(uri, userHandle);
    }

    /**
     * This method is a copy of {@link ContentProvider#createContentUriForUser(Uri, UserHandle)}
     * which is a System API added in Android S.
     */
    private static Uri createContentUriForUserImpl(Uri uri, UserHandle userHandle) {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IllegalArgumentException(String.format(
                    "Given URI [%s] is not a content URI: ", uri));
        }

        int userId = userHandle.getIdentifier();
        if (uriHasUserId(uri)) {
            if (String.valueOf(userId).equals(uri.getUserInfo())) {
                return uri;
            }
            throw new IllegalArgumentException(String.format(
                    "Given URI [%s] already has a user ID, different from given user handle [%s]",
                    uri,
                    userId));
        }

        Uri.Builder builder = uri.buildUpon();
        builder.encodedAuthority(
                "" + userHandle.getIdentifier() + "@" + uri.getEncodedAuthority());
        return builder.build();
    }

    private static boolean uriHasUserId(Uri uri) {
        if (uri == null) return false;
        return !TextUtils.isEmpty(uri.getUserInfo());
    }
}
