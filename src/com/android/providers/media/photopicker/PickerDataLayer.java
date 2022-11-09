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

package com.android.providers.media.photopicker;

import static android.provider.CloudMediaProviderContract.METHOD_GET_MEDIA_COLLECTION_INFO;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME;

import static com.android.providers.media.PickerUriResolver.getAlbumUri;
import static com.android.providers.media.PickerUriResolver.getMediaCollectionInfoUri;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MergeCursor;
import android.os.Bundle;
import android.os.Trace;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.data.CloudProviderQueryExtras;
import com.android.providers.media.photopicker.data.PickerDbFacade;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches data for the picker UI from the db and cloud/local providers
 */
public class PickerDataLayer {
    private static final String TAG = "PickerDataLayer";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final PickerDbFacade mDbFacade;
    private final PickerSyncController mSyncController;
    private final String mLocalProvider;

    public PickerDataLayer(Context context, PickerDbFacade dbFacade,
            PickerSyncController syncController) {
        mContext = context;
        mDbFacade = dbFacade;
        mSyncController = syncController;
        mLocalProvider = dbFacade.getLocalProvider();
    }

    public Cursor fetchMedia(Bundle queryArgs) {
        if (DEBUG) {
            Log.d(TAG, "fetchMedia() [" + Thread.currentThread() + "] args=" + queryArgs);
        }

        final CloudProviderQueryExtras queryExtras
                = CloudProviderQueryExtras.fromMediaStoreBundle(queryArgs, mLocalProvider);
        final String authority = queryExtras.getAlbumAuthority();

        Trace.beginSection(traceSectionName("fetchMedia", authority));
        try {
            return fetchMediaInternal(authority, queryExtras);
        } finally {
            Trace.endSection();
        }
    }

    private Cursor fetchMediaInternal(String authority, CloudProviderQueryExtras queryExtras) {
        final String albumId = queryExtras.getAlbumId();
        // Use media table for all media except albums. Merged categories like,
        // favorites and video are tagged in the media table and are not a part of
        // album_media.
        if (TextUtils.isEmpty(albumId) || isMergedAlbum(queryExtras)) {
            // Refresh the 'media' table
            mSyncController.syncAllMedia();

            if (TextUtils.isEmpty(albumId)) {
                // Notify that the picker is launched in case there's any pending UI notification
                mSyncController.notifyPickerLaunch();
            }

            // Fetch all merged and deduped cloud and local media from 'media' table
            // This also matches 'merged' albums like Favorites because |authority| will
            // be null, hence we have to fetch the data from the picker db
            return mDbFacade.queryMediaForUi(queryExtras.toQueryFilter());
        } else {
            // The album type here can only be local or cloud because merged categories like,
            // Favorites and Videos would hit the first condition.
            // Refresh the 'album_media' table
            mSyncController.syncAlbumMedia(albumId, isLocal(authority));

            // Fetch album specific media for local or cloud from 'album_media' table
            return mDbFacade.queryAlbumMediaForUi(queryExtras.toQueryFilter(), authority);
        }
    }

    /**
     * Checks if the query is for a merged album type.
     * Some albums are not cloud only, they are merged from files on devices and the cloudprovider.
     */
    private boolean isMergedAlbum(CloudProviderQueryExtras queryExtras) {
        final boolean isFavorite = queryExtras.isFavorite();
        final boolean isVideo = queryExtras.isVideo();
        return isFavorite || isVideo;
    }

    public Cursor fetchAlbums(Bundle queryArgs) {
        if (DEBUG) {
            Log.d(TAG, "fetchAlbums() [" + Thread.currentThread() + "] args=" + queryArgs);
        }

        Trace.beginSection(traceSectionName("fetchAlbums"));
        try {
            return fetchAlbumsInternal(queryArgs);
        } finally {
            Trace.endSection();
        }
    }

