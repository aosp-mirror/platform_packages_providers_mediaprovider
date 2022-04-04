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

import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.METHOD_GET_MEDIA_COLLECTION_INFO;
import static android.provider.CloudMediaProviderContract.MediaColumns;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo;
import static com.android.providers.media.PickerUriResolver.getAlbumUri;
import static com.android.providers.media.PickerUriResolver.getMediaUri;
import static com.android.providers.media.PickerUriResolver.getDeletedMediaUri;
import static com.android.providers.media.PickerUriResolver.getMediaCollectionInfoUri;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LIMIT_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LONG_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_DEFAULT;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import android.provider.CloudMediaProviderContract.AlbumColumns;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import com.android.providers.media.photopicker.data.CloudProviderQueryExtras;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fetches data for the picker UI from the db and cloud/local providers
 */
public class PickerDataLayer {
    private static final String TAG = "PickerDataLayer";

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
        final CloudProviderQueryExtras queryExtras
                = CloudProviderQueryExtras.fromMediaStoreBundle(queryArgs, mLocalProvider);
        final String albumId = queryExtras.getAlbumId();
        final String authority = queryExtras.getAlbumAuthority();
        final boolean isFavorite = queryExtras.isFavorite();

        if (TextUtils.isEmpty(albumId) || isFavorite) {
            // Refresh the 'media' table
            mSyncController.syncAllMedia();

            // Fetch all merged and deduped cloud and local media from 'media' table
            // This also matches 'merged' albums like Favorites because |authority| will
            // be null, hence we have to fetch the data from the picker db
            return mDbFacade.queryMediaForUi(queryExtras.toQueryFilter());
        } else {
            // The album type here can only be local or cloud because other album types
            // like Favorites don't have album authorities hence would hit the first condition

            // Refresh the 'album_media' table
            mSyncController.syncAlbumMedia(albumId, isLocal(authority));

            // Fetch album specific media for local or cloud from 'album_media' table
            return mDbFacade.queryAlbumMediaForUi(queryExtras.toQueryFilter(), authority);
        }
    }

    public Cursor fetchAlbums(Bundle queryArgs) {
        // Refresh the 'media' table so that 'merged' albums (Favorites and Videos) are up to date
        mSyncController.syncAllMedia();

        final String cloudProvider = mDbFacade.getCloudProvider();
        final CloudProviderQueryExtras queryExtras
                = CloudProviderQueryExtras.fromMediaStoreBundle(queryArgs, mLocalProvider);
        final Bundle cloudMediaArgs = queryExtras.toCloudMediaBundle();
        final List<Cursor> cursors = new ArrayList<>();
        final Bundle cursorExtra = new Bundle();
        cursorExtra.putString(MediaStore.EXTRA_CLOUD_PROVIDER, cloudProvider);

        final Cursor localAlbums = queryProviderAlbums(mLocalProvider, cloudMediaArgs);
        if (localAlbums != null) {
            cursors.add(localAlbums);
        }

        // TODO(b/195009148): Verify if 'Videos' should be a merged album view, hence if we should
        // refactor to mDbFacade.getMergedAlbums
        final Cursor favoriteAlbums = mDbFacade.getFavoriteAlbum(queryExtras.toQueryFilter());
        if (favoriteAlbums != null) {
            cursors.add(favoriteAlbums);
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

    public AccountInfo fetchCloudAccountInfo() {
        final String cloudProvider = mDbFacade.getCloudProvider();
        if (cloudProvider == null) {
            return null;
        }

        try {
            final Bundle accountBundle = mContext.getContentResolver().call(
                    getMediaCollectionInfoUri(cloudProvider), METHOD_GET_MEDIA_COLLECTION_INFO,
                    /* arg */ null, /* extras */ null);
            final String accountName = accountBundle.getString(
                    CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME);
            final Intent configIntent = (Intent) accountBundle.getParcelable(
                    CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT);

            if (accountName == null) {
                return null;
            }

            return new AccountInfo(accountName, configIntent);
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch account info from cloud provider: " + cloudProvider, e);
            return null;
        }
    }

    private Cursor queryProviderAlbums(String authority, Bundle queryArgs) {
        if (authority == null) {
            // Can happen if there is no cloud provider
            return null;
        }

        return query(getAlbumUri(authority), queryArgs);
    }

    private Cursor query(Uri uri, Bundle extras) {
        return mContext.getContentResolver().query(uri, /* projection */ null, extras,
                /* cancellationSignal */ null);
    }

    private boolean isLocal(String authority) {
        return mLocalProvider.equals(authority);
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
