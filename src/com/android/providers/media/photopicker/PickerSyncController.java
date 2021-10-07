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

import static android.provider.CloudMediaProviderContract.MediaColumns;
import static android.provider.CloudMediaProviderContract.MediaInfo;
import static com.android.providers.media.PickerUriResolver.getMediaUri;
import static com.android.providers.media.PickerUriResolver.getDeletedMediaUri;
import static com.android.providers.media.PickerUriResolver.getMediaInfoUri;

import android.annotation.IntDef;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.util.BackgroundThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances on the device
 * into the picker db.
 */
public class PickerSyncController {
    private static final String TAG = "PickerSyncController";
    private static final String PREFS_NAME = "picker_provider_media_info";
    private static final String PREFS_KEY_CLOUD_PROVIDER = "cloud_provider";
    private static final String PREFS_KEY_CLOUD_PREFIX = "cloud_provider:";
    private static final String PREFS_KEY_LOCAL_PREFIX = "local_provider:";

    public static final String LOCAL_PICKER_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker";
    private static final long DEFAULT_SYNC_DELAY_MS = 1000;

    private static final int H_SYNC_PICKER = 1;

    private static final int SYNC_TYPE_NONE = 0;
    private static final int SYNC_TYPE_INCREMENTAL = 1;
    private static final int SYNC_TYPE_FULL = 2;
    private static final int SYNC_TYPE_RESET = 3;

    @IntDef(flag = false, prefix = { "SYNC_TYPE_" }, value = {
                SYNC_TYPE_NONE,
                SYNC_TYPE_INCREMENTAL,
                SYNC_TYPE_FULL,
                SYNC_TYPE_RESET,
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface SyncType {}

    private final Object mLock = new Object();
    private final PickerDbFacade mDbFacade;
    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final String mLocalProvider;
    private final long mSyncDelayMs;
    private final PickerHandler mHandler;

    // TODO(b/190713331): Listen for package_removed
    private String mCloudProvider;

    public PickerSyncController(Context context, PickerDbFacade dbFacade) {
        this(context, dbFacade, LOCAL_PICKER_PROVIDER_AUTHORITY, DEFAULT_SYNC_DELAY_MS);
    }

    @VisibleForTesting
    PickerSyncController(Context context, PickerDbFacade dbFacade,
            String localProvider, long syncDelayMs) {
        mContext = context;
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mDbFacade = dbFacade;
        mLocalProvider = localProvider;
        mCloudProvider = mPrefs.getString(PREFS_KEY_CLOUD_PROVIDER, /* default */ null);
        mHandler = new PickerHandler(BackgroundThread.get().getLooper());
        mSyncDelayMs = syncDelayMs;
    }

    private class PickerHandler extends Handler {
        public PickerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case H_SYNC_PICKER: {
                    syncPicker();
                    break;
                }
                default:
                    Log.w(TAG, "Unexpected handler message: " + msg);
            }
        }
    }

    /**
     * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances
     */
    public void syncPicker() {
        syncAndCommitProvider(mLocalProvider);

        synchronized (mLock) {
            syncAndCommitProvider(mCloudProvider);

            // Set the latest cloud provider on the facade
            mDbFacade.setCloudProvider(mCloudProvider);
        }
    }

    private void syncAndCommitProvider(@Nullable String authority) {
        if (authority == null) {
            // Only cloud authority can be null
            mDbFacade.resetMedia(authority);
            return;
        }

        final ContentResolver resolver = mContext.getContentResolver();
        final Bundle cachedMediaInfo = getCachedMediaInfo(authority);
        final Bundle latestMediaInfo = getLatestMediaInfo(authority);

        // Check what kind of sync is required, if any
        int result = checkSync(authority, latestMediaInfo, cachedMediaInfo);
        if (result == SYNC_TYPE_NONE) {
            return;
        }

        if (result == SYNC_TYPE_RESET) {
            // Odd! Can only happen if cloud provider gave us unexpected MediaInfo
            // We reset the cloud media in the picker db
            mDbFacade.resetMedia(authority);

            // And clear our cached MediaInfo, so that whenever the provider recovers,
            // we force a full sync
            clearCachedCloudMediaInfo(authority);
            return;
        }

        // Sync provider |authority| into picker db
        syncProvider(authority, cachedMediaInfo, result == SYNC_TYPE_FULL);

        // Commit sync position
        cacheMediaInfo(authority, latestMediaInfo);

        // TODO(b/190713331): Confirm that no more sync required?
    }