    private Cursor fetchAlbumsInternal(Bundle queryArgs) {
        // Refresh the 'media' table so that 'merged' albums (Favorites and Videos) are up to date
        mSyncController.syncAllMedia();

        final String cloudProvider = mDbFacade.getCloudProvider();
        final CloudProviderQueryExtras queryExtras
                = CloudProviderQueryExtras.fromMediaStoreBundle(queryArgs, mLocalProvider);
        final Bundle cloudMediaArgs = queryExtras.toCloudMediaBundle();
        final List<Cursor> cursors = new ArrayList<>();
        final Bundle cursorExtra = new Bundle();
        cursorExtra.putString(MediaStore.EXTRA_CLOUD_PROVIDER, cloudProvider);
        cursorExtra.putString(MediaStore.EXTRA_LOCAL_PROVIDER, mLocalProvider);

        // Favorites and Videos are merged albums.
        final Cursor mergedAlbums = mDbFacade.getMergedAlbums(queryExtras.toQueryFilter());
        if (mergedAlbums != null) {
            cursors.add(mergedAlbums);
        }

        final Cursor localAlbums = queryProviderAlbums(mLocalProvider, cloudMediaArgs);
        if (localAlbums != null) {
            cursors.add(localAlbums);
        }

        final Cursor cloudAlbums = queryProviderAlbums(cloudProvider, cloudMediaArgs);
        if (cloudAlbums != null) {
            cursors.add(cloudAlbums);
        }

        if (cursors.isEmpty()) {
            return null;
        }

        MergeCursor mergeCursor = new MergeCursor(cursors.toArray(new Cursor[cursors.size()]));
        mergeCursor.setExtras(cursorExtra);
        return mergeCursor;
    }

    @Nullable
    public AccountInfo fetchCloudAccountInfo() {
        if (DEBUG) {
            Log.d(TAG, "fetchCloudAccountInfo() [" + Thread.currentThread() + "]");
        }

        final String cloudProvider = mDbFacade.getCloudProvider();
        if (cloudProvider == null) {
            return null;
        }

        Trace.beginSection(traceSectionName("fetchCloudAccountInfo"));
        try {
            return fetchCloudAccountInfoInternal(cloudProvider);
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch account info from cloud provider: " + cloudProvider, e);
            return null;
        } finally {
            Trace.endSection();
        }
    }

    @Nullable
    private AccountInfo fetchCloudAccountInfoInternal(@NonNull String cloudProvider) {
        final Bundle accountBundle = mContext.getContentResolver()
                .call(getMediaCollectionInfoUri(cloudProvider), METHOD_GET_MEDIA_COLLECTION_INFO,
                        /* arg */ null, /* extras */ null);

        final String accountName = accountBundle.getString(ACCOUNT_NAME);
        if (accountName == null) {
            return null;
        }
        final Intent configIntent = accountBundle.getParcelable(ACCOUNT_CONFIGURATION_INTENT);

        return new AccountInfo(accountName, configIntent);
    }

    private Cursor queryProviderAlbums(@Nullable String authority, Bundle queryArgs) {
        if (authority == null) {
            // Can happen if there is no cloud provider
            return null;
        }

        Trace.beginSection(traceSectionName("queryProviderAlbums", authority));
        try {
            return queryProviderAlbumsInternal(authority, queryArgs);
        } finally {
            Trace.endSection();
        }
    }

    private Cursor queryProviderAlbumsInternal(@NonNull String authority, Bundle queryArgs) {
        try {
            return mContext.getContentResolver().query(getAlbumUri(authority),
                    /* projection */ null, queryArgs, /* cancellationSignal */ null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch cloud albums for: " + authority, e);
            return null;
        }
    }

    private boolean isLocal(String authority) {
        return mLocalProvider.equals(authority);
    }

    private String traceSectionName(@NonNull String method) {
        return traceSectionName(method, null);
    }

    private String traceSectionName(@NonNull String method, @Nullable String authority) {
        final StringBuilder sb = new StringBuilder("PDL.")
                .append(method);
        if (authority != null) {
            sb.append('[').append(isLocal(authority) ? "local" : "cloud").append(']');
        }
        return sb.toString();
    }

    public static class AccountInfo {
        public final String accountName;
        public final Intent accountConfigurationIntent;

        public AccountInfo(String accountName, Intent accountConfigurationIntent) {
            this.accountName = accountName;
            this.accountConfigurationIntent = accountConfigurationIntent;
        }
    }
}
