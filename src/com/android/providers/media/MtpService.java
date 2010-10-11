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
import android.hardware.Usb;
import android.media.MtpDatabase;
import android.media.MtpServer;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;

public class MtpService extends Service
{
    private static final String TAG = "MtpService";

    private class UsbReceiver extends BroadcastReceiver {
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Usb.ACTION_USB_STATE)) {
                boolean connected = intent.getExtras().getBoolean(Usb.USB_CONNECTED);
                boolean mtpEnabled = Usb.USB_FUNCTION_ENABLED.equals(
                        intent.getExtras().getString(Usb.USB_FUNCTION_MTP));
                if (connected && mtpEnabled) {
                    startMtpServer();
                } else {
                    stopMtpServer();
                }
            }
        }
    }

    private MtpServer mServer;
    private UsbReceiver mUsbReceiver;

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
                filter.addAction(Usb.ACTION_USB_STATE);
                registerReceiver(mUsbReceiver, filter);
            }
        }
        return mBinder;
    }
}

