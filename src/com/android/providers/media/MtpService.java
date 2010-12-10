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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.UsbManager;
import android.media.MtpDatabase;
import android.media.MtpServer;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

public class MtpService extends Service
{
    private static final String TAG = "MtpService";

    private class UsbReceiver extends BroadcastReceiver {
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (action.equals(UsbManager.ACTION_USB_STATE)) {
                boolean connected = intent.getExtras().getBoolean(UsbManager.USB_CONNECTED);
                boolean mtpEnabled = UsbManager.USB_FUNCTION_ENABLED.equals(
                        intent.getExtras().getString(UsbManager.USB_FUNCTION_MTP));
                if (connected && mtpEnabled) {
                    startMtpServer();
                } else {
                    stopMtpServer();
                }
            }
        }
    }

    private class SettingsObserver extends ContentObserver {
        private ContentResolver mResolver;
        SettingsObserver() {
            super(new Handler());
        }

        void observe(Context context) {
            Log.d(TAG, "observe");
            mResolver = context.getContentResolver();
            mResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.USE_PTP_INTERFACE), false, this);
            onChange(false);
        }

        @Override
        public void onChange(boolean selfChange) {
            Log.d(TAG, "onChange selfChange: " + selfChange);
            mPtpMode = (Settings.System.getInt(mResolver,
                    Settings.System.USE_PTP_INTERFACE, 0) != 0);
            Log.d(TAG, "mPtpMode = " + mPtpMode);
        }
    }

    private MtpServer mServer;
    private UsbReceiver mUsbReceiver;
    private SettingsObserver mSettingsObserver;
    private boolean mPtpMode;

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        mSettingsObserver = new SettingsObserver();
        mSettingsObserver.observe(this);
    }

    @Override
    public void onStart(Intent intent, int startId)
    {
        Log.d(TAG, "onStart intent " + intent + " startId " + startId);
    }

    private void startMtpServer() {
        Log.d(TAG, "startMtpServer");
        synchronized (mBinder) {
            if (mServer == null) {
                String storagePath = SystemProperties.get("ro.media.storage");
                if (storagePath == null || storagePath.length() == 0) {
                    storagePath = Environment.getExternalStorageDirectory().getPath();
                }
                MtpDatabase database = new MtpDatabase(this, MediaProvider.EXTERNAL_VOLUME, storagePath);
                Log.d(TAG, "starting MTP server for " + storagePath);
                mServer = new MtpServer(database, storagePath);
                mServer.setPtpMode(mPtpMode);
                mServer.start();
            }
        }
    }

    private void stopMtpServer() {
        Log.d(TAG, "stopMtpServer");
        synchronized (mBinder) {
            if (mServer != null) {
                mServer.stop();
                mServer = null;
            }
        }
    }

    @Override
    public void onDestroy()
    {
        Log.d(TAG, "onDestroy");
        stopMtpServer();
    }

    private final IMtpService.Stub mBinder =
            new IMtpService.Stub() {
        public void sendObjectAdded(int objectHandle) {
            synchronized (mBinder) {
                if (mServer != null) {
                    mServer.sendObjectAdded(objectHandle);
                }
            }
        }

        public void sendObjectRemoved(int objectHandle) {
            synchronized (mBinder) {
                if (mServer != null) {
                    mServer.sendObjectRemoved(objectHandle);
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent)
    {
        Log.d(TAG, "onBind");
        synchronized (mBinder) {
            if (mUsbReceiver == null) {
                mUsbReceiver = new UsbReceiver();
                IntentFilter filter = new IntentFilter();
                filter.addAction(UsbManager.ACTION_USB_STATE);
                registerReceiver(mUsbReceiver, filter);
            }
        }
        return mBinder;
    }
}

