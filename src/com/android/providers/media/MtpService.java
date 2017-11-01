/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.providers.media;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.mtp.MtpDatabase;
import android.mtp.MtpServer;
import android.mtp.MtpStorage;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.util.HashMap;

public class MtpService extends Service {
    private static final String TAG = "MtpService";
    private static final boolean LOGD = false;

    // We restrict PTP to these subdirectories
    private static final String[] PTP_DIRECTORIES = new String[] {
        Environment.DIRECTORY_DCIM,
        Environment.DIRECTORY_PICTURES,
    };

    private void addStorageDevicesLocked() {
        if (mPtpMode) {
            // In PTP mode we support only primary storage
            final StorageVolume primary = StorageManager.getPrimaryVolume(mVolumes);
            final String path = primary.getPath();
            if (path != null) {
                String state = mStorageManager.getVolumeState(path);
                if (Environment.MEDIA_MOUNTED.equals(state)) {
                    addStorageLocked(mVolumeMap.get(path));
                }
            }
        } else {
            for (StorageVolume volume : mVolumeMap.values()) {
                addStorageLocked(volume);
            }
        }
    }

    private final StorageEventListener mStorageEventListener = new StorageEventListener() {
        @Override
        public void onStorageStateChanged(String path, String oldState, String newState) {
            synchronized (MtpService.this) {
                Log.d(TAG, "onStorageStateChanged " + path + " " + oldState + " -> " + newState);
                if (Environment.MEDIA_MOUNTED.equals(newState)) {
                    volumeMountedLocked(path);
                } else if (Environment.MEDIA_MOUNTED.equals(oldState)) {
                    StorageVolume volume = mVolumeMap.remove(path);
                    if (volume != null) {
                        removeStorageLocked(volume);
                    }
                }
            }
        }
    };

    /**
     * Static state of MtpServer. MtpServer opens FD for MTP driver internally and we cannot open
     * multiple MtpServer at the same time. The static field used to handle the case where MtpServer
     * lives beyond the lifetime of MtpService.
     *
     * Lock MtpService.this before locking MtpService.class if needed. Otherwise it goes to
     * deadlock.
     */
    @GuardedBy("MtpService.class")
    private static ServerHolder sServerHolder;

    private StorageManager mStorageManager;

    /** Flag indicating if MTP is disabled due to keyguard */
    @GuardedBy("this")
    private boolean mMtpDisabled;
    @GuardedBy("this")
    private boolean mUnlocked;
    @GuardedBy("this")
    private boolean mPtpMode;

    @GuardedBy("this")
    private final HashMap<String, StorageVolume> mVolumeMap = new HashMap<String, StorageVolume>();
    @GuardedBy("this")
    private final HashMap<String, MtpStorage> mStorageMap = new HashMap<String, MtpStorage>();
    @GuardedBy("this")
    private StorageVolume[] mVolumes;