    /**
     * Returns the supported cloud {@link CloudMediaProvider} authorities.
     */
    public List<String> getSupportedCloudProviders() {
        final List<String> result = new ArrayList<>();
        final PackageManager pm = mContext.getPackageManager();
        final Intent intent = new Intent(CloudMediaProviderContract.PROVIDER_INTERFACE);
        final List<ResolveInfo> providers = pm.queryIntentContentProviders(intent, /* flags */ 0);

        for (ResolveInfo info : providers) {
            ProviderInfo providerInfo = info.providerInfo;
            if (providerInfo.authority != null
                    && CloudMediaProviderContract.MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION.equals(
                            providerInfo.readPermission)) {
                result.add(providerInfo.authority);
            }
        }

        return result;
    }

    /**
     * Enables a provider with {@code authority} as the default cloud {@link CloudMediaProvider}.
     * If {@code authority} is set to {@code null}, it simply clears the cloud provider.
     *
     * Note, that this doesn't sync the new provider after switching, however, no cloud items will
     * available from the picker db until the next sync. Callers should schedule a sync in the
     * background after switching providers.
     *
     * @return {@code true} if the provider was successfully enabled or cleared, {@code false}
     * otherwise
     */
    public boolean setCloudProvider(String authority) {
        synchronized (mLock) {
            if (Objects.equals(mCloudProvider, authority)) {
                Log.w(TAG, "Cloud provider already set: " + authority);
                return true;
            }
        }

        if (authority == null || getSupportedCloudProviders().contains(authority)) {
            synchronized (mLock) {
                mCloudProvider = authority;

                // This will *clear* the cloud provider on the mDbFacade and prevents any queries
                // from seeing the old or new cloud media until a sync where the cloud provider
                // on the facade will be set again
                clearCachedCloudMediaInfo(authority);
            }

            return true;
        }

        Log.w(TAG, "Cloud provider not supported: " + authority);
        return false;
    }

    public String getCloudProvider() {
        return mCloudProvider;
    }

    public String getLocalProvider() {
        return mLocalProvider;
    }

    /**
     * Notifies about media events like inserts/updates/deletes from cloud and local providers and
     * syncs the changes in the background.
     *
     * There is a delay before executing the background sync to artificially throttle the burst
     * notifications.
     */
    public void notifyMediaEvent() {
        mHandler.removeMessages(H_SYNC_PICKER);
        mHandler.sendEmptyMessageDelayed(H_SYNC_PICKER, mSyncDelayMs);
    }

    // TODO(b/190713331): Check extra_pages and extra_honored_args
    private void syncProvider(String authority, Bundle cachedMediaInfo, boolean fullSync) {
        int result = 0;
        if (fullSync) {
            // Reset media
            result = mDbFacade.resetMedia(authority);
            Log.i(TAG, "Reset sync. Authority: " + authority +  ". Result count: " + result);

            // Sync media
            try (Cursor cursor = query(getMediaUri(authority), /* extras */ null)) {
                result = mDbFacade.addMedia(cursor, authority);
                Log.i(TAG, "Full sync. Authority: " + authority +  ". Result count: " + result
                        + ". Cursor count: " + cursor.getCount());
            }
        } else {
            // Sync media
            final Bundle queryArgs = new Bundle();
            final long cachedGeneration = cachedMediaInfo.getLong(MediaInfo.MEDIA_GENERATION);
            queryArgs.putLong(MediaInfo.MEDIA_GENERATION, cachedGeneration);

            try (Cursor cursor = query(getMediaUri(authority), queryArgs)) {
                result = mDbFacade.addMedia(cursor, authority);
                Log.i(TAG, "Incremental sync. Authority: " + authority +  ". Result count: "
                        + result + ". Cursor count: " + cursor.getCount());
            }

            // Sync deleted_media
            final Bundle queryDeletedArgs = new Bundle();
            queryDeletedArgs.putLong(MediaInfo.MEDIA_GENERATION, cachedGeneration);

            try (Cursor cursor = query(getDeletedMediaUri(authority), queryDeletedArgs)) {
                final int idIndex = cursor.getColumnIndex(MediaColumns.ID);
                result = mDbFacade.removeMedia(cursor, idIndex, authority);
                Log.i(TAG, "Incremental deleted sync. Authority: " + authority +  ". Result count: "
                        + result + ". Cursor count: " + cursor.getCount());
            }
        }
    }

    private void cacheMediaInfo(String authority, Bundle bundle) {
        final SharedPreferences.Editor editor = mPrefs.edit();

        final String version = bundle.getString(MediaInfo.MEDIA_VERSION);
        final long generation = bundle.getLong(MediaInfo.MEDIA_GENERATION);
        final long count = bundle.getLong(MediaInfo.MEDIA_COUNT);

        editor.putString(getPrefsKey(authority, MediaInfo.MEDIA_VERSION), version);
        editor.putLong(getPrefsKey(authority, MediaInfo.MEDIA_GENERATION), generation);
        editor.putLong(getPrefsKey(authority, MediaInfo.MEDIA_COUNT), count);

        editor.commit();
    }

