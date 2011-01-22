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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.mtp.MtpDatabase;
import android.mtp.MtpServer;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

public class MtpService extends Service
{
    private static final String TAG = "MtpService";

     private class SettingsObserver extends ContentObserver {
        private ContentResolver mResolver;
        SettingsObserver() {
            super(new Handler());
        }

        void observe(Context context) {
            mResolver = context.getContentResolver();
            mResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.USE_PTP_INTERFACE), false, this);
            onChange(false);
        }

        @Override
        public void onChange(boolean selfChange) {
            mPtpMode = (Settings.System.getInt(mResolver,
                    Settings.System.USE_PTP_INTERFACE, 0) != 0);
        }
    }

    private MtpServer mServer;
    private SettingsObserver mSettingsObserver;
    private boolean mPtpMode;

    @Override
    public void onCreate() {
        mSettingsObserver = new SettingsObserver();
        mSettingsObserver.observe(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (mBinder) {
            if (mServer == null) {
                String storagePath = Environment.getExternalStorageDirectory().getPath();
                MtpDatabase database = new MtpDatabase(this,
                        MediaProvider.EXTERNAL_VOLUME, storagePath);
                int reserveSpaceMegabytes = getResources().getInteger(
                        com.android.internal.R.integer.config_mtpReserveSpaceMegabytes);

                Log.d(TAG, "starting MTP server for " + storagePath);
                mServer = new MtpServer(database, storagePath, reserveSpaceMegabytes*1024*1024);
                mServer.setPtpMode(mPtpMode);
                mServer.start();
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy()
    {
        synchronized (mBinder) {
            if (mServer != null) {
                Log.d(TAG, "stopping MTP server");
                mServer.stop();
                mServer = null;
            }
        }
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
        return mBinder;
    }
}

