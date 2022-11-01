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

import static android.content.ContentResolver.EXTRA_HONORED_ARGS;
import static android.provider.CloudMediaProviderContract.EXTRA_ALBUM_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_TOKEN;
import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID;

import static com.android.providers.media.PickerUriResolver.getDeletedMediaUri;
import static com.android.providers.media.PickerUriResolver.getMediaCollectionInfoUri;
import static com.android.providers.media.PickerUriResolver.getMediaUri;

import android.annotation.IntDef;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.Trace;
import android.os.storage.StorageManager;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.CloudProviderInfo;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.metrics.PhotoPickerUiEventLogger;
import com.android.providers.media.photopicker.util.CloudProviderUtils;
import com.android.providers.media.util.ForegroundThread;
import com.android.providers.media.util.StringUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances on the device
 * into the picker db.
 */
public class PickerSyncController {
    private static final String TAG = "PickerSyncController";

    private static final String PREFS_KEY_CLOUD_PROVIDER_AUTHORITY = "cloud_provider_authority";
    private static final String PREFS_KEY_CLOUD_PROVIDER_PENDING_NOTIFICATION =
            "cloud_provider_pending_notification";
    private static final String PREFS_KEY_IS_USER_CLOUD_MEDIA_AWARE =
            "user_aware_about_cloud_media_app_settings";
    private static final String PREFS_KEY_CLOUD_PREFIX = "cloud_provider:";
    private static final String PREFS_KEY_LOCAL_PREFIX = "local_provider:";

    private static final String PICKER_USER_PREFS_FILE_NAME = "picker_user_prefs";
    public static final String PICKER_SYNC_PREFS_FILE_NAME = "picker_sync_prefs";
    public static final String LOCAL_PICKER_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker";

    private static final int SYNC_TYPE_NONE = 0;
    private static final int SYNC_TYPE_MEDIA_INCREMENTAL = 1;
    private static final int SYNC_TYPE_MEDIA_FULL = 2;
    private static final int SYNC_TYPE_MEDIA_RESET = 3;

