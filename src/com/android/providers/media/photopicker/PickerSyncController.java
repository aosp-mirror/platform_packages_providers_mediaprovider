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
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
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
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.CloudProviderInfo;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.metrics.PhotoPickerUiEventLogger;
import com.android.providers.media.photopicker.util.CloudProviderUtils;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;
import com.android.providers.media.util.ForegroundThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances on the device
 * into the picker db.
 */
public class PickerSyncController {

    public static final ReentrantLock sIdleMaintenanceSyncLock = new ReentrantLock();
    private static final String TAG = "PickerSyncController";
    private static final boolean DEBUG = false;

    private static final String PREFS_KEY_CLOUD_PROVIDER_AUTHORITY = "cloud_provider_authority";
    private static final String PREFS_KEY_CLOUD_PROVIDER_PENDING_NOTIFICATION =
            "cloud_provider_pending_notification";
    private static final String PREFS_KEY_CLOUD_PREFIX = "cloud_provider:";
    private static final String PREFS_KEY_LOCAL_PREFIX = "local_provider:";

    private static final String PICKER_USER_PREFS_FILE_NAME = "picker_user_prefs";
    public static final String PICKER_SYNC_PREFS_FILE_NAME = "picker_sync_prefs";
    public static final String LOCAL_PICKER_PROVIDER_AUTHORITY =
            "com.android.providers.media.photopicker";

    private static final String PREFS_VALUE_CLOUD_PROVIDER_UNSET = "-";

    private static final int SYNC_TYPE_NONE = 0;
    private static final int SYNC_TYPE_MEDIA_INCREMENTAL = 1;
    private static final int SYNC_TYPE_MEDIA_FULL = 2;
    private static final int SYNC_TYPE_MEDIA_RESET = 3;
    @NonNull
    private static final Handler sBgThreadHandler = BackgroundThread.getHandler();
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
    private final Object mCloudSyncLock = new Object();
    // TODO(b/278562157): If there is a dependency on the sync process, always acquire the
    //  {@link mCloudSyncLock} before {@link mCloudProviderLock} to avoid deadlock.
    private final Object mCloudProviderLock = new Object();
    @GuardedBy("mCloudProviderLock")
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
        mSyncDelayMs = configStore.getPickerSyncDelayMs();

