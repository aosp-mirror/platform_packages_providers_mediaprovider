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
import android.os.UserHandle;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.JobIntentService;

import com.android.providers.media.util.FileUtils;

import java.io.File;
import java.io.IOException;

public class MediaService extends JobIntentService {
    private static final int JOB_ID = -300;

    private static final String ACTION_SCAN_VOLUME
            = "com.android.providers.media.action.SCAN_VOLUME";

    private static final String EXTRA_MEDIAVOLUME = "MediaVolume";

    private static final String EXTRA_SCAN_REASON = "scan_reason";


    public static void queueVolumeScan(Context context, MediaVolume volume, int reason) {
        Intent intent = new Intent(ACTION_SCAN_VOLUME);
        intent.putExtra(EXTRA_MEDIAVOLUME, volume) ;
        intent.putExtra(EXTRA_SCAN_REASON, reason);
        enqueueWork(context, intent);
    }

    public static void enqueueWork(Context context, Intent work) {
        enqueueWork(context, MediaService.class, JOB_ID, work);
    }

    @Override
    protected void onHandleWork(Intent intent) {
        Trace.beginSection("MediaService.handle[" + intent.getAction() + ']');
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
                    final int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
                    onPackageOrphaned(packageName, uid);
                    break;
                }
                case Intent.ACTION_MEDIA_SCANNER_SCAN_FILE: {
                    onScanFile(this, intent.getData());
                    break;
                }
                case Intent.ACTION_MEDIA_MOUNTED: {
                    onMediaMountedBroadcast(this, intent);
                    break;
                }
                case ACTION_SCAN_VOLUME: {
                    final MediaVolume volume = intent.getParcelableExtra(EXTRA_MEDIAVOLUME);
                    int reason = intent.getIntExtra(EXTRA_SCAN_REASON, REASON_DEMAND);
                    onScanVolume(this, volume, reason);
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

    private void onPackageOrphaned(String packageName, int uid) {
        try (ContentProviderClient cpc = getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            ((MediaProvider) cpc.getLocalContentProvider()).onPackageOrphaned(packageName, uid);
        }
    }

    private static void onMediaMountedBroadcast(Context context, Intent intent)
            throws IOException {
        final StorageVolume volume = intent.getParcelableExtra(StorageVolume.EXTRA_STORAGE_VOLUME);
        if (volume != null) {
            MediaVolume mediaVolume = MediaVolume.fromStorageVolume(volume);
            try (ContentProviderClient cpc = context.getContentResolver()
                    .acquireContentProviderClient(MediaStore.AUTHORITY)) {
                if (!((MediaProvider)cpc.getLocalContentProvider()).isVolumeAttached(mediaVolume)) {
                    // This can happen on some legacy app clone implementations, where the
                    // framework is modified to send MEDIA_MOUNTED broadcasts for clone volumes
                    // to u0 MediaProvider; these volumes are not reported through the usual
                    // volume attach events, so we need to scan them here if they weren't
                    // attached previously
                    onScanVolume(context, mediaVolume, REASON_MOUNTED);
                } else {
                    Log.i(TAG, "Volume " + mediaVolume + " already attached");
                }
            }
        } else {
            Log.e(TAG, "Couldn't retrieve StorageVolume from intent");
        }
    }

    public static void onScanVolume(Context context, MediaVolume volume, int reason)
            throws IOException {
        final String volumeName = volume.getName();
        if (!MediaStore.VOLUME_INTERNAL.equals(volumeName) && volume.getPath() == null) {
            /* This is a very unexpected state and can only ever happen with app-cloned users.
              In general, MediaVolumes should always be mounted and have a path, however, if the
              user failed to unlock properly, MediaProvider still gets the volume from the
              StorageManagerService because MediaProvider is special cased there. See
              StorageManagerService#getVolumeList. Reference bug: b/207723670. */
            Log.w(TAG, String.format("Skipping volume scan for %s when volume path is null.",
                    volumeName));
            return;
        }
        UserHandle owner = volume.getUser();
        if (owner == null) {
            // Can happen for the internal volume
            owner = context.getUser();
        }
        // If we're about to scan any external storage, scan internal first
        // to ensure that we have ringtones ready to roll before a possibly very
        // long external storage scan
        if (!MediaStore.VOLUME_INTERNAL.equals(volumeName)) {
            onScanVolume(context, MediaVolume.fromInternal(), reason);
            RingtoneManager.ensureDefaultRingtones(context);
        }

        // Resolve the Uri that we should use for all broadcast intents related
        // to this volume; we do this once to ensure we can deliver all events
        // in the situation where a volume is ejected mid-scan
        final Uri broadcastUri;
        if (!MediaStore.VOLUME_INTERNAL.equals(volumeName)) {
            broadcastUri = Uri.fromFile(volume.getPath());
        } else {
            broadcastUri = null;
        }

        try (ContentProviderClient cpc = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            final MediaProvider provider = ((MediaProvider) cpc.getLocalContentProvider());
            provider.attachVolume(volume, /* validate */ true);

            final ContentResolver resolver = ContentResolver.wrap(cpc.getLocalContentProvider());

            ContentValues values = new ContentValues();
            values.put(MediaStore.MEDIA_SCANNER_VOLUME, volumeName);
            Uri scanUri = resolver.insert(MediaStore.getMediaScannerUri(), values);

            if (broadcastUri != null) {
                context.sendBroadcastAsUser(
                        new Intent(Intent.ACTION_MEDIA_SCANNER_STARTED, broadcastUri), owner);
            }

            if (MediaStore.VOLUME_INTERNAL.equals(volumeName)) {
                for (File dir : FileUtils.getVolumeScanPaths(context, volumeName)) {
                    provider.scanDirectory(dir, reason);
                }
            } else {
                provider.scanDirectory(volume.getPath(), reason);
            }

            resolver.delete(scanUri, null, null);

        } finally {
            if (broadcastUri != null) {
                context.sendBroadcastAsUser(
                        new Intent(Intent.ACTION_MEDIA_SCANNER_FINISHED, broadcastUri), owner);
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

    @Override
    public boolean onStopCurrentWork() {
        // Scans are not stopped even if the job is stopped. So, no need to reschedule it again.
        // MediaProvider scans are highly unlikely to get killed. But even if it does, we would run
        // a scan on attachVolume(). But other requests to MediaService may get lost if
        // MediaProvider process is killed, which would otherwise have been rescheduled by
        // JobScheduler.
        // TODO(b/233357418): Fix this by adhering to the protocol of stopping current work when job
        // scheduler asks
        return false;
    }
}
