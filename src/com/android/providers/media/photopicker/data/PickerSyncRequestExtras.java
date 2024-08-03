/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.providers.media.photopicker.data.CloudProviderQueryExtras.isMergedAlbum;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.EXTRA_MIME_TYPES;

import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Encapsulate all picker sync request arguments related logic.
 */
public class PickerSyncRequestExtras {
    private static final String EXTRA_INTENT_ACTION = "intent_action";
    @Nullable
    private final String mAlbumId;
    @Nullable
    private final String mAlbumAuthority;
    private final boolean mInitLocalOnlyData;
    private final int mCallingPackageUid;
    private final boolean mShouldSyncGrants;
    private final String[] mMimeTypes;

    public PickerSyncRequestExtras(@Nullable String albumId,
            @Nullable String albumAuthority,
            boolean initLocalOnlyData,
            int callingPackageUid,
            boolean shouldSyncGrants,
            @Nullable String[] mimeTypes) {
        mAlbumId = albumId;
        mAlbumAuthority = albumAuthority;
        mInitLocalOnlyData = initLocalOnlyData;
        mCallingPackageUid = callingPackageUid;
        mShouldSyncGrants = shouldSyncGrants;
        mMimeTypes = mimeTypes;
    }

    /**
     * Create a {@link PickerSyncRequestExtras} object from an input bundle.
     */
    public static PickerSyncRequestExtras fromBundle(@NonNull Bundle extras) {
        Objects.requireNonNull(extras);

        final String albumId = extras.getString(MediaStore.EXTRA_ALBUM_ID);
        final String albumAuthority = extras.getString(MediaStore.EXTRA_ALBUM_AUTHORITY);
        final boolean initLocalOnlyData =
                extras.getBoolean(MediaStore.EXTRA_LOCAL_ONLY);
        final int callingPackageUid = extras.getInt(Intent.EXTRA_UID, /* default value */ -1);
        // Grants should only be synced when the intent action is
        // MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.
        final String intentAction = extras.getString(EXTRA_INTENT_ACTION);
        final boolean shouldSyncGrants = intentAction != null
                && intentAction.equals(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP);
        final ArrayList<String> mimeTypesList = extras.getStringArrayList(EXTRA_MIME_TYPES);
        final String[] mimeTypes;
        if (mimeTypesList != null) {
            mimeTypes = mimeTypesList.stream().toArray(String[]::new);
        } else {
            mimeTypes = null;
        }

        return new PickerSyncRequestExtras(albumId, albumAuthority, initLocalOnlyData,
                callingPackageUid, shouldSyncGrants, mimeTypes);
    }

    /**
     * Returns true when media data should be synced.
     */
    public boolean shouldSyncMediaData() {
        return TextUtils.isEmpty(mAlbumId);
    }

    /**
     * Returns true when only local data needs to be synced.
     */
    public boolean shouldSyncLocalOnlyData() {
        return mInitLocalOnlyData;
    }

    /**
     * Returns true when the sync request is for a merged album.
     */
    public boolean shouldSyncMergedAlbum() {
        return isMergedAlbum(mAlbumId);
    }

    /**
     * Return album id for the sync request.
     */
    @Nullable
    public String getAlbumId() {
        return mAlbumId;
    }

    /**
     * Return album authority for the sync request.
     */
    @Nullable
    public String getAlbumAuthority() {
        return mAlbumAuthority;
    }

    /**
     * Return calling package uid for current picker session.
     */
    public int getCallingPackageUid() {
        return mCallingPackageUid;
    }

    /**
     * Returns true if grants should be synced, false otherwise.
     */
    public boolean isShouldSyncGrants() {
        return mShouldSyncGrants;
    }

    /**
     * Returns mimeTypes that can be used as a filtering parameter for syncs.
     */
    public String[] getMimeTypes() {
        return mMimeTypes == null ? new String[]{} : mMimeTypes;
    }
}
