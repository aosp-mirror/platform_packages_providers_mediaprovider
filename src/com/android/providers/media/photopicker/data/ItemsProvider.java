/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker.data;

import static android.content.ContentResolver.QUERY_ARG_LIMIT;
import static android.widget.Toast.LENGTH_LONG;

import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.UserId;

import java.util.Arrays;

/**
 * Provides image and video items from {@link MediaStore} collection to the Photo Picker.
 */
public class ItemsProvider {
    private static final String TAG = ItemsProvider.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final Context mContext;

    public ItemsProvider(Context context) {
        mContext = context;
        ensureNotificationHandler(context);
    }

    /**
     * Returns a {@link Cursor} to all images/videos based on the param passed for
     * {@code categoryType}, {@code offset}, {@code limit}, {@code mimeTypes} and {@code userId}.
     *
     * <p>
     * By default the returned {@link Cursor} sorts by latest date taken.
     *
     * @param category the category of items to return. May be cloud, local or merged albums like
     * favorites or videos.
     * @param offset the offset after which to return items.
     * @param limit the limit of number of items to return.
     * @param mimeType the mime type of item. {@code null} returns all images/videos that are
     *                 scanned by {@link MediaStore}.
     * @param userId the {@link UserId} of the user to get items as.
     *               {@code null} defaults to {@link UserId#CURRENT_USER}
     *
     * @return {@link Cursor} to images/videos on external storage that are scanned by
     * {@link MediaStore}. The returned cursor is filtered based on params passed, it {@code null}
     * if there are no such images/videos. The Cursor for each item contains {@link ItemColumns}
     */
    @Nullable
    public Cursor getItems(Category category, int offset,
            int limit, @Nullable String[] mimeTypes, @Nullable UserId userId) throws
            IllegalArgumentException {
        Trace.beginSection("ItemsProvider.getItems");
        sNotificationHandler.onLoadingStarted();
        try {
            if (DEBUG) {
                Log.d(TAG, "getItems() [" + Thread.currentThread() + "] userId=" + userId
                        + " cat=" + category + " mimeTypes=" + Arrays.toString(mimeTypes)
                        + " offset=" + offset + " limit=" + limit);
            }
            if (userId == null) {
                userId = UserId.CURRENT_USER;
            }
            return queryMedia(limit, mimeTypes, category, userId);
        } finally {
            sNotificationHandler.onLoadingFinished();
            Trace.endSection();
        }
    }

    /**
     * Returns a {@link Cursor} to all non-empty categories in which images/videos are categorised.
     * This includes:
     * * A constant list of local categories for on-device images/videos: {@link Category}
     * * Albums provided by selected cloud provider
     *
     * @param mimeType the mime type of item. {@code null} returns all images/videos that are
     *                 scanned by {@link MediaStore}.
     * @param userId the {@link UserId} of the user to get categories as.
     *               {@code null} defaults to {@link UserId#CURRENT_USER}.
     *
     * @return {@link Cursor} for each category would contain the following columns in
     * their relative order:
     * categoryName: {@link CategoryColumns#NAME} The name of the category,
     * categoryCoverId: {@link CategoryColumns#COVER_ID} The id for the cover of
     *                   the category. By default this will be the most recent image/video in that
     *                   category,
     * categoryNumberOfItems: {@link CategoryColumns#NUMBER_OF_ITEMS} number of image/video items
     *                        in the category,
     */
    @Nullable
    public Cursor getCategories(@Nullable String[] mimeTypes, @Nullable UserId userId) {
        Trace.beginSection("ItemsProvider.getCategories");
        sNotificationHandler.onLoadingStarted();
        try {
            if (DEBUG) {
                Log.d(TAG, "getCategories() [" + Thread.currentThread() + "] userId=" + userId
                        + " mimeTypes=" + Arrays.toString(mimeTypes));
            }
            if (userId == null) {
                userId = UserId.CURRENT_USER;
            }
            return queryAlbums(mimeTypes, userId);
        } finally {
            sNotificationHandler.onLoadingFinished();
            Trace.endSection();
        }
    }

