/* //device/content/providers/media/src/com/android/providers/media/MediaScannerService.java
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package com.android.providers.media;

import static com.android.providers.media.MediaProvider.TAG;

import android.app.Service;
import android.content.Intent;
import android.media.IMediaScannerListener;
import android.media.IMediaScannerService;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.util.Log;

import java.io.File;

public class MediaScannerService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return new IMediaScannerService.Stub() {
            @Override
            public void requestScanFile(String path, String mimeType,
                    IMediaScannerListener listener) {
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();

                AsyncTask.execute(() -> {
                    Uri res = null;
                    try {
                        final File systemFile = getSystemService(StorageManager.class)
                                .translateAppToSystem(new File(path).getCanonicalFile(),
                                        callingPid, callingUid);
                        res = MediaService.onScanFile(MediaScannerService.this,
                                Uri.fromFile(systemFile));
                        Log.d(TAG, "Scanned " + path + " as " + systemFile + " for " + res);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to scan " + path, e);
                    }
                    if (listener != null) {
                        try {
                            listener.scanCompleted(path, res);
                        } catch (RemoteException ignored) {
                        }
                    }
                });
            }

            @Override
            public void scanFile(String path, String mimeType) {
                requestScanFile(path, mimeType, null);
            }
        };
    }
}