    @Override
    public void onCreate() {
        mStorageManager = StorageManager.from(this);
        synchronized (this) {
            updateDisabledStateLocked();
            mStorageManager.registerListener(mStorageEventListener);
            StorageVolume[] volumes = mStorageManager.getVolumeList();
            mVolumes = volumes;
            for (int i = 0; i < volumes.length; i++) {
                String path = volumes[i].getPath();
                String state = mStorageManager.getVolumeState(path);
                if (Environment.MEDIA_MOUNTED.equals(state)) {
                    volumeMountedLocked(path);
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (this) {
            mUnlocked = intent.getBooleanExtra(UsbManager.USB_DATA_UNLOCKED, false);
            if (LOGD) { Log.d(TAG, "onStartCommand intent=" + intent + " mUnlocked=" + mUnlocked); }
            updateDisabledStateLocked();
            mPtpMode = (intent == null ? false
                    : intent.getBooleanExtra(UsbManager.USB_FUNCTION_PTP, false));
            String[] subdirs = null;
            if (mPtpMode) {
                int count = PTP_DIRECTORIES.length;
                subdirs = new String[count];
                for (int i = 0; i < count; i++) {
                    File file =
                            Environment.getExternalStoragePublicDirectory(PTP_DIRECTORIES[i]);
                    // make sure this directory exists
                    file.mkdirs();
                    subdirs[i] = file.getPath();
                }
            }
            final StorageVolume primary = StorageManager.getPrimaryVolume(mVolumes);
            manageServiceLocked(primary, subdirs);
        }

        return START_REDELIVER_INTENT;
    }

    private void updateDisabledStateLocked() {
        final boolean isCurrentUser = UserHandle.myUserId() == ActivityManager.getCurrentUser();
        mMtpDisabled = !mUnlocked || !isCurrentUser;
        if (LOGD) {
            Log.d(TAG, "updating state; isCurrentUser=" + isCurrentUser + ", mMtpLocked="
                    + mMtpDisabled);
        }
    }

    /**
     * Manage {@link #mServer}, creating only when running as the current user.
     */
    private void manageServiceLocked(StorageVolume primary, String[] subdirs) {
        final boolean isCurrentUser = UserHandle.myUserId() == ActivityManager.getCurrentUser();

        synchronized (MtpService.class) {
            if (sServerHolder != null) {
                if (LOGD) {
                    Log.d(TAG, "Cannot launch second MTP server.");
                }
                // Previously executed MtpServer is still running. It will be terminated
                // because MTP device FD will become invalid soon. Also MtpService will get new
                // intent after that when UsbDeviceManager configures USB with new state.
                return;
            }
            if (!isCurrentUser) {
                return;
            }

            Log.d(TAG, "starting MTP server in " + (mPtpMode ? "PTP mode" : "MTP mode"));
            final MtpDatabase database = new MtpDatabase(this, MediaProvider.EXTERNAL_VOLUME,
                    primary.getPath(), subdirs);
            String deviceSerialNumber = Build.SERIAL;
            if (Build.UNKNOWN.equals(deviceSerialNumber)) {
                deviceSerialNumber = "????????";
            }
            final MtpServer server =
                    new MtpServer(
                            database,
                            mPtpMode,
                            new OnServerTerminated(),
                            Build.MANUFACTURER, // MTP DeviceInfo: Manufacturer
                            Build.MODEL,        // MTP DeviceInfo: Model
                            "1.0",              // MTP DeviceInfo: Device Version
                            deviceSerialNumber  // MTP DeviceInfo: Serial Number
                            );
            database.setServer(server);
            sServerHolder = new ServerHolder(server, database);

            // Need to run addStorageDevicesLocked after sServerHolder is set since it accesses
            // sServerHolder.
            if (!mMtpDisabled) {
                addStorageDevicesLocked();
            }
            server.start();
        }
    }

    @Override
    public void onDestroy() {
        mStorageManager.unregisterListener(mStorageEventListener);
    }

    private final IMtpService.Stub mBinder =
            new IMtpService.Stub() {
        @Override
        public void sendObjectAdded(int objectHandle) {
            synchronized (MtpService.class) {
                if (sServerHolder != null) {
                    sServerHolder.server.sendObjectAdded(objectHandle);
                }
            }
        }

        @Override
        public void sendObjectRemoved(int objectHandle) {
            synchronized (MtpService.class) {
                if (sServerHolder != null) {
                    sServerHolder.server.sendObjectRemoved(objectHandle);
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void volumeMountedLocked(String path) {
        for (int i = 0; i < mVolumes.length; i++) {
            StorageVolume volume = mVolumes[i];
            if (volume.getPath().equals(path)) {
                mVolumeMap.put(path, volume);
                if (!mMtpDisabled) {
                    // In PTP mode we support only primary storage
                    if (volume.isPrimary() || !mPtpMode) {
                        addStorageLocked(volume);
                    }
                }
                break;
            }
        }
    }

    private void addStorageLocked(StorageVolume volume) {
        MtpStorage storage = new MtpStorage(volume, getApplicationContext());
        mStorageMap.put(storage.getPath(), storage);

        if (storage.getStorageId() == StorageVolume.STORAGE_ID_INVALID) {
            Log.w(TAG, "Ignoring volume with invalid MTP storage ID: " + storage);
            return;
        } else {
            Log.d(TAG, "Adding MTP storage 0x" + Integer.toHexString(storage.getStorageId())
                    + " at " + storage.getPath());
        }

        synchronized (MtpService.class) {
            if (sServerHolder != null) {
                sServerHolder.database.addStorage(storage);
                sServerHolder.server.addStorage(storage);
            }
        }
    }

    private void removeStorageLocked(StorageVolume volume) {
        MtpStorage storage = mStorageMap.remove(volume.getPath());
        if (storage == null) {
            Log.e(TAG, "Missing MtpStorage for " + volume.getPath());
            return;
        }

        Log.d(TAG, "Removing MTP storage " + Integer.toHexString(storage.getStorageId()) + " at "
                + storage.getPath());

        synchronized (MtpService.class) {
            if (sServerHolder != null) {
                sServerHolder.database.removeStorage(storage);
                sServerHolder.server.removeStorage(storage);
            }
        }
    }

    private static class ServerHolder {
        @NonNull final MtpServer server;
        @NonNull final MtpDatabase database;

        ServerHolder(@NonNull MtpServer server, @NonNull MtpDatabase database) {
            Preconditions.checkNotNull(server);
            Preconditions.checkNotNull(database);
            this.server = server;
            this.database = database;
        }

        void close() {
            this.database.setServer(null);
        }
    }

    private class OnServerTerminated implements Runnable {
        @Override
        public void run() {
            synchronized (MtpService.class) {
                if (sServerHolder == null) {
                    Log.e(TAG, "sServerHolder is unexpectedly null.");
                    return;
                }
                sServerHolder.close();
                sServerHolder = null;
            }
        }
    }
}
