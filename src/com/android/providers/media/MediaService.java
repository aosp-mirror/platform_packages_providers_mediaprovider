/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.providers.media.MediaProvider.TAG;

import android.app.IntentService;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScanner;
import android.net.Uri;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Trace;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

public class MediaService extends IntentService {
    public MediaService() {
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
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "Begin " + intent);
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
                case Intent.ACTION_MEDIA_MOUNTED:
                case Intent.ACTION_MEDIA_SCANNER_SCAN_VOLUME: {
                    onScanVolume(this, intent.getData());
                    break;
                }
                case Intent.ACTION_MEDIA_SCANNER_SCAN_FILE: {
                    onScanFile(this, intent.getData(), intent.getType());
                    break;
                }
                default: {
                    Log.w(TAG, "Unknown intent " + intent);
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed operation " + intent, e);
        } finally {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "End " + intent);
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

    public static void onScanVolume(Context context, Uri uri) throws IOException {
        final ContentResolver resolver = context.getContentResolver();

        final File file = new File(uri.getPath()).getCanonicalFile();
        final String volumeName = MediaStore.getVolumeName(file);

        // If we're about to scan primary external storage, scan internal first
        // to ensure that we have ringtones ready to roll before a possibly very
        // long external storage scan
        if (MediaProvider.EXTERNAL_VOLUME.equals(volumeName)) {
            onScanVolume(context, Uri.fromFile(Environment.getRootDirectory()));
        }

        try {
            try (ContentProviderClient cpc = resolver
                    .acquireContentProviderClient(MediaStore.AUTHORITY)) {
                ((MediaProvider) cpc.getLocalContentProvider()).attachVolume(volumeName);
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.MEDIA_SCANNER_VOLUME, volumeName);
            Uri scanUri = resolver.insert(MediaStore.getMediaScannerUri(), values);

            if (!MediaProvider.INTERNAL_VOLUME.equals(volumeName)) {
                context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_STARTED, uri));
            }

            try (MediaScanner scanner = new MediaScanner(context, volumeName)) {
                scanner.scanDirectories(resolveDirectories(volumeName));
            }

            resolver.delete(scanUri, null, null);

        } finally {
            if (!MediaProvider.INTERNAL_VOLUME.equals(volumeName)) {
                context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_FINISHED, uri));
            }
        }
    }

    public static Uri onScanFile(Context context, Uri uri, String mimeType) throws IOException {
        final File file = new File(uri.getPath()).getCanonicalFile();
        final String volumeName = MediaStore.getVolumeName(file);

        try (MediaScanner scanner = new MediaScanner(context, volumeName)) {
            return scanner.scanSingleFile(file.getAbsolutePath(), mimeType);
        }
    }

    private static String[] resolveDirectories(String volumeName) throws FileNotFoundException {
        final ArrayList<String> res = new ArrayList<>();
        for (File dir : MediaStore.getVolumeScanPaths(volumeName)) {
            res.add(dir.getAbsolutePath());
        }
        return res.toArray(new String[res.size()]);
    }
}