    private Cursor queryMedia(int limit, String[] mimeTypes,
            @NonNull Category category, @NonNull UserId userId)
            throws IllegalStateException {
        Trace.beginSection("ItemsProvider.queryMedia");
        if (DEBUG) {
            Log.d(TAG, "queryMedia() [" + Thread.currentThread() + "] userId=" + userId
                    + " cat=" + category + " mimeTypes=" + Arrays.toString(mimeTypes)
                    + " limit=" + limit,
                    /* log stacktrace */ new Throwable());
        }

        final Bundle extras = new Bundle();
        try (ContentProviderClient client = userId.getContentResolver(mContext)
                .acquireUnstableContentProviderClient(MediaStore.AUTHORITY)) {
            if (client == null) {
                Log.e(TAG, "Unable to acquire unstable content provider for "
                        + MediaStore.AUTHORITY);
                return null;
            }
            extras.putInt(QUERY_ARG_LIMIT, limit);
            if (mimeTypes != null) {
                extras.putStringArray(MediaStore.QUERY_ARG_MIME_TYPE, mimeTypes);
            }
            extras.putString(MediaStore.QUERY_ARG_ALBUM_ID, category.getId());
            extras.putString(MediaStore.QUERY_ARG_ALBUM_AUTHORITY, category.getAuthority());

            final Uri uri = PickerUriResolver.PICKER_INTERNAL_URI.buildUpon()
                    .appendPath(PickerUriResolver.MEDIA_PATH).build();

            return client.query(uri, /* projection */ null, extras, /* cancellationSignal */ null);
        } catch (RemoteException | NameNotFoundException ignored) {
            // Do nothing, return null.
            Log.e(TAG, "Failed to query merged media with extras: "
                    + extras + ". userId = " + userId, ignored);
            return null;
        } finally {
            Trace.endSection();
        }
    }

    @Nullable
    private Cursor queryAlbums(@Nullable String[] mimeTypes, @NonNull UserId userId) {
        Trace.beginSection("ItemsProvider.queryAlbums");
        if (DEBUG) {
            Log.d(TAG, "queryAlbums() [" + Thread.currentThread() + "] userId=" + userId
                    + " mimeTypes=" + Arrays.toString(mimeTypes),
                    /* log stacktrace */ new Throwable());
        }

        final Bundle extras = new Bundle();
        try (ContentProviderClient client = userId.getContentResolver(mContext)
                .acquireUnstableContentProviderClient(MediaStore.AUTHORITY)) {
            if (client == null) {
                Log.e(TAG, "Unable to acquire unstable content provider for "
                        + MediaStore.AUTHORITY);
                return null;
            }
            if (mimeTypes != null) {
                extras.putStringArray(MediaStore.QUERY_ARG_MIME_TYPE, mimeTypes);
            }

            final Uri uri = PickerUriResolver.PICKER_INTERNAL_URI.buildUpon()
                    .appendPath(PickerUriResolver.ALBUM_PATH).build();

            return client.query(uri, /* projection */ null, extras, /* cancellationSignal */ null);
        } catch (RemoteException | NameNotFoundException ignored) {
            // Do nothing, return null.
            Log.w(TAG, "Failed to query merged albums with extras: "
                    + extras + ". userId = " + userId, ignored);
            return null;
        } finally {
            Trace.endSection();
        }
    }

    public static Uri getItemsUri(String id, String authority, UserId userId) {
        final Uri uri = PickerUriResolver.getMediaUri(authority).buildUpon()
                .appendPath(id).build();

        if (userId.equals(UserId.CURRENT_USER)) {
            return uri;
        }

        return createContentUriForUser(uri, userId.getUserHandle());
    }

    private static Uri createContentUriForUser(Uri uri, UserHandle userHandle) {
        if (SdkLevel.isAtLeastS()) {
            return ContentProvider.createContentUriForUser(uri, userHandle);
        }

        return createContentUriForUserImpl(uri, userHandle);
    }

