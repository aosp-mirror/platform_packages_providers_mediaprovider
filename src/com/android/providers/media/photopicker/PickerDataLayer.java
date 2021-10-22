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

import static android.provider.CloudMediaProviderContract.EXTRA_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaColumns;
import static android.provider.CloudMediaProviderContract.MediaInfo;
import static com.android.providers.media.PickerUriResolver.getAlbumUri;
import static com.android.providers.media.PickerUriResolver.getMediaUri;
import static com.android.providers.media.PickerUriResolver.getDeletedMediaUri;
import static com.android.providers.media.PickerUriResolver.getMediaInfoUri;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LIMIT_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LONG_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_DEFAULT;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract.AlbumColumns;
import android.provider.MediaStore;
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

    private final PickerDbFacade mDbFacade;
    private final Context mContext;
    private final String mLocalProvider;

    public PickerDataLayer(Context context, PickerDbFacade dbFacade) {
        mContext = context;
        mDbFacade = dbFacade;
        mLocalProvider = dbFacade.getLocalProvider();
    }

    public Cursor fetchMedia(Bundle queryArgs) {
        final CloudProviderQueryExtras queryExtras
                = CloudProviderQueryExtras.fromMediaStoreBundle(queryArgs);

        if (Objects.equals(queryExtras.getAlbumId(), STRING_DEFAULT) || queryExtras.isFavorite()) {
            // Fetch merged and deduped media from picker db
            return mDbFacade.queryMedia(queryExtras.toQueryFilter());
        } else {
            // Fetch unique media directly from provider
            final String cloudProvider = validateCloudProvider(queryExtras);
            final Bundle extras = queryExtras.toCloudMediaBundle();

            if (cloudProvider == null) {
                return queryProviderMedia(mLocalProvider, extras);
            } else if (queryExtras.getAlbumType() == null) {
                // TODO(b/193668830): Replace null check with AlbumColumns.TYPE_CLOUD after
                // moving test to CTS
                return queryProviderMedia(cloudProvider, extras);
            } else {
                Log.w(TAG, "Unexpected album media query for cloud provider: " + cloudProvider);
                return new MatrixCursor(new String[] {});
            }
        }
    }

    public Cursor fetchAlbums(Bundle queryArgs) {
        final String cloudProvider = mDbFacade.getCloudProvider();
        final CloudProviderQueryExtras queryExtras
                = CloudProviderQueryExtras.fromMediaStoreBundle(queryArgs);
        final Bundle cloudMediaArgs = queryExtras.toCloudMediaBundle();
        final List<Cursor> cursors = new ArrayList<>();
        final Bundle cursorExtra = new Bundle();
        cursorExtra.putString(MediaStore.EXTRA_CLOUD_PROVIDER, queryExtras.getCloudProvider());

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

    private Cursor queryProviderAlbums(String authority, Bundle queryArgs) {
        if (authority == null) {
            // Can happen if there is no cloud provider
            return null;
        }

        return query(getAlbumUri(authority), queryArgs);
    }

    private Cursor queryProviderMedia(String authority, Bundle queryArgs) {
        final Bundle bundle = new Bundle();
        bundle.putString(MediaColumns.AUTHORITY, authority);

        final Cursor cursor = query(getMediaUri(authority), queryArgs);
        cursor.setExtras(bundle);
        return cursor;
    }

    private Cursor query(Uri uri, Bundle extras) {
        return mContext.getContentResolver().query(uri, /* projection */ null, extras,
                /* cancellationSignal */ null);
    }

    private String validateCloudProvider(CloudProviderQueryExtras extras) {
        final String extrasCloudProvider = extras.getCloudProvider();
        final String enabledCloudProvider = mDbFacade.getCloudProvider();

        if (Objects.equals(enabledCloudProvider, extrasCloudProvider)) {
            return enabledCloudProvider;
        }

        // Cloud provider has switched since last query, so no longer valid
        return null;
    }
}
