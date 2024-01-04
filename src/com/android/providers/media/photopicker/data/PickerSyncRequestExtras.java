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

import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Encapsulate all picker sync request arguments related logic.
 */
public class PickerSyncRequestExtras {
    @Nullable
    private final String mAlbumId;
    @Nullable
    private final String mAlbumAuthority;
    private final boolean mInitLocalOnlyData;
    public PickerSyncRequestExtras(@Nullable String albumId,
            @Nullable String albumAuthority,
            boolean initLocalOnlyData) {
        mAlbumId = albumId;
        mAlbumAuthority = albumAuthority;
        mInitLocalOnlyData = initLocalOnlyData;
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
        return new PickerSyncRequestExtras(albumId, albumAuthority, initLocalOnlyData);
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
}
