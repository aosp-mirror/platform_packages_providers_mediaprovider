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
import android.content.pm.PackageManager;
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
                String state = primary.getState();
                if (Environment.MEDIA_MOUNTED.equals(state)) {
                    addStorageLocked(mVolumeMap.get(path));
                } else {
                    Log.e(TAG, "Couldn't add primary storage " + path + " in state " + state);
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
            synchronized (this) {
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

    @GuardedBy("this")
    private ServerHolder sServerHolder;

    private StorageManager mStorageManager;

    /** Flag indicating if MTP is disabled due to keyguard */
    @GuardedBy("this")
    private boolean mMtpDisabled;
    @GuardedBy("this")
    private boolean mUnlocked;
    @GuardedBy("this")
    private boolean mPtpMode;

    @GuardedBy("this")
    private HashMap<String, StorageVolume> mVolumeMap;
    @GuardedBy("this")
    private HashMap<String, MtpStorage> mStorageMap;
    @GuardedBy("this")
    private StorageVolume[] mVolumes;

    @Override
    public void onCreate() {
        mStorageManager = this.getSystemService(StorageManager.class);
    }

    @Override
    public void onDestroy() {
        mStorageManager.unregisterListener(mStorageEventListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        UserHandle user = new UserHandle(ActivityManager.getCurrentUser());
        synchronized (this) {
            mVolumeMap = new HashMap<>();
            mStorageMap = new HashMap<>();
            mStorageManager.registerListener(mStorageEventListener);
            mVolumes = StorageManager.getVolumeList(user.getIdentifier(), 0);
            for (StorageVolume volume : mVolumes) {
                if (Environment.MEDIA_MOUNTED.equals(volume.getState())) {
                    volumeMountedLocked(volume.getPath());
                } else {
                    Log.e(TAG, "StorageVolume not mounted " + volume.getPath());
                }
            }
        }

        synchronized (this) {
            mUnlocked = intent.getBooleanExtra(UsbManager.USB_DATA_UNLOCKED, false);
            updateDisabledStateLocked();
            mPtpMode = (intent == null ? false
                    : intent.getBooleanExtra(UsbManager.USB_FUNCTION_PTP, false));
            String[] subdirs = null;
            if (mPtpMode) {
                Environment.UserEnvironment env = new Environment.UserEnvironment(
                        user.getIdentifier());
                int count = PTP_DIRECTORIES.length;
                subdirs = new String[count];
                for (int i = 0; i < count; i++) {
                    File file = env.buildExternalStoragePublicDirs(PTP_DIRECTORIES[i])[0];
                    // make sure this directory exists
                    file.mkdirs();
                    subdirs[i] = file.getPath();
                }
            }
            final StorageVolume primary = StorageManager.getPrimaryVolume(mVolumes);
            try {
                manageServiceLocked(primary, subdirs, user);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Couldn't find the current user!: " + e.getMessage());
            }
        }

        return START_REDELIVER_INTENT;
    }

    private void updateDisabledStateLocked() {
        mMtpDisabled = !mUnlocked;
        if (LOGD) {
            Log.d(TAG, "updating state; mMtpLocked=" + mMtpDisabled);
        }
    }

    /**
     * Manage {@link #mServer}, creating only when running as the current user.
     */
    private void manageServiceLocked(StorageVolume primary, String[] subdirs, UserHandle user)
            throws PackageManager.NameNotFoundException {
        synchronized (this) {
            if (sServerHolder != null) {
                if (LOGD) {
                    Log.d(TAG, "Cannot launch second MTP server.");
                }
                // Previously executed MtpServer is still running. It will be terminated
                // because MTP device FD will become invalid soon. Also MtpService will get new
                // intent after that when UsbDeviceManager configures USB with new state.
                return;
            }

            Log.d(TAG, "starting MTP server in " + (mPtpMode ? "PTP mode" : "MTP mode") +
                    " with storage " + primary.getPath() + (mMtpDisabled ? " disabled" : ""));
            final MtpDatabase database = new MtpDatabase(this,
                    createPackageContextAsUser(this.getPackageName(), 0, user),
                    MediaProvider.EXTERNAL_VOLUME,
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