    @IntDef(flag = false, prefix = { "SYNC_TYPE_" }, value = {
            SYNC_TYPE_NONE,
            SYNC_TYPE_MEDIA_INCREMENTAL,
            SYNC_TYPE_MEDIA_FULL,
            SYNC_TYPE_MEDIA_RESET,
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface SyncType {}

    private final Context mContext;
    private final ConfigStore mConfigStore;
    private final PickerDbFacade mDbFacade;
    private final SharedPreferences mSyncPrefs;
    private final SharedPreferences mUserPrefs;
    private final String mLocalProvider;
    private final long mSyncDelayMs;
    private final Runnable mSyncAllMediaCallback;

    private final PhotoPickerUiEventLogger mLogger;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private CloudProviderInfo mCloudProviderInfo;

    public PickerSyncController(@NonNull Context context, @NonNull PickerDbFacade dbFacade,
            @NonNull ConfigStore configStore) {
        this(context, dbFacade, configStore, LOCAL_PICKER_PROVIDER_AUTHORITY);
    }

    @VisibleForTesting
    public PickerSyncController(@NonNull Context context, @NonNull PickerDbFacade dbFacade,
            @NonNull ConfigStore configStore, @NonNull String localProvider) {
        mContext = context;
        mConfigStore = configStore;
        mSyncPrefs = mContext.getSharedPreferences(PICKER_SYNC_PREFS_FILE_NAME,
                Context.MODE_PRIVATE);
        mUserPrefs = mContext.getSharedPreferences(PICKER_USER_PREFS_FILE_NAME,
                Context.MODE_PRIVATE);
        mDbFacade = dbFacade;
        mLocalProvider = localProvider;
        mSyncAllMediaCallback = this::syncAllMedia;
        mLogger = new PhotoPickerUiEventLogger();

        final String cachedAuthority = mUserPrefs.getString(
                PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, null);

        mSyncDelayMs = configStore.getPickerSyncDelayMs();

        final CloudProviderInfo defaultInfo = getDefaultCloudProviderInfo(cachedAuthority,
                isUserAwareAboutCloudMediaAppSettings());

        if (Objects.equals(defaultInfo.authority, cachedAuthority)) {
            // Just set it without persisting since it's not changing and persisting would
            // notify the user that cloud media is now available
            mCloudProviderInfo = defaultInfo;
        } else {
            // Persist it so that we notify the user that cloud media is now available
            persistCloudProviderInfo(defaultInfo);
        }

        Log.d(TAG, "Initialized cloud provider to: " + mCloudProviderInfo.authority);
    }

    /**
     * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances
     */
    public void syncAllMedia() {
        Trace.beginSection(traceSectionName("syncAllMedia"));
        try {
            syncAllMediaFromProvider(mLocalProvider, /* isLocal */ true, /* retryOnFailure */ true);

            synchronized (mLock) {
                final String cloudProvider = mCloudProviderInfo.authority;
                syncAllMediaFromProvider(cloudProvider, /* isLocal */ false,
                        /* retryOnFailure */ true);

                // Reset the album_media table every time we sync all media
                // TODO(sergeynv@): do we really need to reset for both providers?
                resetAlbumMedia();

                // Set the latest cloud provider on the facade
                mDbFacade.setCloudProvider(cloudProvider);
            }
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Syncs album media from the local and currently enabled cloud {@link CloudMediaProvider}
     * instances
     */
    public void syncAlbumMedia(String albumId, boolean isLocal) {
        if (isLocal) {
            syncAlbumMediaFromProvider(mLocalProvider, /* isLocal */ true, albumId);
        } else {
            synchronized (mLock) {
                syncAlbumMediaFromProvider(
                        mCloudProviderInfo.authority, /* isLocal */ false, albumId);
            }
        }
    }

    private void resetAlbumMedia() {
        executeSyncAlbumReset(mLocalProvider, /* isLocal */ true, /* albumId */ null);

        synchronized (mLock) {
            final String cloudProvider = mCloudProviderInfo.authority;
            executeSyncAlbumReset(cloudProvider, /* isLocal */ false, /* albumId */ null);
        }
    }

    private void resetAllMedia(String authority, boolean isLocal) {
        executeSyncReset(authority, isLocal);
        resetCachedMediaCollectionInfo(authority, isLocal);
    }

    @NonNull
    private CloudProviderInfo getCloudProviderInfo(String authority, boolean ignoreAllowlist) {
        if (authority == null) {
            return CloudProviderInfo.EMPTY;
        }

        final List<CloudProviderInfo> availableProviders = ignoreAllowlist
                ? CloudProviderUtils.getAllAvailableCloudProviders(mContext, mConfigStore)
                : CloudProviderUtils.getAvailableCloudProviders(mContext, mConfigStore);

        for (CloudProviderInfo info : availableProviders) {
            if (info.authority.equals(authority)) {
                return info;
            }
        }

        return CloudProviderInfo.EMPTY;
    }

    /**
     * @return list of available <b>and</b> allowlisted {@link CloudMediaProvider}-s.
     */
    @VisibleForTesting
    List<CloudProviderInfo> getAvailableCloudProviders() {
        return CloudProviderUtils.getAvailableCloudProviders(mContext, mConfigStore);
    }

    /**
     * Enables a provider with {@code authority} as the default cloud {@link CloudMediaProvider}.
     * If {@code authority} is set to {@code null}, it simply clears the cloud provider.
     *
     * Note, that this doesn't sync the new provider after switching, however, no cloud items will
     * be available from the picker db until the next sync. Callers should schedule a sync in the
     * background after switching providers.
     *
     * @return {@code true} if the provider was successfully enabled or cleared, {@code false}
     * otherwise
     */
    public boolean setCloudProvider(String authority) {
        Trace.beginSection(traceSectionName("setCloudProvider"));
        try {
            return setCloudProviderInternal(authority, /* ignoreAllowlist */ false);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Set cloud provider ignoring allowlist.
     */
    @VisibleForTesting
    public void forceSetCloudProvider(String authority) {
        Trace.beginSection(traceSectionName("forceSetCloudProvider"));
        try {
            setCloudProviderInternal(authority, /* ignoreAllowlist */ true);
        } finally {
            Trace.endSection();
        }
    }

    private boolean setCloudProviderInternal(String authority, boolean ignoreAllowList) {
        synchronized (mLock) {
            if (Objects.equals(mCloudProviderInfo.authority, authority)) {
                Log.w(TAG, "Cloud provider already set: " + authority);
                return true;
            }
        }

        final CloudProviderInfo newProviderInfo = getCloudProviderInfo(authority, ignoreAllowList);
        if (authority == null || !newProviderInfo.isEmpty()) {
            synchronized (mLock) {
                final String oldAuthority = mCloudProviderInfo.authority;
                persistCloudProviderInfo(newProviderInfo);
                resetCachedMediaCollectionInfo(newProviderInfo.authority, /* isLocal */ false);

                // Disable cloud provider queries on the db until next sync
                // This will temporarily *clear* the cloud provider on the db facade and prevent
                // any queries from seeing cloud media until a sync where the cloud provider will be
                // reset on the facade
                mDbFacade.setCloudProvider(null);

                // TODO(b/242897322): Log from PickerViewModel using its InstanceId when relevant
                mLogger.logPickerCloudProviderChanged(newProviderInfo.uid,
                        newProviderInfo.packageName);
                Log.i(TAG, "Cloud provider changed successfully. Old: "
                        + oldAuthority + ". New: " + newProviderInfo.authority);
            }

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

        // TODO(b/232738117): Enforce allow list here. This works around some CTS failure late in
        // Android T. The current implementation is fine since cloud providers is only supported
        // for app developers testing.
        final List<CloudProviderInfo> infos =
                CloudProviderUtils.getAllAvailableCloudProviders(mContext, mConfigStore);
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

    /**
     * Notifies about package removal
     */
    public void notifyPackageRemoval(String packageName) {
        synchronized (mLock) {
            if (mCloudProviderInfo.matches(packageName)) {
                Log.i(TAG, "Package " + packageName
                        + " is the current cloud provider and got removed");
                setCloudProvider(null);
            }
        }
    }

    /**
     * Notifies about picker UI launched
     */
    public void notifyPickerLaunch() {
        final String packageName;
        synchronized (mLock) {
            packageName = mCloudProviderInfo.packageName;
        }

        final boolean hasPendingNotification = mUserPrefs.getBoolean(
                PREFS_KEY_CLOUD_PROVIDER_PENDING_NOTIFICATION, /* defaultValue */ false);

        if (!hasPendingNotification || (packageName == null)) {
            Log.d(TAG, "No pending UI notification");
            return;
        }

        // Offload showing the UI on a fg thread to avoid the expensive binder request
        // to fetch the app name blocking the picker launch
        ForegroundThread.getHandler().post(() -> {
            Log.i(TAG, "Cloud media now available in the picker");

            final PackageManager pm = mContext.getPackageManager();
            String appName = packageName;
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                appName = (String) pm.getApplicationLabel(appInfo);
            } catch (final NameNotFoundException e) {
                Log.i(TAG, "Failed to get appName for package: " + packageName);
            }

            final String message = mContext.getResources().getString(R.string.picker_cloud_sync,
                    appName);
            Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
        });

        // Clear the notification
        updateBooleanUserPref(PREFS_KEY_CLOUD_PROVIDER_PENDING_NOTIFICATION, false);
    }

    /**
     * Notifies about cloud media app banner displayed in picker UI
     */
    @VisibleForTesting
    void notifyUserCloudMediaAware() {
        updateBooleanUserPref(PREFS_KEY_IS_USER_CLOUD_MEDIA_AWARE, true);
    }

    private void updateBooleanUserPref(String key, boolean value) {
        final SharedPreferences.Editor editor = mUserPrefs.edit();
        editor.putBoolean(key, value);
        editor.apply();
    }

    /**
     * Clears the flag - user aware about cloud media app settings
     */
    @VisibleForTesting
    void clearUserAwareAboutCloudMediaAppSettingsFlag() {
        final SharedPreferences.Editor editor = mUserPrefs.edit();
        editor.remove(PREFS_KEY_IS_USER_CLOUD_MEDIA_AWARE);
        editor.apply();
    }

    private void syncAlbumMediaFromProvider(String authority, boolean isLocal, String albumId) {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(EXTRA_ALBUM_ID, albumId);

        Trace.beginSection(traceSectionName("syncAlbumMediaFromProvider", isLocal));
        try {
            executeSyncAlbumReset(authority, isLocal, albumId);

            if (authority != null) {
                executeSyncAddAlbum(authority, isLocal, albumId, queryArgs);
            }
        } catch (RuntimeException e) {
            // Unlike syncAllMediaFromProvider, we don't retry here because any errors would have
            // occurred in fetching all the album_media since incremental sync is not supported.
            // A full sync is therefore unlikely to resolve any issue
            Log.e(TAG, "Failed to sync album media", e);
        } finally {
            Trace.endSection();
        }
    }

    private void syncAllMediaFromProvider(String authority, boolean isLocal,
            boolean retryOnFailure) {
        Trace.beginSection(traceSectionName("syncAllMediaFromProvider", isLocal));
        try {
            final SyncRequestParams params = getSyncRequestParams(authority, isLocal);

            switch (params.syncType) {
                case SYNC_TYPE_MEDIA_RESET:
                    // Can only happen when |authority| has been set to null and we need to clean up
                    resetAllMedia(authority, isLocal);
                    break;
                case SYNC_TYPE_MEDIA_FULL:
                    resetAllMedia(authority, isLocal);

                    // Pass a mutable empty bundle intentionally because it might be populated with
                    // the next page token as part of a query to a cloud provider supporting
                    // pagination
                    executeSyncAdd(authority, isLocal, params.getMediaCollectionId(),
                            /* isIncrementalSync */ false, /* queryArgs */ new Bundle());

                    // Commit sync position
                    cacheMediaCollectionInfo(authority, isLocal, params.latestMediaCollectionInfo);
                    break;
                case SYNC_TYPE_MEDIA_INCREMENTAL:
                    final Bundle queryArgs = new Bundle();
                    queryArgs.putLong(EXTRA_SYNC_GENERATION, params.syncGeneration);

                    executeSyncAdd(authority, isLocal, params.getMediaCollectionId(),
                            /* isIncrementalSync */ true, queryArgs);
                    executeSyncRemove(authority, isLocal, params.getMediaCollectionId(), queryArgs);

                    // Commit sync position
                    cacheMediaCollectionInfo(authority, isLocal, params.latestMediaCollectionInfo);
                    break;
                case SYNC_TYPE_NONE:
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected sync type: " + params.syncType);
            }
        } catch (RuntimeException e) {
            // Reset all media for the cloud provider in case it never succeeds
            resetAllMedia(authority, isLocal);

            // Attempt a full sync. If this fails, the db table would have been reset,
            // flushing all old content and leaving the picker UI empty.
            Log.e(TAG, "Failed to sync all media. Reset media and retry: " + retryOnFailure, e);
            if (retryOnFailure) {
                syncAllMediaFromProvider(authority, isLocal, /* retryOnFailure */ false);
            }
        } finally {
            Trace.endSection();
        }
    }

    private void executeSyncReset(String authority, boolean isLocal) {
        Log.i(TAG, "Executing SyncReset. isLocal: " + isLocal + ". authority: " + authority);

        Trace.beginSection(traceSectionName("executeSyncReset", isLocal));
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginResetMediaOperation(authority)) {
            final int writeCount = operation.execute(null /* cursor */);
            operation.setSuccess();

            Log.i(TAG, "SyncReset. isLocal:" + isLocal + ". authority: " + authority
                    +  ". result count: " + writeCount);
        } finally {
            Trace.endSection();
        }
    }

    private void executeSyncAlbumReset(String authority, boolean isLocal, String albumId) {
        Log.i(TAG, "Executing SyncAlbumReset."
                + " isLocal: " + isLocal + ". authority: " + authority + ". albumId: " + albumId);

        Trace.beginSection(traceSectionName("executeSyncAlbumReset", isLocal));
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginResetAlbumMediaOperation(authority, albumId)) {
            final int writeCount = operation.execute(null /* cursor */);
            operation.setSuccess();

            Log.i(TAG, "Successfully executed SyncResetAlbum. authority: " + authority
                    + ". albumId: " + albumId + ". Result count: " + writeCount);
        } finally {
            Trace.endSection();
        }
    }

    private void executeSyncAdd(String authority, boolean isLocal,
            String expectedMediaCollectionId, boolean isIncrementalSync, Bundle queryArgs) {
        final Uri uri = getMediaUri(authority);
        final List<String> expectedHonoredArgs = new ArrayList<>();
        if (isIncrementalSync) {
            expectedHonoredArgs.add(EXTRA_SYNC_GENERATION);
        }

        Log.i(TAG, "Executing SyncAdd. isLocal: " + isLocal + ". authority: " + authority);

        Trace.beginSection(traceSectionName("executeSyncAdd", isLocal));
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginAddMediaOperation(authority)) {
            executePagedSync(uri, expectedMediaCollectionId, expectedHonoredArgs, queryArgs,
                    operation);
        } finally {
            Trace.endSection();
        }
    }

    private void executeSyncAddAlbum(String authority, boolean isLocal,
            String albumId, Bundle queryArgs) {
        final Uri uri = getMediaUri(authority);

        Log.i(TAG, "Executing SyncAddAlbum. "
                + "isLocal: " + isLocal + ". authority: " + authority + ". albumId: " + albumId);

        Trace.beginSection(traceSectionName("executeSyncAddAlbum", isLocal));
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginAddAlbumMediaOperation(authority, albumId)) {

            // We don't need to validate the mediaCollectionId for album_media sync since it's
            // always a full sync
            executePagedSync(uri, /* mediaCollectionId */ null, Arrays.asList(EXTRA_ALBUM_ID),
                    queryArgs, operation);
        } finally {
            Trace.endSection();
        }
    }

    private void executeSyncRemove(String authority, boolean isLocal,
            String mediaCollectionId, Bundle queryArgs) {
        final Uri uri = getDeletedMediaUri(authority);

        Log.i(TAG, "Executing SyncRemove. isLocal: " + isLocal + ". authority: " + authority);

        Trace.beginSection(traceSectionName("executeSyncRemove", isLocal));
        try (PickerDbFacade.DbWriteOperation operation =
                     mDbFacade.beginRemoveMediaOperation(authority)) {
            executePagedSync(uri, mediaCollectionId, Arrays.asList(EXTRA_SYNC_GENERATION),
                    queryArgs, operation);
        } finally {
            Trace.endSection();
        }
    }

    private void persistCloudProviderInfo(CloudProviderInfo info) {
        synchronized (mLock) {
            mCloudProviderInfo = info;
        }

        final String authority = info.authority;
        final SharedPreferences.Editor editor = mUserPrefs.edit();

        if (info.isEmpty()) {
            editor.remove(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY);
            editor.putBoolean(PREFS_KEY_CLOUD_PROVIDER_PENDING_NOTIFICATION, false);
        } else {
            editor.putString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, authority);
            editor.putBoolean(PREFS_KEY_CLOUD_PROVIDER_PENDING_NOTIFICATION, true);
        }

        editor.apply();

        if (SdkLevel.isAtLeastT()) {
            try {
                StorageManager sm = mContext.getSystemService(StorageManager.class);
                sm.setCloudMediaProvider(authority);
            } catch (SecurityException e) {
                // When run as part of the unit tests, the notification fails because only the
                // MediaProvider uid can notify
                Log.w(TAG, "Failed to notify the system of cloud provider update to: " + authority);
            }
        }

        Log.d(TAG, "Updated cloud provider to: " + authority);
    }

    private void cacheMediaCollectionInfo(String authority, boolean isLocal, Bundle bundle) {
        if (authority == null) {
            Log.d(TAG, "Ignoring cache media info for null authority with bundle: " + bundle);
            return;
        }

        Trace.beginSection(traceSectionName("cacheMediaCollectionInfo", isLocal));
        try {
            final SharedPreferences.Editor editor = mSyncPrefs.edit();
            if (bundle == null) {
                editor.remove(getPrefsKey(isLocal, MEDIA_COLLECTION_ID));
                editor.remove(getPrefsKey(isLocal, LAST_MEDIA_SYNC_GENERATION));
            } else {
                final String collectionId = bundle.getString(MEDIA_COLLECTION_ID);
                final long generation = bundle.getLong(LAST_MEDIA_SYNC_GENERATION);

                editor.putString(getPrefsKey(isLocal, MEDIA_COLLECTION_ID), collectionId);
                editor.putLong(getPrefsKey(isLocal, LAST_MEDIA_SYNC_GENERATION), generation);
            }
            editor.apply();
        } finally {
            Trace.endSection();
        }
    }

    private void resetCachedMediaCollectionInfo(String authority, boolean isLocal) {
        cacheMediaCollectionInfo(authority, isLocal, /* bundle */ null);
    }

    private Bundle getCachedMediaCollectionInfo(boolean isLocal) {
        final Bundle bundle = new Bundle();

        final String collectionId = mSyncPrefs.getString(
                getPrefsKey(isLocal, MEDIA_COLLECTION_ID), /* default */ null);
        final long generation = mSyncPrefs.getLong(
                getPrefsKey(isLocal, LAST_MEDIA_SYNC_GENERATION), /* default */ -1);

        bundle.putString(MEDIA_COLLECTION_ID, collectionId);
        bundle.putLong(LAST_MEDIA_SYNC_GENERATION, generation);

        return bundle;
    }

    private Bundle getLatestMediaCollectionInfo(String authority) {
        return mContext.getContentResolver().call(getMediaCollectionInfoUri(authority),
                CloudMediaProviderContract.METHOD_GET_MEDIA_COLLECTION_INFO, /* arg */ null,
                /* extras */ null);
    }

    @SyncType
    private SyncRequestParams getSyncRequestParams(String authority, boolean isLocal) {
        if (authority == null) {
            // Only cloud authority can be null
            Log.d(TAG, "Fetching SyncRequestParams. Null cloud authority. Result: SYNC_TYPE_RESET");
            return SyncRequestParams.forResetMedia();
        }

        final Bundle cachedMediaCollectionInfo = getCachedMediaCollectionInfo(isLocal);
        final Bundle latestMediaCollectionInfo = getLatestMediaCollectionInfo(authority);

        final String latestCollectionId = latestMediaCollectionInfo.getString(MEDIA_COLLECTION_ID);
        final long latestGeneration = latestMediaCollectionInfo.getLong(LAST_MEDIA_SYNC_GENERATION);

        final String cachedCollectionId = cachedMediaCollectionInfo.getString(MEDIA_COLLECTION_ID);
        final long cachedGeneration = cachedMediaCollectionInfo.getLong(LAST_MEDIA_SYNC_GENERATION);

        Log.d(TAG, "Fetching SyncRequestParams. Islocal: " + isLocal
                + ". Authority: " + authority
                + ". LatestMediaCollectionInfo: " + latestMediaCollectionInfo
                + ". CachedMediaCollectionInfo: " + cachedMediaCollectionInfo);

        if (TextUtils.isEmpty(latestCollectionId) || latestGeneration < 0) {
            throw new IllegalStateException("Unexpected media collection info. mediaCollectionId: "
                    + latestCollectionId + ". lastMediaSyncGeneration: " + latestGeneration);
        }

        if (!Objects.equals(latestCollectionId, cachedCollectionId)) {
            Log.d(TAG, "SyncRequestParams. Islocal: " + isLocal + ". Authority: " + authority
                    + ". Result: SYNC_TYPE_FULL");
            return SyncRequestParams.forFullMedia(latestMediaCollectionInfo);
        }

        if (cachedGeneration == latestGeneration) {
            Log.d(TAG, "SyncRequestParams. Islocal: " + isLocal + ". Authority: " + authority
                    + ". Result: SYNC_TYPE_NONE");
            return SyncRequestParams.forNone();
        }

        Log.d(TAG, "SyncRequestParams. Islocal: " + isLocal + ". Authority: " + authority
                + ". Result: SYNC_TYPE_INCREMENTAL");
        return SyncRequestParams.forIncremental(cachedGeneration, latestMediaCollectionInfo);
    }

    private String getPrefsKey(boolean isLocal, String key) {
        return (isLocal ? PREFS_KEY_LOCAL_PREFIX : PREFS_KEY_CLOUD_PREFIX) + key;
    }

    private Cursor query(Uri uri, Bundle extras) {
        return mContext.getContentResolver().query(uri, /* projection */ null, extras,
                /* cancellationSignal */ null);
    }

    private void executePagedSync(Uri uri, String expectedMediaCollectionId,
            List<String> expectedHonoredArgs, Bundle queryArgs,
            PickerDbFacade.DbWriteOperation dbWriteOperation) {
        Trace.beginSection(traceSectionName("executePagedSync"));
        try {
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
                    nextPageToken = validateCursor(cursor, expectedMediaCollectionId,
                            expectedHonoredArgs, tokens);

                    int writeCount = dbWriteOperation.execute(cursor);

                    totalRowcount += writeCount;
                    cursorCount += cursor.getCount();
                }
            } while (nextPageToken != null);

            dbWriteOperation.setSuccess();
            Log.i(TAG, "Paged sync successful. QueryArgs: " + queryArgs + ". Result count: "
                    + totalRowcount + ". Cursor count: " + cursorCount);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Get the default {@link CloudProviderInfo} at {@link PickerSyncController} construction
     */
    @VisibleForTesting
    CloudProviderInfo getDefaultCloudProviderInfo(String cachedProvider,
            boolean isUserAwareAboutCloudMediaAppSettings) {
        if (cachedProvider == null && isUserAwareAboutCloudMediaAppSettings) {
            Log.i(TAG, "Skipping default cloud provider selection since the user has made an "
                    + "explicit empty choice");
            return CloudProviderInfo.EMPTY;
        }

        final List<CloudProviderInfo> infos = getAvailableCloudProviders();

        if (infos.size() == 1) {
            Log.i(TAG, "Only 1 cloud provider found, hence "
                    + infos.get(0).authority + " is the default");
            return infos.get(0);
        } else {
            final String defaultCloudProviderAuthority = StringUtils.getStringConfig(
                mContext, R.string.config_default_cloud_provider_authority);
            Log.i(TAG, "Found multiple cloud providers but OEM default is: "
                    + defaultCloudProviderAuthority);

            if (cachedProvider != null) {
                for (CloudProviderInfo info : infos) {
                    if (info.authority.equals(cachedProvider)) {
                        return info;
                    }
                }
            }

            if (defaultCloudProviderAuthority != null) {
                for (CloudProviderInfo info : infos) {
                    if (info.authority.equals(defaultCloudProviderAuthority)) {
                        return info;
                    }
                }
            }
        }

        // No default set or default not installed
        return CloudProviderInfo.EMPTY;
    }

    /**
     * @return the value of the user pref
     * {@link PREFS_KEY_IS_USER_CLOUD_MEDIA_AWARE} with the default value as
     * {@code false}
     */
    @VisibleForTesting
    boolean isUserAwareAboutCloudMediaAppSettings() {
        return mUserPrefs.getBoolean(PREFS_KEY_IS_USER_CLOUD_MEDIA_AWARE,
                /* defaultValue */ false);
    }

    private static String traceSectionName(@NonNull String method) {
        return "PSC." + method;
    }

    private static String traceSectionName(@NonNull String method, boolean isLocal) {
        return traceSectionName(method)
                + "[" + (isLocal ? "local" : "cloud") + ']';
    }

    private static String validateCursor(Cursor cursor, String expectedMediaCollectionId,
            List<String> expectedHonoredArgs, Set<String> usedPageTokens) {
        final Bundle bundle = cursor.getExtras();

        if (bundle == null) {
            throw new IllegalStateException("Unable to verify the media collection id");
        }

        final String mediaCollectionId = bundle.getString(EXTRA_MEDIA_COLLECTION_ID);
        final String pageToken = bundle.getString(EXTRA_PAGE_TOKEN);
        List<String> honoredArgs = bundle.getStringArrayList(EXTRA_HONORED_ARGS);
        if (honoredArgs == null) {
            honoredArgs = new ArrayList<>();
        }

        if (expectedMediaCollectionId != null
                && !expectedMediaCollectionId.equals(mediaCollectionId)) {
            throw new IllegalStateException("Mismatched media collection id. Expected: "
                    + expectedMediaCollectionId + ". Found: " + mediaCollectionId);
        }

        if (!honoredArgs.containsAll(expectedHonoredArgs)) {
            throw new IllegalStateException("Unspecified honored args. Expected: "
                    + Arrays.toString(expectedHonoredArgs.toArray())
                    + ". Found: " + Arrays.toString(honoredArgs.toArray()));
        }

        if (usedPageTokens.contains(pageToken)) {
            throw new IllegalStateException("Found repeated page token: " + pageToken);
        } else {
            usedPageTokens.add(pageToken);
        }

        return pageToken;
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

        String getMediaCollectionId() {
            return latestMediaCollectionInfo.getString(MEDIA_COLLECTION_ID);
        }

        static SyncRequestParams forNone() {
            return SYNC_REQUEST_NONE;
        }

        static SyncRequestParams forResetMedia() {
            return SYNC_REQUEST_MEDIA_RESET;
        }

        static SyncRequestParams forFullMedia(Bundle latestMediaCollectionInfo) {
            return new SyncRequestParams(SYNC_TYPE_MEDIA_FULL, /* generation */ 0,
                    latestMediaCollectionInfo);
        }

        static SyncRequestParams forIncremental(long generation, Bundle latestMediaCollectionInfo) {
            return new SyncRequestParams(SYNC_TYPE_MEDIA_INCREMENTAL, generation,
                    latestMediaCollectionInfo);
        }
    }
}
