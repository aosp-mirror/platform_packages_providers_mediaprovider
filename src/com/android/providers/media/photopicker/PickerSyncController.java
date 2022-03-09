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

import static android.provider.CloudMediaProviderContract.EXTRA_FILTER_ALBUM;
import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_TOKEN;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo;
import static com.android.providers.media.PickerUriResolver.getMediaUri;
import static com.android.providers.media.PickerUriResolver.getDeletedMediaUri;
import static com.android.providers.media.PickerUriResolver.getMediaCollectionInfoUri;

import android.annotation.IntDef;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import androidx.annotation.GuardedBy;
import androidx.annotation.VisibleForTesting;
import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.photopicker.data.PickerDbFacade;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances on the device
 * into the picker db.
 */
public class PickerSyncController {
    private static final String TAG = "PickerSyncController";
    private static final String PREFS_KEY_CLOUD_PROVIDER = "cloud_provider";
    private static final String PREFS_KEY_CLOUD_PROVIDER_UID = "cloud_provider_uid";
    private static final String PREFS_KEY_CLOUD_PREFIX = "cloud_provider:";
    private static final String PREFS_KEY_LOCAL_PREFIX = "local_provider:";

    private static final String PICKER_USER_PREFS_FILE_NAME = "picker_user_prefs";
    public static final String PICKER_SYNC_PREFS_FILE_NAME = "picker_sync_prefs";
    public static final String LOCAL_PICKER_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker";

    private static final String DEFAULT_CLOUD_PROVIDER_PKG = null;
    private static final int DEFAULT_CLOUD_PROVIDER_UID = -1;
    private static final long DEFAULT_SYNC_DELAY_MS =
            PickerDbFacade.getDefaultPickerDbSyncDelayMs();

    private static final int SYNC_TYPE_NONE = 0;
    private static final int SYNC_TYPE_MEDIA_INCREMENTAL = 1;
    private static final int SYNC_TYPE_MEDIA_FULL = 2;
    private static final int SYNC_TYPE_MEDIA_RESET = 3;
    private static final int SYNC_TYPE_ALBUM_MEDIA_RESET = 4;
    private static final int SYNC_TYPE_ALBUM_MEDIA_FULL = 5;

