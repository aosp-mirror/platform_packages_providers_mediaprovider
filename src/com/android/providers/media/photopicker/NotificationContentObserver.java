/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media.photopicker;

import static com.android.providers.media.PickerUriResolver.PICKER_INTERNAL_URI;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link ContentObserver} to listen to notification on database update
 * (for e.g. cloud sync completion of a batch).
 *
 * <p> This observer listens to below uris:
 * <ul>
 * <li>content://media/picker_internal/update</li>
 * <li>content://media/picker_internal/update/media</li>
 * <li>content://media/picker_internal/update/album_content/ALBUM_ID</li>
 * </ul>
 *
 * <p> The notification received will contain date_taken_ms
 * {@link android.provider.CloudMediaProviderContract.MediaColumns#DATE_TAKEN_MILLIS} or
 * {@link android.provider.CloudMediaProviderContract.AlbumColumns#DATE_TAKEN_MILLIS}.
 * In case of album content, it will also contain
 * {@link android.provider.CloudMediaProviderContract#EXTRA_ALBUM_ID}
 */
public class NotificationContentObserver extends ContentObserver {
    private static final String TAG = "NotificationContentObserver";

    /**
     * Callback triggered upon receiving notification.
     */
    public interface ContentObserverCallback{
        /**
         * Callers must implement this to handle the notification received.
         *
         * @param dateTakenMs date_taken_ms of the update
         * @param albumId album_id in case of album_content update. Null in case of media update
         */
        void onNotificationReceived(String dateTakenMs, String albumId);
    }

    // Key: Collection of preference keys, Value: onChange callback for keys
    private final Map<List<String>, ContentObserverCallback> mUrisToCallback = new HashMap<>();

    public static final String UPDATE = "update";
    public static final String MEDIA = "media";
    public static final String ALBUM_CONTENT = "album_content";

    private final List<String> mKeys;
    private final List<Uri> mUris;

    private static final Uri URI_UPDATE = PICKER_INTERNAL_URI.buildUpon()
            .appendPath(UPDATE).build();

    private static final Uri URI_UPDATE_MEDIA = URI_UPDATE.buildUpon()
            .appendPath(MEDIA).build();

    private static final Uri URI_UPDATE_ALBUM_CONTENT = URI_UPDATE.buildUpon()
            .appendPath(ALBUM_CONTENT).build();

    public static final String REGEX_MEDIA = URI_UPDATE_MEDIA + "/[0-9]*$";
    public static final Pattern PATTERN_MEDIA = Pattern.compile(REGEX_MEDIA);
    public static final String REGEX_ALBUM_CONTENT = URI_UPDATE_ALBUM_CONTENT + "/[0-9]*/[0-9]*$";
    public static final Pattern PATTERN_ALBUM_CONTENT = Pattern.compile(REGEX_ALBUM_CONTENT);

    /**
     * Creates a content observer.
     *
     * @param handler The handler to run {@link #onChange} on, or null if none.
     */
    public NotificationContentObserver(Handler handler) {
        super(handler);
        mKeys = Arrays.asList(MEDIA, ALBUM_CONTENT);
        mUris = Arrays.asList(URI_UPDATE_MEDIA, URI_UPDATE_ALBUM_CONTENT);
    }

    /**
     * Registers {@link ContentObserver} instance of this class to the resolver for {@link #mUris}.
     */
    public void register(ContentResolver contentResolver) {
        for (Uri uri : mUris) {
            contentResolver.registerContentObserver(uri, /* notifyForDescendants */ true,
                    /* observer */ this);
        }
    }

    /**
     * Unregisters ContentObserver
     */
    public void unregister(ContentResolver contentResolver) {
        contentResolver.unregisterContentObserver(this);
    }

    /**
     * {@link ContentObserverCallback} is added to {@link ContentObserver} to handle the
     * onNotificationReceived event triggered by the key collection of {@code keysToObserve}.
     *
     * <p> Note: Observer can observe the keys present in {@link #mKeys}.
     *
     * @param observerCallback A callback which is used to handle the onNotificationReceived event
     *                         triggered by the key collection of {@code keysToObserve}.
     */
    public void registerKeysToObserverCallback(List<String> keysToObserve,
            ContentObserverCallback observerCallback) {
        boolean hasValidKey = false;
        for (String key : keysToObserve) {
            if (!mKeys.contains(key)) {
                Log.w(TAG, "NotificationContentObserver can not observer the key: " + key
                        + ". Please pass valid keys from " + mKeys);
                continue;
            }
            hasValidKey = true;
        }
        if (hasValidKey) {
            mUrisToCallback.put(keysToObserve, observerCallback);
        }
    }

    @Override
    public final void onChange(boolean selfChange, Uri uri) {
        String albumId = null;
        String key = null;

        if (PATTERN_MEDIA.matcher(uri.toString()).find()) {
            key = MEDIA;
        } else if (PATTERN_ALBUM_CONTENT.matcher(uri.toString()).find()) {
            key = ALBUM_CONTENT;
            albumId = uri.getPathSegments().get(3);
        } else {
            Log.w(TAG, "NotificationContentObserver cannot parse uri: " + uri
                    + " . Please send correct uri path.");
            return;
        }

        String dateTakenMs = uri.getLastPathSegment();

        for (List<String> keys : mUrisToCallback.keySet()) {
            if (keys.contains(key)) {
                mUrisToCallback.get(keys).onNotificationReceived(dateTakenMs, albumId);
            }
        }
    }

    @VisibleForTesting
    public Map<List<String>, ContentObserverCallback> getUrisToCallback() {
        return mUrisToCallback;
    }
}