        initCloudProvider();
    }

    private void initCloudProvider() {
        synchronized (mCloudProviderLock) {
            if (!mConfigStore.isCloudMediaInPhotoPickerEnabled()) {
                Log.d(TAG, "Cloud-Media-in-Photo-Picker feature is disabled during " + TAG
                        + " construction.");
                persistCloudProviderInfo(CloudProviderInfo.EMPTY, /* shouldUnset */ false);
                return;
            }

            final String cachedAuthority = mUserPrefs.getString(
                    PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, null);

            if (isCloudProviderUnset(cachedAuthority)) {
                Log.d(TAG, "Cloud provider state is unset during " + TAG + " construction.");
                setCurrentCloudProviderInfo(CloudProviderInfo.EMPTY);
                return;
            }

            initCloudProviderLocked(cachedAuthority);
        }
    }

    private void initCloudProviderLocked(@Nullable String cachedAuthority) {
        final CloudProviderInfo defaultInfo = getDefaultCloudProviderInfo(cachedAuthority);

        if (Objects.equals(defaultInfo.authority, cachedAuthority)) {
            // Just set it without persisting since it's not changing and persisting would
            // notify the user that cloud media is now available
            setCurrentCloudProviderInfo(defaultInfo);
        } else {
            // Persist it so that we notify the user that cloud media is now available
            persistCloudProviderInfo(defaultInfo, /* shouldUnset */ false);
        }

        Log.d(TAG, "Initialized cloud provider to: " + defaultInfo.authority);
    }

    /**
     * Syncs the local and currently enabled cloud {@link CloudMediaProvider} instances
     */
    public void syncAllMedia() {
        Log.d(TAG, "syncAllMedia");

        Trace.beginSection(traceSectionName("syncAllMedia"));
        try {
            syncAllMediaFromLocalProvider();
            syncAllMediaFromCloudProvider();
        } finally {
            Trace.endSection();
        }
    }


    /**
     * Syncs the local media
     */
    public void syncAllMediaFromLocalProvider() {
        // Picker sync and special format update can execute concurrently and run into a deadlock.
        // Acquiring a lock before execution of each flow to avoid this.
        sIdleMaintenanceSyncLock.lock();
        try {
            syncAllMediaFromProvider(mLocalProvider, /* isLocal */ true, /* retryOnFailure */ true);
        } finally {
            sIdleMaintenanceSyncLock.unlock();
        }
    }

    private void syncAllMediaFromCloudProvider() {
        synchronized (mCloudSyncLock) {
            final String cloudProvider = getCloudProvider();

            // Disable cloud queries in the database. If any cloud related queries come through
            // while cloud sync is in progress, all cloud items will be ignored and local items will
            // be returned.
            mDbFacade.setCloudProvider(null);

            // Trigger a sync.
            final boolean isSyncCommitted = syncAllMediaFromProvider(cloudProvider,
                    /* isLocal */ false, /* retryOnFailure */ true);

            // Check if sync was committed i.e. the latest collection info was persisted.
            if (!isSyncCommitted) {
                Log.e(TAG, "Failed to sync with cloud provider - " + cloudProvider
                        + ". The cloud provider may have changed during the sync");
                return;
            }

            // Reset the album_media table every time we sync all media
            // TODO(258765155): do we really need to reset for both providers?
            resetAlbumMedia();

            // Re-enable cloud queries in the database for the latest cloud provider.
            synchronized (mCloudProviderLock) {
                if (Objects.equals(mCloudProviderInfo.authority, cloudProvider)) {
                    mDbFacade.setCloudProvider(cloudProvider);
                } else {
                    Log.e(TAG, "Failed to sync with cloud provider - " + cloudProvider
                            + ". The cloud provider has changed to "
                            + mCloudProviderInfo.authority);
                }
            }
        }
    }

    /**
     * Syncs album media from the local and currently enabled cloud {@link CloudMediaProvider}
     * instances
     */
    public void syncAlbumMedia(String albumId, boolean isLocal) {
        if (isLocal) {
            syncAlbumMediaFromLocalProvider(albumId);
        } else {
            syncAlbumMediaFromCloudProvider(albumId);
        }
    }

    private void syncAlbumMediaFromLocalProvider(@NonNull String albumId) {
        syncAlbumMediaFromProvider(mLocalProvider, /* isLocal */ true, albumId);
    }

    private void syncAlbumMediaFromCloudProvider(@NonNull String albumId) {
        synchronized (mCloudSyncLock) {
            syncAlbumMediaFromProvider(getCloudProvider(), /* isLocal */ false, albumId);
        }
    }

    private void resetAlbumMedia() {
        executeSyncAlbumReset(mLocalProvider, /* isLocal */ true, /* albumId */ null);

        synchronized (mCloudSyncLock) {
            executeSyncAlbumReset(getCloudProvider(), /* isLocal */ false, /* albumId */ null);
        }
    }

    /**
     * Resets media library previously synced from the current {@link CloudMediaProvider} as well
     * as the {@link #mLocalProvider local provider}.
     */
    public void resetAllMedia() {
        resetAllMedia(mLocalProvider, /* isLocal */ true);
        synchronized (mCloudSyncLock) {
            resetAllMedia(getCloudProvider(), /* isLocal */ false);
        }
    }

    private boolean resetAllMedia(@Nullable String authority, boolean isLocal) {
        Trace.beginSection(traceSectionName("resetAllMedia", isLocal));
        try {
            executeSyncReset(authority, isLocal);
            return resetCachedMediaCollectionInfo(authority, isLocal);
        } finally {
            Trace.endSection();
        }
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
            if (Objects.equals(info.authority, authority)) {
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
     *         otherwise.
     */
    public boolean setCloudProvider(@Nullable String authority) {
        Trace.beginSection(traceSectionName("setCloudProvider"));
        try {
            return setCloudProviderInternal(authority, /* ignoreAllowlist */ false);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Set cloud provider ignoring allowlist.
     *
     * @return {@code true} if the provider was successfully enabled or cleared, {@code false}
     *         otherwise.
     */
    public boolean forceSetCloudProvider(@Nullable String authority) {
        Trace.beginSection(traceSectionName("forceSetCloudProvider"));
        try {
            return setCloudProviderInternal(authority, /* ignoreAllowlist */ true);
        } finally {
            Trace.endSection();
        }
    }

    private boolean setCloudProviderInternal(@Nullable String authority, boolean ignoreAllowList) {
        Log.d(TAG, "setCloudProviderInternal() auth=" + authority + ", "
                + "ignoreAllowList=" + ignoreAllowList);
        if (DEBUG) {
            Log.v(TAG, "Thread=" + Thread.currentThread() + "; Stacktrace:", new Throwable());
        }

        if (!mConfigStore.isCloudMediaInPhotoPickerEnabled()) {
            Log.w(TAG, "Ignoring a request to set the CloudMediaProvider (" + authority + ") "
                    + "since the Cloud-Media-in-Photo-Picker feature is disabled");
            return false;
        }

        synchronized (mCloudProviderLock) {
            if (Objects.equals(mCloudProviderInfo.authority, authority)) {
                Log.w(TAG, "Cloud provider already set: " + authority);
                return true;
            }
        }

        final CloudProviderInfo newProviderInfo = getCloudProviderInfo(authority, ignoreAllowList);
        if (authority == null || !newProviderInfo.isEmpty()) {
            synchronized (mCloudProviderLock) {
                // Disable cloud provider queries on the db until next sync
                // This will temporarily *clear* the cloud provider on the db facade and prevent
                // any queries from seeing cloud media until a sync where the cloud provider will be
                // reset on the facade
                mDbFacade.setCloudProvider(null);

                final String oldAuthority = mCloudProviderInfo.authority;
                persistCloudProviderInfo(newProviderInfo, /* shouldUnset */ true);

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

    /**
     * @return {@link CloudProviderInfo} for the current {@link CloudMediaProvider} or
     *         {@link CloudProviderInfo#EMPTY} if the {@link CloudMediaProvider} integration is not
     *         enabled.
     */
    @NonNull
    public CloudProviderInfo getCurrentCloudProviderInfo() {
        synchronized (mCloudProviderLock) {
            return mCloudProviderInfo;
        }
    }

    /**
     * Set {@link PickerSyncController#mCloudProviderInfo} as the current {@link CloudMediaProvider}
     *         or {@link CloudProviderInfo#EMPTY} if the {@link CloudMediaProvider} integration
     *         disabled by the user.
     */
    private void setCurrentCloudProviderInfo(@NonNull CloudProviderInfo cloudProviderInfo) {
        synchronized (mCloudProviderLock) {
            mCloudProviderInfo = cloudProviderInfo;
        }
    }

    /**
     * @return {@link android.content.pm.ProviderInfo#authority authority} of the current
     *         {@link CloudMediaProvider} or {@code null} if the {@link CloudMediaProvider}
     *         integration is not enabled.
     */
    @Nullable
    public String getCloudProvider() {
        synchronized (mCloudProviderLock) {
            return mCloudProviderInfo.authority;
        }
    }

    /**
     * @return {@link android.content.pm.ProviderInfo#authority authority} of the local provider.
     */
    @NonNull
    public String getLocalProvider() {
        return mLocalProvider;
    }

    public boolean isProviderEnabled(String authority) {
        if (mLocalProvider.equals(authority)) {
            return true;
        }

        synchronized (mCloudProviderLock) {
            if (!mCloudProviderInfo.isEmpty()
                    && Objects.equals(mCloudProviderInfo.authority, authority)) {
                return true;
            }
        }

        return false;
    }

    public boolean isProviderEnabled(String authority, int uid) {
        if (uid == Process.myUid() && mLocalProvider.equals(authority)) {
            return true;
        }

        synchronized (mCloudProviderLock) {
            if (!mCloudProviderInfo.isEmpty() && uid == mCloudProviderInfo.uid
                    && Objects.equals(mCloudProviderInfo.authority, authority)) {
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
            if (info.uid == uid && Objects.equals(info.authority, authority)) {
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
        sBgThreadHandler.removeCallbacks(mSyncAllMediaCallback);
        sBgThreadHandler.postDelayed(mSyncAllMediaCallback, mSyncDelayMs);
    }

    /**
     * Notifies about package removal
     */
    public void notifyPackageRemoval(String packageName) {
        synchronized (mCloudProviderLock) {
            if (mCloudProviderInfo.matches(packageName)) {
                Log.i(TAG, "Package " + packageName
                        + " is the current cloud provider and got removed");
                resetCloudProvider();
            }
        }
    }

    private void resetCloudProvider() {
        synchronized (mCloudProviderLock) {
            setCloudProvider(/* authority */ null);

            /**
             * {@link #setCloudProvider(String null)} sets the cloud provider state to UNSET.
             * Clearing the persisted cloud provider authority to set the state as NOT_SET instead.
             */
            clearPersistedCloudProviderAuthority();

            initCloudProviderLocked(/* cachedAuthority */ null);
        }
    }

    // TODO(b/257887919): Build proper UI and remove this.
    /**
     * Notifies about picker UI launched
     */
    public void notifyPickerLaunch() {
        final String authority = getCloudProvider();

        final boolean hasPendingNotification = mUserPrefs.getBoolean(
                PREFS_KEY_CLOUD_PROVIDER_PENDING_NOTIFICATION, /* defaultValue */ false);

        if (!hasPendingNotification || (authority == null)) {
            Log.d(TAG, "No pending UI notification");
            return;
        }

        // Offload showing the UI on a fg thread to avoid the expensive binder request
        // to fetch the app name blocking the picker launch
        ForegroundThread.getHandler().post(() -> {
            Log.i(TAG, "Cloud media now available in the picker");

            final PackageManager pm = mContext.getPackageManager();
            final String appName = CloudProviderUtils.getProviderLabel(pm, authority);

            final String message = mContext.getResources().getString(R.string.picker_cloud_sync,
                    appName);
            Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
        });

        // Clear the notification
        updateBooleanUserPref(PREFS_KEY_CLOUD_PROVIDER_PENDING_NOTIFICATION, false);
    }

    private void updateBooleanUserPref(String key, boolean value) {
        final SharedPreferences.Editor editor = mUserPrefs.edit();
        editor.putBoolean(key, value);
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

    /**
     * Returns true if the sync was successful and the latest collection info was persisted.
     */
    private boolean syncAllMediaFromProvider(@Nullable String authority, boolean isLocal,
            boolean retryOnFailure) {
        Log.d(TAG, "syncAllMediaFromProvider() " + (isLocal ? "LOCAL" : "CLOUD")
                + ", auth=" + authority
                + ", retry=" + retryOnFailure);
        if (DEBUG) {
            Log.v(TAG, "Thread=" + Thread.currentThread() + "; Stacktrace:", new Throwable());
        }

        Trace.beginSection(traceSectionName("syncAllMediaFromProvider", isLocal));
        try {
            final SyncRequestParams params = getSyncRequestParams(authority, isLocal);

            switch (params.syncType) {
                case SYNC_TYPE_MEDIA_RESET:
                    // Can only happen when |authority| has been set to null and we need to clean up
                    return resetAllMedia(authority, isLocal);
                case SYNC_TYPE_MEDIA_FULL:
                    if (!resetAllMedia(authority, isLocal)) {
                        return false;
                    }

                    // Pass a mutable empty bundle intentionally because it might be populated with
                    // the next page token as part of a query to a cloud provider supporting
                    // pagination
                    executeSyncAdd(authority, isLocal, params.getMediaCollectionId(),
                            /* isIncrementalSync */ false, /* queryArgs */ new Bundle());

                    // Commit sync position
                    return cacheMediaCollectionInfo(
                            authority, isLocal, params.latestMediaCollectionInfo);
                case SYNC_TYPE_MEDIA_INCREMENTAL:
                    final Bundle queryArgs = new Bundle();
                    queryArgs.putLong(EXTRA_SYNC_GENERATION, params.syncGeneration);

                    executeSyncAdd(authority, isLocal, params.getMediaCollectionId(),
                            /* isIncrementalSync */ true, queryArgs);
                    executeSyncRemove(authority, isLocal, params.getMediaCollectionId(), queryArgs);

                    // Commit sync position
                    return cacheMediaCollectionInfo(
                            authority, isLocal, params.latestMediaCollectionInfo);
                case SYNC_TYPE_NONE:
                    return true;
                default:
                    throw new IllegalArgumentException("Unexpected sync type: " + params.syncType);
            }
        } catch (RequestObsoleteException e) {
            Log.e(TAG, "Failed to sync all media because authority has changed: ", e);
        } catch (RuntimeException e) {
            // Reset all media for the cloud provider in case it never succeeds
            resetAllMedia(authority, isLocal);

            // Attempt a full sync. If this fails, the db table would have been reset,
            // flushing all old content and leaving the picker UI empty.
            Log.e(TAG, "Failed to sync all media. Reset media and retry: " + retryOnFailure, e);
            if (retryOnFailure) {
                return syncAllMediaFromProvider(authority, isLocal, /* retryOnFailure */ false);
            }
        } finally {
            Trace.endSection();
        }
        return false;
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

    /**
     * Persist cloud provider info and send a sync request to the background thread.
     */
    private void persistCloudProviderInfo(@NonNull CloudProviderInfo info, boolean shouldUnset) {
        synchronized (mCloudProviderLock) {
            setCurrentCloudProviderInfo(info);

            final String authority = info.authority;
            final SharedPreferences.Editor editor = mUserPrefs.edit();
            final boolean isCloudProviderInfoNotEmpty = !info.isEmpty();

            if (isCloudProviderInfoNotEmpty) {
                editor.putString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY, authority);
            } else if (shouldUnset) {
                editor.putString(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY,
                        PREFS_VALUE_CLOUD_PROVIDER_UNSET);
            } else {
                editor.remove(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY);
            }

            editor.putBoolean(
                    PREFS_KEY_CLOUD_PROVIDER_PENDING_NOTIFICATION, isCloudProviderInfoNotEmpty);

            editor.apply();

            if (SdkLevel.isAtLeastT()) {
                try {
                    StorageManager sm = mContext.getSystemService(StorageManager.class);
                    sm.setCloudMediaProvider(authority);
                } catch (SecurityException e) {
                    // When run as part of the unit tests, the notification fails because only the
                    // MediaProvider uid can notify
                    Log.w(TAG, "Failed to notify the system of cloud provider update to: "
                            + authority);
                }
            }

            Log.d(TAG, "Updated cloud provider to: " + authority);

            resetCachedMediaCollectionInfo(info.authority, /* isLocal */ false);
        }
    }

    /**
     * Clears the persisted cloud provider authority and sets the state to default (NOT_SET).
     */
    @VisibleForTesting
    void clearPersistedCloudProviderAuthority() {
        Log.d(TAG, "Setting the cloud provider state to default (NOT_SET) by clearing the "
                + "persisted cloud provider authority");
        mUserPrefs.edit().remove(PREFS_KEY_CLOUD_PROVIDER_AUTHORITY).apply();
    }

    /**
     * Commit the latest media collection info when a sync operation is completed.
     */
    private boolean cacheMediaCollectionInfo(@Nullable String authority, boolean isLocal,
            @Nullable Bundle bundle) {
        if (authority == null) {
            Log.d(TAG, "Ignoring cache media info for null authority with bundle: " + bundle);
            return true;
        }

        Trace.beginSection(traceSectionName("cacheMediaCollectionInfo", isLocal));

        try {
            if (isLocal) {
                cacheMediaCollectionInfoInternal(isLocal, bundle);
                return true;
            } else {
                synchronized (mCloudProviderLock) {
                    // Check if the media collection info belongs to the current cloud provider
                    // authority.
                    if (Objects.equals(authority, mCloudProviderInfo.authority)) {
                        cacheMediaCollectionInfoInternal(isLocal, bundle);
                        return true;
                    } else {
                        Log.e(TAG, "Do not cache collection info for "
                                + authority + " because cloud provider changed to "
                                + mCloudProviderInfo.authority);
                        return false;
                    }
                }
            }
        } finally {
            Trace.endSection();
        }
    }

    private void cacheMediaCollectionInfoInternal(boolean isLocal,
            @Nullable Bundle bundle) {
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
    }

    private boolean resetCachedMediaCollectionInfo(@Nullable String authority, boolean isLocal) {
        return cacheMediaCollectionInfo(authority, isLocal, /* bundle */ null);
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

    @NonNull
    private SyncRequestParams getSyncRequestParams(@Nullable String authority,
            boolean isLocal) throws RequestObsoleteException {
        if (isLocal) {
            return getSyncRequestParamsInternal(authority, isLocal);
        } else {
            // Ensure that we are fetching sync request params for the current cloud provider.
            synchronized (mCloudProviderLock) {
                if (Objects.equals(mCloudProviderInfo.authority, authority)) {
                    return getSyncRequestParamsInternal(authority, isLocal);
                } else {
                    throw new RequestObsoleteException("Attempt to fetch sync request params for an"
                            + " unknown cloud provider. Current provider: "
                            + mCloudProviderInfo.authority + " Requested provider: " + authority);
                }
            }
        }
    }


    @NonNull
    private SyncRequestParams getSyncRequestParamsInternal(@Nullable String authority,
            boolean isLocal) {
        Log.d(TAG, "getSyncRequestParams() " + (isLocal ? "LOCAL" : "CLOUD")
                + ", auth=" + authority);
        if (DEBUG) {
            Log.v(TAG, "Thread=" + Thread.currentThread() + "; Stacktrace:", new Throwable());
        }

        final SyncRequestParams result;
        if (authority == null) {
            // Only cloud authority can be null
            result = SyncRequestParams.forResetMedia();
        } else {
            final Bundle cachedMediaCollectionInfo = getCachedMediaCollectionInfo(isLocal);
            final Bundle latestMediaCollectionInfo = getLatestMediaCollectionInfo(authority);

            final String latestCollectionId =
                    latestMediaCollectionInfo.getString(MEDIA_COLLECTION_ID);
            final long latestGeneration =
                    latestMediaCollectionInfo.getLong(LAST_MEDIA_SYNC_GENERATION);
            Log.d(TAG, "   Latest ID/Gen=" + latestCollectionId + "/" + latestGeneration);

            final String cachedCollectionId =
                    cachedMediaCollectionInfo.getString(MEDIA_COLLECTION_ID);
            final long cachedGeneration =
                    cachedMediaCollectionInfo.getLong(LAST_MEDIA_SYNC_GENERATION);
            Log.d(TAG, "   Cached ID/Gen=" + cachedCollectionId + "/" + cachedGeneration);

            if (TextUtils.isEmpty(latestCollectionId) || latestGeneration < 0) {
                throw new IllegalStateException("Unexpected Latest Media Collection Info: "
                        + "ID/Gen=" + latestCollectionId + "/" + latestGeneration);
            }

            if (!Objects.equals(latestCollectionId, cachedCollectionId)) {
                result = SyncRequestParams.forFullMedia(latestMediaCollectionInfo);
            } else if (cachedGeneration == latestGeneration) {
                result = SyncRequestParams.forNone();
            } else {
                result = SyncRequestParams.forIncremental(
                        cachedGeneration, latestMediaCollectionInfo);
            }
        }
        Log.d(TAG, "   RESULT=" + result);
        return result;
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
    CloudProviderInfo getDefaultCloudProviderInfo(@Nullable String lastProvider) {
        final List<CloudProviderInfo> providers = getAvailableCloudProviders();

        if (providers.size() == 1) {
            Log.i(TAG, "Only 1 cloud provider found, hence " + providers.get(0).authority
                    + " is the default");
            return providers.get(0);
        } else {
            Log.i(TAG, "Found " + providers.size() + " available Cloud Media Providers.");
        }

        if (lastProvider != null) {
            for (CloudProviderInfo provider : providers) {
                if (Objects.equals(provider.authority, lastProvider)) {
                    return provider;
                }
            }
        }

        final String defaultProviderPkg = mConfigStore.getDefaultCloudProviderPackage();
        if (defaultProviderPkg != null) {
            Log.i(TAG, "Default Cloud-Media-Provider package is " + defaultProviderPkg);

            for (CloudProviderInfo provider : providers) {
                if (provider.matches(defaultProviderPkg)) {
                    return provider;
                }
            }
        } else {
            Log.i(TAG, "Default Cloud-Media-Provider is not set.");
        }

        // No default set or default not installed
        return CloudProviderInfo.EMPTY;
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
        static final SyncRequestParams SYNC_REQUEST_NONE = new SyncRequestParams(SYNC_TYPE_NONE);
        static final SyncRequestParams SYNC_REQUEST_MEDIA_RESET =
                new SyncRequestParams(SYNC_TYPE_MEDIA_RESET);

        final int syncType;
        // Only valid for SYNC_TYPE_INCREMENTAL
        final long syncGeneration;
        // Only valid for SYNC_TYPE_[INCREMENTAL|FULL]
        final Bundle latestMediaCollectionInfo;

        SyncRequestParams(@SyncType int syncType) {
            this(syncType, /* syncGeneration */ 0, /* latestMediaCollectionInfo */ null);
        }

        SyncRequestParams(@SyncType int syncType, long syncGeneration,
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

        @Override
        public String toString() {
            return "SyncRequestParams{type=" + syncTypeToString(syncType)
                    + ", gen=" + syncGeneration + ", latest=" + latestMediaCollectionInfo + '}';
        }
    }

    private static String syncTypeToString(@SyncType int syncType) {
        switch (syncType) {
            case SYNC_TYPE_NONE:
                return "NONE";
            case SYNC_TYPE_MEDIA_INCREMENTAL:
                return "MEDIA_INCREMENTAL";
            case SYNC_TYPE_MEDIA_FULL:
                return "MEDIA_FULL";
            case SYNC_TYPE_MEDIA_RESET:
                return "MEDIA_RESET";
            default:
                return "Unknown";
        }
    }

    private static boolean isCloudProviderUnset(@Nullable String lastProviderAuthority) {
        return Objects.equals(lastProviderAuthority, PREFS_VALUE_CLOUD_PROVIDER_UNSET);
    }
}
