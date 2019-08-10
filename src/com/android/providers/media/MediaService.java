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

import static android.media.RingtoneManager.TYPE_ALARM;
import static android.media.RingtoneManager.TYPE_NOTIFICATION;
import static android.media.RingtoneManager.TYPE_RINGTONE;

import static com.android.providers.media.MediaProvider.TAG;

import android.app.IntentService;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.provider.Settings;
import android.util.Log;

import com.android.providers.media.scan.MediaScanner;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;

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
                case Intent.ACTION_MEDIA_MOUNTED: {
                    onScanVolume(this, intent.getData());
                    break;
                }
                case Intent.ACTION_MEDIA_SCANNER_SCAN_FILE: {
                    onScanFile(this, intent.getData());
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
        final File file = new File(uri.getPath()).getCanonicalFile();
        final String volumeName = MediaStore.getVolumeName(file);

        // If we're about to scan primary external storage, scan internal first
        // to ensure that we have ringtones ready to roll before a possibly very
        // long external storage scan
        if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volumeName)) {
            onScanVolume(context, Uri.fromFile(Environment.getRootDirectory()));
            ensureDefaultRingtones(context);
        }

        try (ContentProviderClient cpc = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            ((MediaProvider) cpc.getLocalContentProvider()).attachVolume(volumeName);

            final ContentResolver resolver = ContentResolver.wrap(cpc.getLocalContentProvider());

            ContentValues values = new ContentValues();
            values.put(MediaStore.MEDIA_SCANNER_VOLUME, volumeName);
            Uri scanUri = resolver.insert(MediaStore.getMediaScannerUri(), values);

            if (!MediaStore.VOLUME_INTERNAL.equals(volumeName)) {
                context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_STARTED, uri));
            }

            for (File dir : resolveDirectories(volumeName)) {
                MediaScanner.instance(context).scanDirectory(dir);
            }

            resolver.delete(scanUri, null, null);

        } finally {
            if (!MediaStore.VOLUME_INTERNAL.equals(volumeName)) {
                context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_FINISHED, uri));
            }
        }
    }

    public static Uri onScanFile(Context context, Uri uri) throws IOException {
        final File file = new File(uri.getPath()).getCanonicalFile();
        return MediaScanner.instance(context).scanFile(file);
    }

    private static Collection<File> resolveDirectories(String volumeName)
            throws FileNotFoundException {
        return MediaStore.getVolumeScanPaths(volumeName);
    }

    /**
     * Ensure that we've set ringtones at least once after initial scan.
     */
    private static void ensureDefaultRingtones(Context context) {
        for (int type : new int[] {
                TYPE_RINGTONE,
                TYPE_NOTIFICATION,
                TYPE_ALARM,
        }) {
            // Skip if we've already defined it at least once, so we don't
            // overwrite the user changing to null
            final String setting = getDefaultRingtoneSetting(type);
            if (Settings.System.getInt(context.getContentResolver(), setting, 0) != 0) {
                continue;
            }

            // Try finding the scanned ringtone
            final String filename = getDefaultRingtoneFilename(type);
            final Uri baseUri = MediaStore.Audio.Media.INTERNAL_CONTENT_URI;
            try (Cursor cursor = context.getContentResolver().query(baseUri,
                    new String[] { MediaColumns._ID },
                    MediaColumns.DISPLAY_NAME + "=?",
                    new String[] { filename }, null)) {
                if (cursor.moveToFirst()) {
                    final Uri ringtoneUri = context.getContentResolver().canonicalizeOrElse(
                            ContentUris.withAppendedId(baseUri, cursor.getLong(0)));
                    RingtoneManager.setActualDefaultRingtoneUri(context, type, ringtoneUri);
                    Settings.System.putInt(context.getContentResolver(), setting, 1);
                }
            }
        }
    }

    private static String getDefaultRingtoneSetting(int type) {
        switch (type) {
            case TYPE_RINGTONE: return "ringtone_set";
            case TYPE_NOTIFICATION: return "notification_sound_set";
            case TYPE_ALARM: return "alarm_alert_set";
            default: throw new IllegalArgumentException();
        }
    }

    private static String getDefaultRingtoneFilename(int type) {
        switch (type) {
            case TYPE_RINGTONE: return SystemProperties.get("ro.config.ringtone");
            case TYPE_NOTIFICATION: return SystemProperties.get("ro.config.notification_sound");
            case TYPE_ALARM: return SystemProperties.get("ro.config.alarm_alert");
            default: throw new IllegalArgumentException();
        }
    }
}