    /**
     * This method is a copy of {@link ContentProvider#createContentUriForUser(Uri, UserHandle)}
     * which is a System API added in Android S.
     */
    private static Uri createContentUriForUserImpl(Uri uri, UserHandle userHandle) {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IllegalArgumentException(String.format(
                    "Given URI [%s] is not a content URI: ", uri));
        }

        int userId = userHandle.getIdentifier();
        if (uriHasUserId(uri)) {
            if (String.valueOf(userId).equals(uri.getUserInfo())) {
                return uri;
            }
            throw new IllegalArgumentException(String.format(
                    "Given URI [%s] already has a user ID, different from given user handle [%s]",
                    uri,
                    userId));
        }

        Uri.Builder builder = uri.buildUpon();
        builder.encodedAuthority(
                "" + userHandle.getIdentifier() + "@" + uri.getEncodedAuthority());
        return builder.build();
    }

    private static boolean uriHasUserId(Uri uri) {
        if (uri == null) return false;
        return !TextUtils.isEmpty(uri.getUserInfo());
    }

    // TODO(b/257887919): Build proper UI and remove all this monstrosity below!
    private static volatile @Nullable NotificationHandler sNotificationHandler;

    private static void ensureNotificationHandler(@NonNull Context context) {
        if (sNotificationHandler == null) {
            synchronized (PickerSyncController.class) {
                if (sNotificationHandler == null) {
                    sNotificationHandler = new NotificationHandler(context);
                }
            }
        }
    }

    private static class NotificationHandler extends Handler {
        static final int MESSAGE_CODE_STARTED_LOADING = 1;
        static final int MESSAGE_CODE_TICK = 2;
        static final int MESSAGE_CODE_FINISHED_LOADING = 3;

        static final int FIRST_TICK_DELAY = 1_000; // 1 second
        static final int TICK_DELAY = 30_000; // 30 seconds

        final Context mContext;

        NotificationHandler(@NonNull Context context) {
            // It will be running on the UI thread.
            super(Looper.getMainLooper());
            mContext = context.getApplicationContext();
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case MESSAGE_CODE_STARTED_LOADING:
                    if (hasMessages(MESSAGE_CODE_TICK)) {
                        // Already have scheduled ticks - do nothing.
                        return;
                    }
                    // Wait 1 sec before actually showing the first notification (so that we don't
                    // annoy users with our Toasts if the loading actually takes less than 1 sec).
                    sendTickMessageDelayed(/* seqNum */ 1, FIRST_TICK_DELAY);
                    break;

                case MESSAGE_CODE_TICK:
                    final int seqNum = msg.arg1;

                    // These Strings are intentionally hardcoded here instead of being added to
                    // the res/values/strings.xml.
                    // They are to be used in droidfood only, not to be translated, and must be
                    // removed very soon!
                    final String text;
                    if (seqNum == 1) {
                        text = "Syncing your cloud media library...";
                    } else {
                        text = "Still syncing your cloud media library...";
                    }
                    Toast.makeText(mContext, "[Dogfood: known issue] " + text, LENGTH_LONG).show();

                    // Do not show more than 10 of these.
                    if (seqNum < 10) {
                        // Show next tick in 30 seconds.
                        sendTickMessageDelayed(/* seqNum */ seqNum + 1, TICK_DELAY);
                    }
                    break;

                case MESSAGE_CODE_FINISHED_LOADING:
                    removeMessages(MESSAGE_CODE_STARTED_LOADING);
                    removeMessages(MESSAGE_CODE_TICK);
                    break;

                default:
                    super.handleMessage(msg);
            }
        }

        void onLoadingStarted() {
            sendEmptyMessage(MESSAGE_CODE_STARTED_LOADING);
        }

        void onLoadingFinished() {
            sendEmptyMessage(MESSAGE_CODE_FINISHED_LOADING);
        }

        private void sendTickMessageDelayed(int seqNum, int delay) {
            final Message message = obtainMessage(MESSAGE_CODE_TICK);
            message.arg1 = seqNum;

            sendMessageDelayed(message, delay);
        }
    }
}