    @IntDef(flag = false, prefix = { "SYNC_TYPE_" }, value = {
                SYNC_TYPE_NONE,
            SYNC_TYPE_MEDIA_INCREMENTAL,
            SYNC_TYPE_MEDIA_FULL,
            SYNC_TYPE_MEDIA_RESET,
            SYNC_TYPE_ALBUM_MEDIA_RESET,
            SYNC_TYPE_ALBUM_MEDIA_FULL
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface SyncType {}

    private final Object mLock = new Object();
    private final PickerDbFacade mDbFacade;
    private final Context mContext;
    private final SharedPreferences mSyncPrefs;
    private final SharedPreferences mUserPrefs;
    private final String mLocalProvider;
    private final long mSyncDelayMs;
    private final Runnable mSyncAllMediaCallback;

    // TODO(b/190713331): Listen for package_removed
    @GuardedBy("mLock")
    private CloudProviderInfo mCloudProviderInfo;

    public PickerSyncController(Context context, PickerDbFacade dbFacade) {
        this(context, dbFacade, LOCAL_PICKER_PROVIDER_AUTHORITY, DEFAULT_SYNC_DELAY_MS);
    }

    @VisibleForTesting
    PickerSyncController(Context context, PickerDbFacade dbFacade,
            String localProvider, long syncDelayMs) {
        mContext = context;
        mSyncPrefs = mContext.getSharedPreferences(PICKER_SYNC_PREFS_FILE_NAME,
                Context.MODE_PRIVATE);
        mUserPrefs = mContext.getSharedPreferences(PICKER_USER_PREFS_FILE_NAME,
                Context.MODE_PRIVATE);
        mDbFacade = dbFacade;
        mLocalProvider = localProvider;
        mSyncDelayMs = syncDelayMs;
        mSyncAllMediaCallback = this::syncAllMedia;

        final String cloudProvider = mUserPrefs.getString(PREFS_KEY_CLOUD_PROVIDER,
                DEFAULT_CLOUD_PROVIDER_PKG);
        final int cloudProviderUid = mUserPrefs.getInt(PREFS_KEY_CLOUD_PROVIDER_UID,
                DEFAULT_CLOUD_PROVIDER_UID);
        if (cloudProvider == null) {
            mCloudProviderInfo = CloudProviderInfo.EMPTY;
        } else {
            mCloudProviderInfo = new CloudProviderInfo(cloudProvider, cloudProviderUid);
        }
    }

    /**
     * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances
     */
    public void syncAllMedia() {
        syncAllMediaFromProvider(mLocalProvider);

        synchronized (mLock) {
            final String cloudProvider = mCloudProviderInfo.authority;
            syncAllMediaFromProvider(cloudProvider);

            // Set the latest cloud provider on the facade
            mDbFacade.setCloudProvider(cloudProvider);
        }
    }

    /**
     * Syncs album media from the local and currently enabled cloud {@link CloudMediaProvider}
     * instances
     */
    public void syncAlbumMedia(String albumId) {
        syncAlbumMediaFromProvider(mLocalProvider, albumId);

        synchronized (mLock) {
            final String cloudProvider = mCloudProviderInfo.authority;
            syncAlbumMediaFromProvider(cloudProvider, albumId);
            // Should be a no-op. Cloud provider should already be set on the facade before an
            // Album Media is fetched.
            mDbFacade.setCloudProvider(cloudProvider);
        }
    }

    /**
     * Returns the supported cloud {@link CloudMediaProvider} infos.
     */
    public CloudProviderInfo getCloudProviderInfo(String authority) {
        for (CloudProviderInfo info : getSupportedCloudProviders()) {
            if (info.authority.equals(authority)) {
                return info;
            }
        }

        return CloudProviderInfo.EMPTY;
    }

    /**
     * Returns the supported cloud {@link CloudMediaProvider} authorities.
     */
    @VisibleForTesting
    List<CloudProviderInfo> getSupportedCloudProviders() {
        final List<CloudProviderInfo> result = new ArrayList<>();
        final PackageManager pm = mContext.getPackageManager();
        final Intent intent = new Intent(CloudMediaProviderContract.PROVIDER_INTERFACE);
        final List<ResolveInfo> providers = pm.queryIntentContentProviders(intent, /* flags */ 0);

        for (ResolveInfo info : providers) {
            ProviderInfo providerInfo = info.providerInfo;
            if (providerInfo.authority != null
                    && CloudMediaProviderContract.MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION.equals(
                            providerInfo.readPermission)) {
                result.add(new CloudProviderInfo(providerInfo.authority,
                                providerInfo.applicationInfo.uid));
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
            if (Objects.equals(mCloudProviderInfo.authority, authority)) {
                Log.w(TAG, "Cloud provider already set: " + authority);
                return true;
            }
        }

        final CloudProviderInfo newProviderInfo = getCloudProviderInfo(authority);
        if (authority == null || !newProviderInfo.isEmpty()) {
            synchronized (mLock) {
                setCloudProviderInfo(newProviderInfo);
                resetCachedMediaCollectionInfo(newProviderInfo.authority);

                // Disable cloud provider queries on the db until next sync
                // This will temporarily *clear* the cloud provider on the db facade and prevent
                // any queries from seeing cloud media until a sync where the cloud provider will be
                // reset on the facade
                mDbFacade.setCloudProvider(null);
            }

            Log.i(TAG, "Cloud provider changed successfully. Old: " + authority + ". New: "
                    + newProviderInfo.authority);
            return true;
        }

        Log.w(TAG, "Cloud provider not supported: " + authority);
        return false;
    }

    public String getCloudProvider() {
        synchronized (mLock) {
            return mCloudProviderInfo.authority;
        }
    }

    public String getLocalProvider() {
        return mLocalProvider;
    }

    public boolean isProviderEnabled(String authority) {
        if (mLocalProvider.equals(authority)) {
            return true;
        }

        synchronized (mLock) {
            if (!mCloudProviderInfo.isEmpty() && mCloudProviderInfo.authority.equals(authority)) {
                return true;
            }
        }

        return false;
    }

    public boolean isProviderEnabled(String authority, int uid) {
        if (uid == Process.myUid() && mLocalProvider.equals(authority)) {
            return true;
        }

        synchronized (mLock) {
            if (!mCloudProviderInfo.isEmpty() && uid == mCloudProviderInfo.uid
                    && mCloudProviderInfo.authority.equals(authority)) {
                return true;
            }
        }

        return false;
    }

    public boolean isProviderSupported(String authority, int uid) {
        if (uid == Process.myUid() && mLocalProvider.equals(authority)) {
            return true;
        }

        final List<CloudProviderInfo> infos = getSupportedCloudProviders();
        for (CloudProviderInfo info : infos) {
            if (info.uid == uid && info.authority.equals(authority)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Notifies about media events like inserts/updates/deletes from cloud and local providers and
     * syncs the changes in the background.
     *
     * There is a delay before executing the background sync to artificially throttle the burst
     * notifications.
     */
    public void notifyMediaEvent() {
        BackgroundThread.getHandler().removeCallbacks(mSyncAllMediaCallback);
        BackgroundThread.getHandler().postDelayed(mSyncAllMediaCallback, mSyncDelayMs);
    }


    private void syncAlbumMediaFromProvider(String authority, String albumId) {
        final SyncRequestParams params = getSyncAlbumRequestParams(authority);
        switch (params.syncType) {
            case SYNC_TYPE_ALBUM_MEDIA_RESET:
                executeSyncAlbumReset(authority, albumId);
                return;
            case SYNC_TYPE_ALBUM_MEDIA_FULL:
                executeSyncAlbumReset(authority, albumId);
                final Bundle queryArgs = new Bundle();
                queryArgs.putString(EXTRA_FILTER_ALBUM, albumId);
                executeSyncAddAlbum(authority, albumId, queryArgs /* queryArgs */);
                return;
            case SYNC_TYPE_NONE:
                return;
            default:
                throw new IllegalArgumentException(
                        "Unexpected sync type: " + params.syncType + " for album media");
        }
    }

    // TODO(b/190713331): Check extra_pages and extra_honored_args
    private void syncAllMediaFromProvider(String authority) {
        final SyncRequestParams params = getSyncRequestParams(authority);

        switch (params.syncType) {
            case SYNC_TYPE_MEDIA_RESET:
                // Odd! Can only happen if provider gave us unexpected MediaCollectionInfo
                // We reset the cloud media in the picker db
                executeSyncReset(authority);

                // And clear our cached MediaCollectionInfo, so that whenever the provider recovers,
                // we force a full sync
                resetCachedMediaCollectionInfo(authority);
                return;
            case SYNC_TYPE_MEDIA_FULL:
                executeSyncReset(authority);
                executeSyncAdd(authority, new Bundle() /* queryArgs */);

                // Commit sync position
                cacheMediaCollectionInfo(authority, params.latestMediaCollectionInfo);
                return;
            case SYNC_TYPE_MEDIA_INCREMENTAL:
                final Bundle queryArgs = new Bundle();
                queryArgs.putLong(EXTRA_SYNC_GENERATION, params.syncGeneration);

                executeSyncAdd(authority, queryArgs);
                executeSyncRemove(authority, queryArgs);

                // Commit sync position
                cacheMediaCollectionInfo(authority, params.latestMediaCollectionInfo);
                return;
            case SYNC_TYPE_NONE:
                return;
            default:
                throw new IllegalArgumentException("Unexpected sync type: " + params.syncType);
        }

        // TODO(b/190713331): Confirm that no more sync required?
    }

    private void executeSyncReset(String authority) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginResetMediaOperation(authority)) {
            final int writeCount = operation.execute(null /* cursor */);
            operation.setSuccess();
            Log.i(TAG, "SyncReset. Authority: " + authority +  ". Result count: " + writeCount);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to execute SyncReset.", e);
        }
    }

    private void executeSyncAlbumReset(String authority, String albumId) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginResetAlbumMediaOperation(authority, albumId)) {
            final int writeCount = operation.execute(null /* cursor */);
            operation.setSuccess();
            Log.i(TAG, "SyncResetAlbum. Authority: " + authority + ". AlbumId: " + albumId
                    + ". Result count: " + writeCount);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to execute SyncReset.", e);
        }
    }

    private void executeSyncAdd(String authority, Bundle queryArgs) {
        final Uri uri = getMediaUri(authority);
        Log.i(TAG, "Executing SyncAdd with authority: " + authority);
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginAddMediaOperation(authority)) {
            executePagedSync(uri, queryArgs, operation);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to execute SyncAdd.", e);
        }
    }

    private void executeSyncAddAlbum(String authority, String albumId, Bundle queryArgs) {
        final Uri uri = getMediaUri(authority);
        Log.i(TAG,
                "Executing SyncAddAlbum with authority: " + authority + "and albumId: " + albumId);
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginAddAlbumMediaOperation(authority, albumId)) {
            executePagedSync(uri, queryArgs, operation);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to execute SyncAddAlbum.", e);
        }
    }