    private void clearCachedCloudMediaInfo(String authority) {
        if (isLocal(authority)) {
            // We never expect to clear local media info because we neither switch local providers
            // nor expect incorrect data from the local provider since we are bundled together
            return;
        }

        // Disable cloud provider queries on the db until next sync
        mDbFacade.setCloudProvider(null);

        final SharedPreferences.Editor editor = mPrefs.edit();

        editor.remove(getPrefsKey(authority, MediaInfo.MEDIA_VERSION));
        editor.remove(getPrefsKey(authority, MediaInfo.MEDIA_GENERATION));
        editor.remove(getPrefsKey(authority, MediaInfo.MEDIA_COUNT));

        if (authority == null) {
            editor.remove(PREFS_KEY_CLOUD_PROVIDER);
        } else {
            editor.putString(PREFS_KEY_CLOUD_PROVIDER, authority);
        }

        editor.commit();
    }

    private Bundle getCachedMediaInfo(String authority) {
        final Bundle bundle = new Bundle();

        final String version = mPrefs.getString(getPrefsKey(authority, MediaInfo.MEDIA_VERSION),
                /* default */ null);
        final long generation = mPrefs.getLong(getPrefsKey(authority, MediaInfo.MEDIA_GENERATION),
                /* default */ -1);
        final long count = mPrefs.getLong(getPrefsKey(authority, MediaInfo.MEDIA_COUNT),
                /* default */ -1);

        bundle.putString(MediaInfo.MEDIA_VERSION, version);
        bundle.putLong(MediaInfo.MEDIA_GENERATION, generation);
        bundle.putLong(MediaInfo.MEDIA_COUNT, count);

        return bundle;
    }

    private Bundle getLatestMediaInfo(String authority) {
        return mContext.getContentResolver().call(getMediaInfoUri(authority),
                CloudMediaProviderContract.METHOD_GET_MEDIA_INFO, /* arg */ null,
                /* extras */ null);
    }

    @SyncType
    private int checkSync(String authority, Bundle latestMediaInfo, Bundle cachedMediaInfo) {
        final String latestVersion = latestMediaInfo.getString(MediaInfo.MEDIA_VERSION);
        final long latestGeneration = latestMediaInfo.getLong(MediaInfo.MEDIA_GENERATION);
        final long latestCount = latestMediaInfo.getLong(MediaInfo.MEDIA_COUNT);

        final String cachedVersion = cachedMediaInfo.getString(MediaInfo.MEDIA_VERSION);
        final long cachedGeneration = cachedMediaInfo.getLong(MediaInfo.MEDIA_GENERATION);
        final long cachedCount = cachedMediaInfo.getLong(MediaInfo.MEDIA_COUNT);

        Log.d(TAG, "checkSync. Authority: " + authority + ". LatestMediaInfo: " + latestMediaInfo
                + ". CachedMediaInfo: " + cachedMediaInfo);

        if (TextUtils.isEmpty(latestVersion) || latestGeneration < 0 || latestCount < 0) {
            // If results from |latestMediaInfo| are unexpected, we reset the cloud provider
            Log.w(TAG, "checkSync. Authority: " + authority
                    + ". Result: SYNC_TYPE_RESET. Unexpected results: " + latestMediaInfo);
            return SYNC_TYPE_RESET;
        }

        if (!Objects.equals(latestVersion, cachedVersion)) {
            Log.d(TAG, "checkSync. Authority: " + authority + ". Result: SYNC_TYPE_FULL");
            return SYNC_TYPE_FULL;
        }

        if (cachedGeneration == latestGeneration && cachedCount == latestCount) {
            Log.d(TAG, "checkSync. Authority: " + authority + ". Result: SYNC_TYPE_NONE");
            return SYNC_TYPE_NONE;
        }

        Log.d(TAG, "checkSync. Authority: " + authority + ". Result: SYNC_TYPE_INCREMENTAL");
        return SYNC_TYPE_INCREMENTAL;
    }

    private String getPrefsKey(String authority, String key) {
        return (isLocal(authority) ? PREFS_KEY_LOCAL_PREFIX : PREFS_KEY_CLOUD_PREFIX) + key;
    }

    private boolean isLocal(String authority) {
        return mLocalProvider.equals(authority);
    }

    private Cursor query(Uri uri, Bundle extras) {
        return mContext.getContentResolver().query(uri, /* projection */ null, extras,
                /* cancellationSignal */ null);
    }
}
