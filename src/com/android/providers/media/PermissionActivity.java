/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.providers.media.MediaProvider.AUDIO_MEDIA_ID;
import static com.android.providers.media.MediaProvider.IMAGES_MEDIA_ID;
import static com.android.providers.media.MediaProvider.VIDEO_MEDIA_ID;
import static com.android.providers.media.MediaProvider.collectUris;
import static com.android.providers.media.util.Logging.TAG;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.MediaProvider.LocalUriMatcher;
import com.android.providers.media.util.Metrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Permission dialog that asks for user confirmation before performing a
 * specific action, such as granting access for a narrow set of media files to
 * the calling app.
 *
 * @see MediaStore#createWriteRequest
 * @see MediaStore#createTrashRequest
 * @see MediaStore#createFavoriteRequest
 * @see MediaStore#createDeleteRequest
 */
public class PermissionActivity extends Activity {
    // TODO: narrow metrics to specific verb that was requested

    public static final int REQUEST_CODE = 42;

    private List<Uri> uris;
    private ContentValues values;

    private CharSequence label;
    private String verb;
    private String data;
    private String volumeName;

    private static final String VERB_WRITE = "write";
    private static final String VERB_TRASH = "trash";
    private static final String VERB_UNTRASH = "untrash";
    private static final String VERB_FAVORITE = "favorite";
    private static final String VERB_UNFAVORITE = "unfavorite";
    private static final String VERB_DELETE = "delete";

    private static final String DATA_AUDIO = "audio";
    private static final String DATA_VIDEO = "video";
    private static final String DATA_IMAGE = "image";
    private static final String DATA_GENERIC = "generic";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Strategy borrowed from PermissionController
        getWindow().addSystemFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        setFinishOnTouchOutside(false);

        // All untrusted input values here were validated when generating the
        // original PendingIntent
        try {
            uris = collectUris(getIntent().getExtras().getParcelable(MediaStore.EXTRA_CLIP_DATA));
            values = getIntent().getExtras().getParcelable(MediaStore.EXTRA_CONTENT_VALUES);

            label = resolveCallingLabel();
            verb = resolveVerb();
            data = resolveData();
            volumeName = MediaStore.getVolumeName(uris.get(0));
        } catch (Exception e) {
            Log.w(TAG, e);
            finish();
            return;
        }

        // Favorite-related requests are automatically granted for now; we still
        // make developers go through this no-op dialog flow to preserve our
        // ability to start prompting in the future
        switch (verb) {
            case VERB_FAVORITE:
            case VERB_UNFAVORITE: {
                onPositiveAction(null, 0);
                return;
            }
        }

        // Kick off async loading of description to show in dialog
        final View bodyView = getLayoutInflater().inflate(R.layout.permission_body, null, false);
        new DescriptionTask(bodyView).execute(uris);

        final CharSequence message = resolveMessageText();
        if (!TextUtils.isEmpty(message)) {
            final TextView messageView = bodyView.requireViewById(R.id.message);
            messageView.setVisibility(View.VISIBLE);
            messageView.setText(message);
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(resolveTitleText());
        builder.setPositiveButton(resolvePositiveText(), this::onPositiveAction);
        builder.setNegativeButton(resolveNegativeText(), this::onNegativeAction);
        builder.setCancelable(false);
        builder.setView(bodyView);

        final AlertDialog dialog = builder.show();
        final WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
        params.width = getResources().getDimensionPixelSize(R.dimen.permission_dialog_width);
        dialog.getWindow().setAttributes(params);
    }

    private void onPositiveAction(DialogInterface dialog, int which) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                Log.d(TAG, "User allowed grant for " + uris);
                Metrics.logPermissionGranted(volumeName,
                        System.currentTimeMillis(), getCallingPackage(), 1);
                try {
                    switch (getIntent().getAction()) {
                        case MediaStore.CREATE_WRITE_REQUEST_CALL: {
                            for (Uri uri : uris) {
                                grantUriPermission(getCallingPackage(), uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            }
                            break;
                        }
                        case MediaStore.CREATE_TRASH_REQUEST_CALL:
                        case MediaStore.CREATE_FAVORITE_REQUEST_CALL: {
                            final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
                            for (Uri uri : uris) {
                                ops.add(ContentProviderOperation.newUpdate(uri)
                                        .withValues(values)
                                        .withExceptionAllowed(true)
                                        .build());
                            }
                            getContentResolver().applyBatch(MediaStore.AUTHORITY, ops);
                            break;
                        }
                        case MediaStore.CREATE_DELETE_REQUEST_CALL: {
                            final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
                            for (Uri uri : uris) {
                                ops.add(ContentProviderOperation.newDelete(uri)
                                        .withExceptionAllowed(true)
                                        .build());
                            }
                            getContentResolver().applyBatch(MediaStore.AUTHORITY, ops);
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, e);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                setResult(Activity.RESULT_OK);
                finish();
            }
        }.execute();
    }

