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

package com.android.providers.media.photopicker.data.model;

import static android.provider.CloudMediaProviderContract.AlbumColumns;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS;

import static com.android.providers.media.photopicker.util.CursorUtils.getCursorInt;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.ItemsProvider;

import java.util.Locale;

/**
 * Defines each category (which is group of items) for the photo picker.
 */
public class Category {
    public static final String TAG = "PhotoPicker";
    public static final Category DEFAULT = new Category();

    private final String mId;
    private final String mAuthority;
    private final String mDisplayName;
    private final boolean mIsLocal;
    private final Uri mCoverUri;
    private final int mItemCount;

    private Category() {
        this(null, null, null, null, 0, false);
    }

    @VisibleForTesting
    public Category(String id, String authority, String displayName, Uri coverUri, int itemCount,
            boolean isLocal) {
        mId = id;
        mAuthority = authority;
        mDisplayName = displayName;
        mIsLocal = isLocal;
        mCoverUri = coverUri;
        mItemCount = itemCount;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "Category: {mId: %s, mAuthority: %s, mDisplayName: %s, " +
                "mCoverUri: %s, mItemCount: %d, mIsLocal: %b",
                mId, mAuthority, mDisplayName, mCoverUri, mItemCount, mIsLocal);
    }

    public String getId() {
        return mId;
    }

    public String getAuthority() {
        return mAuthority;
    }

    public String getDisplayName(Context context) {
        if (mIsLocal) {
            return getLocalizedDisplayName(context, mId);
        }
        return mDisplayName;
    }

    public boolean isLocal() {
        return mIsLocal;
    }

    public Uri getCoverUri() {
        return mCoverUri;
    }

    public int getItemCount() {
        return mItemCount;
    }

    public boolean isDefault() {
        return TextUtils.isEmpty(mId);
    }

    /**
     * Write the {@link Category} to the given {@code bundle}.
     */
    public void toBundle(@NonNull Bundle bundle) {
        bundle.putString(AlbumColumns.ID, mId);
        bundle.putString(AlbumColumns.AUTHORITY, mAuthority);
        bundle.putString(AlbumColumns.DISPLAY_NAME, mDisplayName);
        // Re-using the 'media_cover_id' to store the media_cover_uri for lack of
        // a different constant
        bundle.putParcelable(AlbumColumns.MEDIA_COVER_ID, mCoverUri);
        bundle.putInt(AlbumColumns.MEDIA_COUNT, mItemCount);
        bundle.putBoolean(AlbumColumns.IS_LOCAL, mIsLocal);
    }

    /**
     * Create a {@link Category} from the {@code bundle}.
     */
    public static Category fromBundle(@NonNull Bundle bundle) {
        return new Category(bundle.getString(AlbumColumns.ID),
                bundle.getString(AlbumColumns.AUTHORITY),
                bundle.getString(AlbumColumns.DISPLAY_NAME),
                bundle.getParcelable(AlbumColumns.MEDIA_COVER_ID),
                bundle.getInt(AlbumColumns.MEDIA_COUNT),
                bundle.getBoolean(AlbumColumns.IS_LOCAL));
    }

    /**
     * Create a {@link Category} from the {@code cursor}.
     */
    public static Category fromCursor(@NonNull Cursor cursor, @NonNull UserId userId) {
        String authority = getCursorString(cursor, AlbumColumns.AUTHORITY);
        if (authority == null) {
            // Authority will be null for cloud albums in cursor.
            String cloudProvider = cursor.getExtras().getString(MediaStore.EXTRA_CLOUD_PROVIDER);
            if (cloudProvider == null) {
                // If cloud provider is null, cloud albums will not show up properly.
                Log.e(TAG, "Cloud provider is set by the user but not passed in album media cursor"
                        + " extras.");
            } else {
                authority = cloudProvider;
            }
        }
        final boolean isLocal = authority != null
                && authority.equals(cursor.getExtras().getString(MediaStore.EXTRA_LOCAL_PROVIDER));
        final Uri coverUri = ItemsProvider.getItemsUri(
                getCursorString(cursor, AlbumColumns.MEDIA_COVER_ID), authority, userId);

        return new Category(getCursorString(cursor, AlbumColumns.ID),
                authority,
                getCursorString(cursor, AlbumColumns.DISPLAY_NAME),
                coverUri,
                getCursorInt(cursor, AlbumColumns.MEDIA_COUNT),
                isLocal);
    }

    private static String getLocalizedDisplayName(Context context, String albumId) {
        switch (albumId) {
            case ALBUM_ID_VIDEOS:
                return context.getString(R.string.picker_category_videos);
            case ALBUM_ID_CAMERA:
                return context.getString(R.string.picker_category_camera);
            case ALBUM_ID_SCREENSHOTS:
                return context.getString(R.string.picker_category_screenshots);
            case ALBUM_ID_DOWNLOADS:
                return context.getString(R.string.picker_category_downloads);
            case ALBUM_ID_FAVORITES:
                return context.getString(R.string.picker_category_favorites);
            default:
                return albumId;
        }
    }
}
