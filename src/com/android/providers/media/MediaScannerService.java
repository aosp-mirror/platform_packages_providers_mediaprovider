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

import android.app.IntentService;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Intent;
import android.media.IMediaScannerListener;
import android.media.IMediaScannerService;
import android.media.MediaScanner;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class MediaScannerService extends IntentService {
    private static final String TAG = "MediaScannerService";

    public MediaScannerService() {
        super(TAG);
    }

    private PowerManager.WakeLock mWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        mWakeLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        mWakeLock.acquire();
        Trace.traceBegin(Trace.TRACE_TAG_DATABASE, intent.getAction());
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Begin " + intent);
        }
        try {
            switch (intent.getAction()) {
                case Intent.ACTION_LOCALE_CHANGED: {
                    onLocaleChanged();
                    break;
                }
                case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                case Intent.ACTION_PACKAGE_DATA_CLEARED: {
                    final String packageName = intent.getData().getSchemeSpecificPart();
                    onPackageOrphaned(packageName);
                    break;
                }
                case Intent.ACTION_MEDIA_MOUNTED: {
                    onScanVolume(intent.getData());
                    break;
                }
                case Intent.ACTION_MEDIA_SCANNER_SCAN_FILE: {
                    onScanFile(intent.getData(), intent.getType());
                    break;
                }
                default: {
                    Log.w(TAG, "Unknown intent " + intent);
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed operation " + intent);
        } finally {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "End " + intent);
            }
            Trace.traceEnd(Trace.TRACE_TAG_DATABASE);
            mWakeLock.release();
        }
    }

    private void onLocaleChanged() {
        try (ContentProviderClient cpc = getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            ((MediaProvider) cpc.getLocalContentProvider()).onLocaleChanged();
        }
    }

    private void onPackageOrphaned(String packageName) {
        try (ContentProviderClient cpc = getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            ((MediaProvider) cpc.getLocalContentProvider()).onPackageOrphaned(packageName);
        }
    }

    private void onScanVolume(Uri uri) throws IOException {
        final File file = new File(uri.getPath()).getCanonicalFile();
        final String volumeName = MediaStore.getVolumeName(file);

        // If we're about to scan primary external storage, scan internal first
        // to ensure that we have ringtones ready to roll before a possibly very
        // long external storage scan
        if (MediaProvider.EXTERNAL_VOLUME.equals(volumeName)) {
            onScanVolume(Uri.fromFile(Environment.getRootDirectory()));
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MEDIA_SCANNER_VOLUME, volumeName);
            Uri scanUri = getContentResolver().insert(MediaStore.getMediaScannerUri(), values);

            if (!MediaProvider.INTERNAL_VOLUME.equals(volumeName)) {
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_STARTED, uri));
            }

            try (MediaScanner scanner = new MediaScanner(this, volumeName)) {
                scanner.scanDirectories(resolveDirectories(volumeName));
            }

            getContentResolver().delete(scanUri, null, null);

        } finally {
            if (!MediaProvider.INTERNAL_VOLUME.equals(volumeName)) {
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_FINISHED, uri));
            }
        }
    }

    private Uri onScanFile(Uri uri, String mimeType) throws IOException {
        final File file = new File(uri.getPath()).getCanonicalFile();
        final String volumeName = MediaStore.getVolumeName(file);

        try (MediaScanner scanner = new MediaScanner(this, volumeName)) {
            return scanner.scanSingleFile(file.getAbsolutePath(), mimeType);
        }
    }

    private String[] resolveDirectories(String volumeName) throws FileNotFoundException {
        if (MediaProvider.INTERNAL_VOLUME.equals(volumeName)) {
            return new String[] {
                    Environment.getRootDirectory() + "/media",
                    Environment.getOemDirectory() + "/media",
                    Environment.getProductDirectory() + "/media",
            };
        } else if (getSystemService(UserManager.class).isDemoUser()) {
            return new String[] {
                    MediaStore.getVolumePath(volumeName).getAbsolutePath(),
                    Environment.getDataPreloadsMediaDirectory().getAbsolutePath(),
            };
        } else {
            return new String[] {
                    MediaStore.getVolumePath(volumeName).getAbsolutePath(),
            };
        }
    }

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
                        res = onScanFile(Uri.fromFile(systemFile), mimeType);
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to scan " + path);
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