    private void onNegativeAction(DialogInterface dialog, int which) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                Log.d(TAG, "User declined request for " + uris);
                Metrics.logPermissionDenied(volumeName,
                        System.currentTimeMillis(), getCallingPackage(), 1);
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        }.execute();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Strategy borrowed from PermissionController
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Strategy borrowed from PermissionController
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    /**
     * Resolve a label that represents the remote calling app, typically the
     * name of that app.
     */
    private @NonNull CharSequence resolveCallingLabel() throws NameNotFoundException {
        final String callingPackage = getCallingPackage();
        if (TextUtils.isEmpty(callingPackage)) {
            throw new NameNotFoundException("Missing calling package");
        }

        final PackageManager pm = getPackageManager();
        final CharSequence callingLabel = pm
                .getApplicationLabel(pm.getApplicationInfo(callingPackage, 0));
        if (TextUtils.isEmpty(callingLabel)) {
            throw new NameNotFoundException("Missing calling package");
        }

        return callingLabel;
    }

    private @NonNull String resolveVerb() {
        switch (getIntent().getAction()) {
            case MediaStore.CREATE_WRITE_REQUEST_CALL:
                return VERB_WRITE;
            case MediaStore.CREATE_TRASH_REQUEST_CALL:
                return (values.getAsInteger(MediaColumns.IS_TRASHED) != 0)
                        ? VERB_TRASH : VERB_UNTRASH;
            case MediaStore.CREATE_FAVORITE_REQUEST_CALL:
                return (values.getAsInteger(MediaColumns.IS_FAVORITE) != 0)
                        ? VERB_FAVORITE : VERB_UNFAVORITE;
            case MediaStore.CREATE_DELETE_REQUEST_CALL:
                return VERB_DELETE;
            default:
                throw new IllegalArgumentException("Invalid action: " + getIntent().getAction());
        }
    }

    /**
     * Resolve what kind of data this permission request is asking about. If the
     * requested data is of mixed types, this returns {@link #DATA_GENERIC}.
     */
    private @NonNull String resolveData() {
        final LocalUriMatcher matcher = new LocalUriMatcher(MediaStore.AUTHORITY);
        final int firstMatch = matcher.matchUri(uris.get(0), false);
        for (int i = 1; i < uris.size(); i++) {
            final int match = matcher.matchUri(uris.get(i), false);
            if (match != firstMatch) {
                // Any mismatch means we need to use generic strings
                return DATA_GENERIC;
            }
        }
        switch (firstMatch) {
            case AUDIO_MEDIA_ID: return DATA_AUDIO;
            case VIDEO_MEDIA_ID: return DATA_VIDEO;
            case IMAGES_MEDIA_ID: return DATA_IMAGE;
            default: return DATA_GENERIC;
        }
    }

    /**
     * Resolve the dialog title string to be displayed to the user. All
     * arguments have been bound and this string is ready to be displayed.
     */
    private @Nullable CharSequence resolveTitleText() {
        final String resName = "permission_" + verb + "_" + data;
        final int resId = getResources().getIdentifier(resName, "plurals",
                getResources().getResourcePackageName(R.string.app_label));
        if (resId != 0) {
            final int count = uris.size();
            final CharSequence text = getResources().getQuantityText(resId, count);
            return TextUtils.expandTemplate(text, label, String.valueOf(count));
        } else {
            // We always need a string to prompt the user with
            throw new IllegalStateException("Invalid resource: " + resName);
        }
    }

    /**
     * Resolve the dialog message string to be displayed to the user, if any.
     * All arguments have been bound and this string is ready to be displayed.
     */
    private @Nullable CharSequence resolveMessageText() {
        final String resName = "permission_" + verb + "_" + data + "_info";
        final int resId = getResources().getIdentifier(resName, "plurals",
                getResources().getResourcePackageName(R.string.app_label));
        if (resId != 0) {
            final int count = uris.size();
            final long durationMillis = (values.getAsLong(MediaColumns.DATE_EXPIRES) * 1000)
                    - System.currentTimeMillis();
            final long durationDays = (durationMillis + DateUtils.DAY_IN_MILLIS)
                    / DateUtils.DAY_IN_MILLIS;
            final CharSequence text = getResources().getQuantityText(resId, count);
            return TextUtils.expandTemplate(text, label, String.valueOf(count),
                    String.valueOf(durationDays));
        } else {
            // Only some actions have a secondary message string; it's okay if
            // there isn't one defined
            return null;
        }
    }

    private @NonNull CharSequence resolvePositiveText() {
        final String resName = "permission_" + verb + "_grant";
        final int resId = getResources().getIdentifier(resName, "string",
                getResources().getResourcePackageName(R.string.app_label));
        return getResources().getText(resId);
    }

    private @NonNull CharSequence resolveNegativeText() {
        final String resName = "permission_" + verb + "_deny";
        final int resId = getResources().getIdentifier(resName, "string",
                getResources().getResourcePackageName(R.string.app_label));
        return getResources().getText(resId);
    }

    /**
     * Task that will load a set of {@link Description} to be eventually
     * displayed in the body of the dialog.
     */
    private class DescriptionTask extends AsyncTask<List<Uri>, Void, List<Description>> {
        private static final int MAX_THUMBS = 3;

        private View bodyView;
        private Resources res;

        public DescriptionTask(@NonNull View bodyView) {
            this.bodyView = bodyView;
            this.res = bodyView.getContext().getResources();
        }

        @Override
        protected List<Description> doInBackground(List<Uri>... params) {
            final List<Uri> uris = params[0];
            final List<Description> res = new ArrayList<>();

            // Default information that we'll load for each item
            int loadFlags = Description.LOAD_THUMBNAIL | Description.LOAD_CONTENT_DESCRIPTION;
            int neededThumbs = MAX_THUMBS;

            // If we're only asking for single item, load the full image
            if (uris.size() == 1) {
                loadFlags |= Description.LOAD_FULL;
            }

            for (Uri uri : uris) {
                try {
                    final Description desc = new Description(bodyView.getContext(), uri, loadFlags);
                    res.add(desc);

                    // Once we've loaded enough information to bind our UI, we
                    // can skip loading data for remaining requested items, but
                    // we still need to create them to show the correct counts
                    if (desc.isVisual()) {
                        neededThumbs--;
                    }
                    if (neededThumbs == 0) {
                        loadFlags = 0;
                    }
                } catch (Exception e) {
                    // Keep rolling forward to try getting enough descriptions
                    Log.w(TAG, e);
                }
            }
            return res;
        }

        @Override
        protected void onPostExecute(List<Description> results) {
            // Decide how to bind results based on how many are visual
            final List<Description> visualResults = results.stream().filter(Description::isVisual)
                    .collect(Collectors.toList());
            if (results.size() == 1 && visualResults.size() == 1) {
                bindAsFull(results.get(0));
            } else if (!visualResults.isEmpty()) {
                bindAsThumbs(results, visualResults);
            } else {
                bindAsText(results);
            }
        }

        /**
         * Bind dialog as a single full-bleed image.
         */
        private void bindAsFull(@NonNull Description result) {
            final ImageView thumbFull = bodyView.requireViewById(R.id.thumb_full);
            result.bindFull(thumbFull);
        }

        /**
         * Bind dialog as a list of multiple thumbnails.
         */
        private void bindAsThumbs(@NonNull List<Description> results,
                @NonNull List<Description> visualResults) {
            final List<ImageView> thumbs = new ArrayList<>();
            thumbs.add(bodyView.requireViewById(R.id.thumb1));
            thumbs.add(bodyView.requireViewById(R.id.thumb2));
            thumbs.add(bodyView.requireViewById(R.id.thumb3));

            // We're going to show the "more" tile when we can't display
            // everything requested, but we have at least one visual item
            final boolean showMore = (visualResults.size() != results.size())
                    || (visualResults.size() > MAX_THUMBS);
            if (showMore) {
                final View thumbMoreContainer = bodyView.requireViewById(R.id.thumb_more_container);
                final ImageView thumbMore = bodyView.requireViewById(R.id.thumb_more);
                final TextView thumbMoreText = bodyView.requireViewById(R.id.thumb_more_text);

                // Since we only want three tiles displayed maximum, swap out
                // the first tile for our "more" tile
                thumbs.remove(0);
                thumbs.add(thumbMore);

                final int shownCount = Math.min(visualResults.size(), MAX_THUMBS - 1);
                final int moreCount = results.size() - shownCount;
                final CharSequence moreText = TextUtils.expandTemplate(res.getQuantityText(
                        R.plurals.permission_more_thumb, moreCount), String.valueOf(moreCount));

                thumbMoreText.setText(moreText);
                thumbMoreContainer.setVisibility(View.VISIBLE);
            }

            // Trim off extra thumbnails from the front of our list, so that we
            // always bind any "more" item last
            while (thumbs.size() > visualResults.size()) {
                thumbs.remove(0);
            }

            // Finally we can bind all our thumbnails into place
            for (int i = 0; i < thumbs.size(); i++) {
                final Description desc = visualResults.get(i);
                final ImageView imageView = thumbs.get(i);
                desc.bindThumbnail(imageView);
            }
        }

        /**
         * Bind dialog as a list of text descriptions, typically when there's no
         * visual representation of the items.
         */
        private void bindAsText(@NonNull List<Description> results) {
            final List<CharSequence> list = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                list.add(results.get(i).contentDescription);

                if (list.size() >= MAX_THUMBS && results.size() > list.size()) {
                    final int moreCount = results.size() - list.size();
                    final CharSequence moreText = TextUtils.expandTemplate(res.getQuantityText(
                            R.plurals.permission_more_text, moreCount), String.valueOf(moreCount));
                    list.add(moreText);
                    break;
                }
            }

            final TextView text = bodyView.requireViewById(R.id.list);
            text.setText(TextUtils.join("\n", list));
            text.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Description of a single media item.
     */
    private static class Description {
        public @Nullable CharSequence contentDescription;
        public @Nullable Bitmap thumbnail;
        public @Nullable Bitmap full;

        public static final int LOAD_CONTENT_DESCRIPTION = 1 << 0;
        public static final int LOAD_THUMBNAIL = 1 << 1;
        public static final int LOAD_FULL = 1 << 2;

        public Description(Context context, Uri uri, int loadFlags) {
            final Resources res = context.getResources();
            final ContentResolver resolver = context.getContentResolver();

            try {
                // Load description first so that we'll always have something
                // textual to display in case we have image trouble below
                if ((loadFlags & LOAD_CONTENT_DESCRIPTION) != 0) {
                    try (Cursor c = resolver.query(uri,
                            new String[] { MediaColumns.DISPLAY_NAME }, null, null)) {
                        if (c.moveToFirst()) {
                            contentDescription = c.getString(0);
                        }
                    }
                }
                if ((loadFlags & LOAD_THUMBNAIL) != 0) {
                    final Size size = new Size(res.getDisplayMetrics().widthPixels,
                            res.getDisplayMetrics().widthPixels);
                    thumbnail = resolver.loadThumbnail(uri, size, null);
                }
                if ((loadFlags & LOAD_FULL) != 0) {
                    full = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri));
                }
            } catch (IOException e) {
                Log.w(TAG, e);
            }
        }

        public boolean isVisual() {
            return thumbnail != null || full != null;
        }

        public void bindThumbnail(ImageView imageView) {
            Objects.requireNonNull(thumbnail);
            imageView.setImageBitmap(thumbnail);
            imageView.setContentDescription(contentDescription);
            imageView.setVisibility(View.VISIBLE);
            imageView.setClipToOutline(true);
        }

        public void bindFull(ImageView imageView) {
            Objects.requireNonNull(full);
            imageView.setImageBitmap(full);
            imageView.setContentDescription(contentDescription);
            imageView.setVisibility(View.VISIBLE);
        }
    }
}
