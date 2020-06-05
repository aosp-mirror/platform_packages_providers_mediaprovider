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

import static com.android.providers.media.scan.MediaScanner.REASON_DEMAND;
import static com.android.providers.media.scan.MediaScanner.REASON_MOUNTED;
import static com.android.providers.media.util.Logging.TAG;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Trace;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.JobIntentService;

import com.android.providers.media.util.FileUtils;

import java.io.File;
import java.io.IOException;

public class MediaService extends JobIntentService {
    private static final int JOB_ID = -300;

    public static void enqueueWork(Context context, Intent work) {
        enqueueWork(context, MediaService.class, JOB_ID, work);
    }

    @Override
    protected void onHandleWork(Intent intent) {
        Trace.beginSection(intent.getAction());
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
                case Intent.ACTION_MEDIA_SCANNER_SCAN_FILE: {
                    onScanFile(this, intent.getData());
                    break;
                }
                case Intent.ACTION_MEDIA_MOUNTED: {
                    onScanVolume(this, intent.getData(), REASON_MOUNTED);
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
            Trace.endSection();
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

    private static void onScanVolume(Context context, Uri uri, int reason)
            throws IOException {
        final File file = new File(uri.getPath()).getCanonicalFile();
        final String volumeName = FileUtils.getVolumeName(context, file);

        onScanVolume(context, volumeName, reason);
    }

    public static void onScanVolume(Context context, String volumeName, int reason)
            throws IOException {
        // If we're about to scan any external storage, scan internal first
        // to ensure that we have ringtones ready to roll before a possibly very
        // long external storage scan
        if (!MediaStore.VOLUME_INTERNAL.equals(volumeName)) {
            onScanVolume(context, MediaStore.VOLUME_INTERNAL, reason);
            RingtoneManager.ensureDefaultRingtones(context);
        }

        // Resolve the Uri that we should use for all broadcast intents related
        // to this volume; we do this once to ensure we can deliver all events
        // in the situation where a volume is ejected mid-scan
        final Uri broadcastUri;
        if (!MediaStore.VOLUME_INTERNAL.equals(volumeName)) {
            broadcastUri = Uri.fromFile(FileUtils.getVolumePath(context, volumeName));
        } else {
            broadcastUri = null;
        }

        try (ContentProviderClient cpc = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            final MediaProvider provider = ((MediaProvider) cpc.getLocalContentProvider());
            provider.attachVolume(volumeName, /* validate */ true);

            final ContentResolver resolver = ContentResolver.wrap(cpc.getLocalContentProvider());

            ContentValues values = new ContentValues();
            values.put(MediaStore.MEDIA_SCANNER_VOLUME, volumeName);
            Uri scanUri = resolver.insert(MediaStore.getMediaScannerUri(), values);

            if (broadcastUri != null) {
                context.sendBroadcast(
                        new Intent(Intent.ACTION_MEDIA_SCANNER_STARTED, broadcastUri));
            }

            for (File dir : FileUtils.getVolumeScanPaths(context, volumeName)) {
                provider.scanDirectory(dir, reason);
            }

            resolver.delete(scanUri, null, null);

        } finally {
            if (broadcastUri != null) {
                context.sendBroadcast(
                        new Intent(Intent.ACTION_MEDIA_SCANNER_FINISHED, broadcastUri));
            }
        }
    }

    private static Uri onScanFile(Context context, Uri uri) throws IOException {
        final File file = new File(uri.getPath()).getCanonicalFile();
        try (ContentProviderClient cpc = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            final MediaProvider provider = ((MediaProvider) cpc.getLocalContentProvider());
            return provider.scanFile(file, REASON_DEMAND);
        }
    }
}
