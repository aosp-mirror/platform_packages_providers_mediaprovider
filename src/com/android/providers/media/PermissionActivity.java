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
import static com.android.providers.media.util.DatabaseUtils.getAsBoolean;
import static com.android.providers.media.util.Logging.TAG;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.ImageDecoder.ImageInfo;
import android.graphics.ImageDecoder.Source;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.MediaProvider.LocalUriMatcher;
import com.android.providers.media.util.Metrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
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
    private ApplicationInfo appInfo;

    private ProgressDialog progressDialog;
    private TextView titleView;

    private static final Long LEAST_SHOW_PROGRESS_TIME_MS = 300L;

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

    // Use to sort the thumbnails.
    private static final int ORDER_IMAGE = 1;
    private static final int ORDER_VIDEO = 2;
    private static final int ORDER_AUDIO = 3;
    private static final int ORDER_GENERIC = 4;

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

            appInfo = resolveCallingAppInfo();
            label = resolveAppLabel(appInfo);
            verb = resolveVerb();
            data = resolveData();
            volumeName = MediaStore.getVolumeName(uris.get(0));
        } catch (Exception e) {
            Log.w(TAG, e);
            finish();
            return;
        }

        progressDialog = new ProgressDialog(this);

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
        // We set the title in message so that the text doesn't get truncated
        builder.setMessage(resolveTitleText());
        builder.setPositiveButton(R.string.allow, this::onPositiveAction);
        builder.setNegativeButton(R.string.deny, this::onNegativeAction);
        builder.setCancelable(false);
        builder.setView(bodyView);

        final AlertDialog dialog = builder.show();

        // The title is being set as a message above.
        // We need to style it like the default AlertDialog title
        TextView dialogMessage = (TextView) dialog.findViewById(
                android.R.id.message);
        if (dialogMessage != null) {
            dialogMessage.setTextAppearance(R.style.PermissionAlertDialogTitle);
        } else {
            Log.w(TAG, "Couldn't find message element");
        }

        final WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
        params.width = getResources().getDimensionPixelSize(R.dimen.permission_dialog_width);
        dialog.getWindow().setAttributes(params);

        // Hunt around to find the title of our newly created dialog so we can
        // adjust accessibility focus once descriptions have been loaded
        titleView = (TextView) findViewByPredicate(dialog.getWindow().getDecorView(), (view) -> {
            return (view instanceof TextView) && view.isImportantForAccessibility();
        });
    }

    private void onPositiveAction(@Nullable DialogInterface dialog, int which) {
        // Disable the buttons
        if (dialog != null) {
            ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }

        progressDialog.show();
        final long startTime = System.currentTimeMillis();
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                Log.d(TAG, "User allowed grant for " + uris);
                Metrics.logPermissionGranted(volumeName, appInfo.uid,
                        getCallingPackage(), uris.size());
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
                                        .withExtra(MediaStore.QUERY_ARG_ALLOW_MOVEMENT, true)
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
                // Don't dismiss the progress dialog too quick, it will cause bad UX.
                final long duration = System.currentTimeMillis() - startTime;
                if (duration > LEAST_SHOW_PROGRESS_TIME_MS) {
                    progressDialog.dismiss();
                    finish();
                } else {
                    Handler handler = new Handler(getMainLooper());
                    handler.postDelayed(() -> {
                        progressDialog.dismiss();
                        finish();
                    }, LEAST_SHOW_PROGRESS_TIME_MS - duration);
                }
            }
        }.execute();
    }

    private void onNegativeAction(DialogInterface dialog, int which) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                Log.d(TAG, "User declined request for " + uris);
                Metrics.logPermissionDenied(volumeName, appInfo.uid, getCallingPackage(),
                        1);
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
     * Resolve a label that represents the app denoted by given {@link ApplicationInfo}.
     */
    private @NonNull CharSequence resolveAppLabel(final ApplicationInfo ai)
            throws NameNotFoundException {
        final PackageManager pm = getPackageManager();
        final CharSequence callingLabel = pm.getApplicationLabel(ai);
        if (TextUtils.isEmpty(callingLabel)) {
            throw new NameNotFoundException("Missing calling package");
        }

        return callingLabel;
    }

    /**
     * Resolve the application info of the calling app.
     */
    private @NonNull ApplicationInfo resolveCallingAppInfo() throws NameNotFoundException {
        final String callingPackage = getCallingPackage();
        if (TextUtils.isEmpty(callingPackage)) {
            throw new NameNotFoundException("Missing calling package");
        }

        return getPackageManager().getApplicationInfo(callingPackage, 0);
    }

    private @NonNull String resolveVerb() {
        switch (getIntent().getAction()) {
            case MediaStore.CREATE_WRITE_REQUEST_CALL:
                return VERB_WRITE;
            case MediaStore.CREATE_TRASH_REQUEST_CALL:
                return getAsBoolean(values, MediaColumns.IS_TRASHED, false)
                        ? VERB_TRASH : VERB_UNTRASH;
            case MediaStore.CREATE_FAVORITE_REQUEST_CALL:
                return getAsBoolean(values, MediaColumns.IS_FAVORITE, false)
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
     * Recursively walk the given view hierarchy looking for the first
     * {@link View} which matches the given predicate.
     */
    private static @Nullable View findViewByPredicate(@NonNull View root,
            @NonNull Predicate<View> predicate) {
        if (predicate.test(root)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View res = findViewByPredicate(group.getChildAt(i), predicate);
                if (res != null) {
                    return res;
                }
            }
        }
        return null;
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

            // If the size is zero, return the res directly.
            if (uris.isEmpty()) {
                return res;
            }

            // Default information that we'll load for each item
            int loadFlags = Description.LOAD_THUMBNAIL | Description.LOAD_CONTENT_DESCRIPTION;
            int neededThumbs = MAX_THUMBS;

            // If we're only asking for single item, load the full image
            if (uris.size() == 1) {
                // Set visible to the thumb_full to avoid the size
                // changed of the dialog in full decoding.
                final ImageView thumbFull = bodyView.requireViewById(R.id.thumb_full);
                thumbFull.setVisibility(View.VISIBLE);
                loadFlags |= Description.LOAD_FULL;
            } else {
                // If the size equals 2, we will remove thumb1 later.
                // Set visible to the thumb2 and thumb3 first to avoid
                // the size changed of the dialog.
                ImageView thumb = bodyView.requireViewById(R.id.thumb2);
                thumb.setVisibility(View.VISIBLE);
                thumb = bodyView.requireViewById(R.id.thumb3);
                thumb.setVisibility(View.VISIBLE);
                // If the count of thumbs equals to MAX_THUMBS, set visible to thumb1.
                if (uris.size() == MAX_THUMBS) {
                    thumb = bodyView.requireViewById(R.id.thumb1);
                    thumb.setVisibility(View.VISIBLE);
                } else if (uris.size() > MAX_THUMBS) {
                    // If the count is larger than MAX_THUMBS, set visible to
                    // thumb_more_container.
                    final View container = bodyView.requireViewById(R.id.thumb_more_container);
                    container.setVisibility(View.VISIBLE);
                }
            }

            // Sort the uris in DATA_GENERIC case (Image, Video, Audio, Others)
            if (TextUtils.equals(data, DATA_GENERIC) && uris.size() > 1) {
                final ToIntFunction<Uri> score = (uri) -> {
                    final LocalUriMatcher matcher = new LocalUriMatcher(MediaStore.AUTHORITY);
                    final int match = matcher.matchUri(uri, false);

                    switch (match) {
                        case AUDIO_MEDIA_ID: return ORDER_AUDIO;
                        case VIDEO_MEDIA_ID: return ORDER_VIDEO;
                        case IMAGES_MEDIA_ID: return ORDER_IMAGE;
                        default: return ORDER_GENERIC;
                    }
                };
                final Comparator<Uri> bestScore = (a, b) ->
                        score.applyAsInt(a) - score.applyAsInt(b);

                uris.sort(bestScore);
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

            // This is pretty hacky, but somehow our dynamic loading of content
            // can confuse accessibility focus, so refocus on the actual dialog
            // title to announce ourselves properly
            titleView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
        }

        /**
         * Bind dialog as a single full-bleed image. If there is no image, use
         * the icon of Mime type instead.
         */
        private void bindAsFull(@NonNull Description result) {
            final ImageView thumbFull = bodyView.requireViewById(R.id.thumb_full);
            if (result.full != null) {
                result.bindFull(thumbFull);
            } else {
                thumbFull.setScaleType(ImageView.ScaleType.FIT_CENTER);
                thumbFull.setBackground(new ColorDrawable(getColor(R.color.thumb_gray_color)));
                result.bindMimeIcon(thumbFull);
            }
        }

        /**
         * Bind dialog as a list of multiple thumbnails. If there is no thumbnail for some
         * items, use the icons of the MIME type instead.
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
                final View gradientView = bodyView.requireViewById(R.id.thumb_more_gradient);

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
                gradientView.setVisibility(View.VISIBLE);
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
                if (desc.thumbnail != null) {
                    desc.bindThumbnail(imageView);
                } else {
                    desc.bindMimeIcon(imageView);
                }
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
        public @Nullable Icon mimeIcon;

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
                    // Only offer full decodes when a supported file type;
                    // otherwise fall back to using thumbnail
                    final String mimeType = resolver.getType(uri);
                    if (ImageDecoder.isMimeTypeSupported(mimeType)) {
                        full = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri),
                                new Resizer(context.getResources().getDisplayMetrics()));
                    } else {
                        full = thumbnail;
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, e);
                if (thumbnail == null && full == null) {
                    final String mimeType = resolver.getType(uri);
                    mimeIcon = resolver.getTypeInfo(mimeType).getIcon();
                }
            }
        }

        public boolean isVisual() {
            return thumbnail != null || full != null || mimeIcon != null;
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

        public void bindMimeIcon(ImageView imageView) {
            Objects.requireNonNull(mimeIcon);
            imageView.setImageIcon(mimeIcon);
            imageView.setContentDescription(contentDescription);
            imageView.setVisibility(View.VISIBLE);
            imageView.setClipToOutline(true);
        }
    }

    /**
     * Utility that will speed up decoding of large images, since we never need
     * them to be larger than the screen dimensions.
     */
    private static class Resizer implements ImageDecoder.OnHeaderDecodedListener {
        private final int maxSize;

        public Resizer(DisplayMetrics metrics) {
            this.maxSize = Math.max(metrics.widthPixels, metrics.heightPixels);
        }

        @Override
        public void onHeaderDecoded(ImageDecoder decoder, ImageInfo info, Source source) {
            // We requested a rough thumbnail size, but the remote size may have
            // returned something giant, so defensively scale down as needed.
            final int widthSample = info.getSize().getWidth() / maxSize;
            final int heightSample = info.getSize().getHeight() / maxSize;
            final int sample = Math.max(widthSample, heightSample);
            if (sample > 1) {
                decoder.setTargetSampleSize(sample);
            }
        }
    }
}
