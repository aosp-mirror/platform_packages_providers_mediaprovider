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
import android.content.Context;
import android.content.Intent;
import android.media.MtpServer;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.util.Locale;

public class MtpService extends Service
{
    private static final String TAG = "MtpService";

    private MtpServer mServer;

    @Override
    public void onStart(Intent intent, int startId)
    {
         Log.d(TAG, "onStart");

        String storagePath = Environment.getExternalStorageDirectory().getPath();
        String databasePath = getFilesDir().getPath() + File.separator + "mtp.db";
        mServer = new MtpServer(storagePath, databasePath);
        mServer.start();
    }


    @Override
    public void onDestroy()
    {
        Log.d(TAG, "onDestroy");

        mServer.stop();
        mServer = null;
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }

}