    private void executeSyncRemove(String authority, Bundle queryArgs) {
        final Uri uri = getDeletedMediaUri(authority);
        Log.i(TAG, "Executing SyncRemove with authority: " + authority);
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginRemoveMediaOperation(authority)) {
            executePagedSync(uri, queryArgs, operation);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to execute SyncRemove.", e);
        }
    }

    private void setCloudProviderInfo(CloudProviderInfo info) {
        synchronized (mLock) {
            mCloudProviderInfo = info;
        }

        final SharedPreferences.Editor editor = mUserPrefs.edit();

        if (info.isEmpty()) {
            editor.remove(PREFS_KEY_CLOUD_PROVIDER);
            editor.remove(PREFS_KEY_CLOUD_PROVIDER_UID);
        } else {
            editor.putString(PREFS_KEY_CLOUD_PROVIDER, info.authority);
            editor.putInt(PREFS_KEY_CLOUD_PROVIDER_UID, info.uid);
        }

        editor.apply();
    }

    private void cacheMediaCollectionInfo(String authority, Bundle bundle) {
        if (authority == null) {
            Log.d(TAG, "Ignoring cache media info for null authority with bundle: " + bundle);
            return;
        }

        final SharedPreferences.Editor editor = mSyncPrefs.edit();

        if (bundle == null) {
            editor.remove(getPrefsKey(authority, MediaCollectionInfo.MEDIA_COLLECTION_ID));
            editor.remove(getPrefsKey(authority, MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION));
        } else {
            final String collectionId = bundle.getString(MediaCollectionInfo.MEDIA_COLLECTION_ID);
            final long generation = bundle.getLong(
                    MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);

            editor.putString(getPrefsKey(authority, MediaCollectionInfo.MEDIA_COLLECTION_ID),
                    collectionId);
            editor.putLong(getPrefsKey(authority, MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION),
                    generation);
        }

        editor.apply();
    }

    private void resetCachedMediaCollectionInfo(String authority) {
        cacheMediaCollectionInfo(authority, /* bundle */ null);
    }

    private Bundle getCachedMediaCollectionInfo(String authority) {
        final Bundle bundle = new Bundle();

        final String collectionId = mSyncPrefs.getString(
                getPrefsKey(authority, MediaCollectionInfo.MEDIA_COLLECTION_ID),
                /* default */ null);
        final long generation = mSyncPrefs.getLong(
                getPrefsKey(authority, MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION),
                /* default */ -1);

        bundle.putString(MediaCollectionInfo.MEDIA_COLLECTION_ID, collectionId);
        bundle.putLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION, generation);

        return bundle;
    }

    private Bundle getLatestMediaCollectionInfo(String authority) {
        try {
            return mContext.getContentResolver().call(getMediaCollectionInfoUri(authority),
                    CloudMediaProviderContract.METHOD_GET_MEDIA_COLLECTION_INFO, /* arg */ null,
                    /* extras */ null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch latest media info from authority: " + authority, e);
            return Bundle.EMPTY;
        }
    }

    @SyncType
    private SyncRequestParams getSyncRequestParams(String authority) {
        if (authority == null) {
            // Only cloud authority can be null
            Log.d(TAG, "Fetching SyncRequestParams. Null cloud authority. Result: SYNC_TYPE_RESET");
            return SyncRequestParams.forResetMedia();
        }

        final Bundle cachedMediaCollectionInfo = getCachedMediaCollectionInfo(authority);
        final Bundle latestMediaCollectionInfo = getLatestMediaCollectionInfo(authority);

        final String latestCollectionId =
                latestMediaCollectionInfo.getString(MediaCollectionInfo.MEDIA_COLLECTION_ID);
        final long latestGeneration =
                latestMediaCollectionInfo.getLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);

        final String cachedCollectionId =
                cachedMediaCollectionInfo.getString(MediaCollectionInfo.MEDIA_COLLECTION_ID);
        final long cachedGeneration = cachedMediaCollectionInfo.getLong(
                MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);

        Log.d(TAG, "Fetching SyncRequestParams. Authority: " + authority
                + ". LatestMediaCollectionInfo: " + latestMediaCollectionInfo
                + ". CachedMediaCollectionInfo: " + cachedMediaCollectionInfo);

        if (TextUtils.isEmpty(latestCollectionId) || latestGeneration < 0) {
            // If results from |latestMediaCollectionInfo| are unexpected, we reset the
            // cloud provider
            Log.w(TAG, "SyncRequestParams. Authority: " + authority
                    + ". Result: SYNC_TYPE_RESET. Unexpected result: " + latestMediaCollectionInfo);
            return SyncRequestParams.forResetMedia();
        }

        if (!Objects.equals(latestCollectionId, cachedCollectionId)) {
            Log.d(TAG, "SyncRequestParams. Authority: " + authority + ". Result: SYNC_TYPE_FULL");
            return SyncRequestParams.forFullMedia(latestMediaCollectionInfo);
        }

        if (cachedGeneration == latestGeneration) {
            Log.d(TAG, "SyncRequestParams. Authority: " + authority + ". Result: SYNC_TYPE_NONE");
            return SyncRequestParams.forNone();
        }

        Log.d(TAG, "SyncRequestParams. Authority: " + authority
                + ". Result: SYNC_TYPE_INCREMENTAL");
        return SyncRequestParams.forIncremental(cachedGeneration, latestMediaCollectionInfo);
    }


    @SyncType
    private SyncRequestParams getSyncAlbumRequestParams(String authority) {
        if (authority == null) {
            // Only cloud authority can be null
            Log.d(TAG,
                    "Fetching SyncRequestParams. Null cloud authority. Result: "
                            + "SYNC_TYPE_ALBUM_MEDIA_RESET");
            return SyncRequestParams.forResetAlbumMedia();
        }

        Log.d(TAG, "SyncRequestParams. Authority: " + authority
                + ". Result: SYNC_TYPE_ALBUM_MEDIA_FULL");
        return SyncRequestParams.forFullAlbumMedia();
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

    // TODO(b/195008834): Verify Cursor extras: extra_honored_args and extra_media_collection_id
    private void executePagedSync(Uri uri, Bundle queryArgs,
            PickerDbFacade.DbWriteOperation dbWriteOperation) {
        int cursorCount = 0;
        int totalRowcount = 0;
        // Set to check the uniqueness of tokens across pages.
        Set<String> tokens = new ArraySet<>();

        String nextPageToken = null;
        do {
            if (nextPageToken != null) {
                queryArgs.putString(EXTRA_PAGE_TOKEN, nextPageToken);
            }

            try (Cursor cursor = query(uri, queryArgs)) {
                Bundle extras = cursor.getExtras();
                nextPageToken = extractAndValidateNewToken(extras, tokens);

                int writeCount = dbWriteOperation.execute(cursor);

                totalRowcount += writeCount;
                cursorCount += cursor.getCount();
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to execute paginated query.", e);
                // Caught exception, abort the DB update and the queries for subsequent pages.
                return;
            }
        } while (nextPageToken != null);

        dbWriteOperation.setSuccess();
        Log.i(TAG, "Paged sync successful. QueryArgs: " + queryArgs + ". Result count: "
                + totalRowcount + ". Cursor count: " + cursorCount);
    }

    private static String extractAndValidateNewToken(Bundle bundle, Set<String> oldTokenSet) {
        String token = null;
        if (bundle != null && bundle.containsKey(EXTRA_PAGE_TOKEN)) {
            token = bundle.getString(EXTRA_PAGE_TOKEN);

            if (oldTokenSet.contains(token)) {
                // We have found the same token for multiple pages, throw exception.
                throw new IllegalStateException("Found the same token for multiple pages.");
            } else {
                oldTokenSet.add(token);
            }
        }
        return token;
    }

    @VisibleForTesting
    static class CloudProviderInfo {
        static final CloudProviderInfo EMPTY = new CloudProviderInfo();
        private final String authority;
        private final int uid;

        private CloudProviderInfo() {
            this.authority = DEFAULT_CLOUD_PROVIDER_PKG;
            this.uid = DEFAULT_CLOUD_PROVIDER_UID;
        }

        CloudProviderInfo(String authority, int uid) {
            Objects.requireNonNull(authority);

            this.authority = authority;
            this.uid = uid;
        }

        boolean isEmpty() {
            return equals(EMPTY);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;

            CloudProviderInfo that = (CloudProviderInfo) obj;

            return Objects.equals(authority, that.authority) &&
                    Objects.equals(uid, that.uid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(authority, uid);
        }
    }

    private static class SyncRequestParams {
        private static final SyncRequestParams SYNC_REQUEST_NONE =
                new SyncRequestParams(SYNC_TYPE_NONE);
        private static final SyncRequestParams SYNC_REQUEST_MEDIA_RESET =
                new SyncRequestParams(SYNC_TYPE_MEDIA_RESET);

        private final int syncType;
        // Only valid for SYNC_TYPE_INCREMENTAL
        private final long syncGeneration;
        // Only valid for SYNC_TYPE_[INCREMENTAL|FULL]
        private final Bundle latestMediaCollectionInfo;

        private SyncRequestParams(@SyncType int syncType) {
            this(syncType, /* syncGeneration */ 0, /* latestMediaCollectionInfo */ null);
        }

        private SyncRequestParams(@SyncType int syncType, long syncGeneration,
                Bundle latestMediaCollectionInfo) {
            this.syncType = syncType;
            this.syncGeneration = syncGeneration;
            this.latestMediaCollectionInfo = latestMediaCollectionInfo;
        }

        static SyncRequestParams forNone() {
            return SYNC_REQUEST_NONE;
        }

        static SyncRequestParams forResetMedia() {
            return SYNC_REQUEST_MEDIA_RESET;
        }

        static SyncRequestParams forResetAlbumMedia() {
            return new SyncRequestParams(SYNC_TYPE_ALBUM_MEDIA_RESET);
        }

        static SyncRequestParams forFullMedia(Bundle latestMediaCollectionInfo) {
            return new SyncRequestParams(SYNC_TYPE_MEDIA_FULL, /* generation */ 0,
                    latestMediaCollectionInfo);
        }

        static SyncRequestParams forFullAlbumMedia() {
            return new SyncRequestParams(SYNC_TYPE_ALBUM_MEDIA_FULL);
        }

        static SyncRequestParams forIncremental(long generation, Bundle latestMediaCollectionInfo) {
            return new SyncRequestParams(SYNC_TYPE_MEDIA_INCREMENTAL, generation,
                    latestMediaCollectionInfo);
        }
    }
}
