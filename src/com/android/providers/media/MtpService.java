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
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MtpDatabase;
import android.media.MtpServer;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

public class MtpService extends Service
{
    private static final String TAG = "MtpService";

    private MtpServer mServer;

    @Override
    public void onStart(Intent intent, int startId)
    {
         Log.d(TAG, "onStart intent " + intent + " startId " + startId);
         ContentResolver resolver = getContentResolver();

        // make sure external media database is open
        try {
            ContentValues values = new ContentValues();
            values.put("name", MediaProvider.EXTERNAL_VOLUME);
            resolver.insert(Uri.parse("content://media/"), values);
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "failed to open media database");
        }

        MtpDatabase database = new MtpDatabase(this, MediaProvider.EXTERNAL_VOLUME);
        String storagePath = Environment.getExternalStorageDirectory().getPath();
        synchronized (mBinder) {
            mServer = new MtpServer(database, storagePath);
            mServer.start();
        }
    }

    @Override
    public void onDestroy()
    {
        Log.d(TAG, "onDestroy");

        synchronized (mBinder) {
            mServer.stop();
            mServer = null;
        }
    }

    private final IMtpService.Stub mBinder =
            new IMtpService.Stub() {
        public void sendObjectAdded(int objectHandle) {
            synchronized (this) {
                Log.d(TAG, "sendObjectAdded " + objectHandle);
                if (mServer != null) {
                    mServer.sendObjectAdded(objectHandle);
                }
            }
        }

        public void sendObjectRemoved(int objectHandle) {
            synchronized (this) {
                Log.d(TAG, "sendObjectRemoved " + objectHandle);
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
        return mBinder;
    }
}

