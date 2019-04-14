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

import static com.android.providers.media.MediaProvider.TAG;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.android.internal.app.AlertActivity;

import java.io.IOException;

public class PermissionActivity extends AlertActivity implements DialogInterface.OnClickListener {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final CharSequence label;
        final int resId;
        try {
            label = getCallingLabel();
            resId = getMessageId();
        } catch (Exception e) {
            Log.w(TAG, e);
            finish();
            return;
        }

        final Resources res = getResources();
        final ImageView thumbnailView = new ImageView(this);
        thumbnailView.setScaleType(ScaleType.CENTER_INSIDE);
        thumbnailView.setPadding(
                0, res.getDimensionPixelSize(com.android.internal.R.dimen.default_gap), 0, 0);
        new AsyncTask<Void, Void, Thumbnail>() {
            @Override
            protected Thumbnail doInBackground(Void... params) {
                try {
                    return new Thumbnail(PermissionActivity.this, getIntent().getData());
                } catch (Exception e) {
                    Log.w(TAG, e);
                    finish();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Thumbnail result) {
                if (result == null) return;
                Log.d(TAG, "Found " + result.bitmap.getWidth() + "x" + result.bitmap.getHeight()
                        + " with description " + result.contentDescription);
                thumbnailView.setImageBitmap(result.bitmap);
                thumbnailView.setContentDescription(result.contentDescription);
            }
        }.execute();

        mAlertParams.mMessage = TextUtils.expandTemplate(getText(resId), label);
        mAlertParams.mPositiveButtonText = getString(R.string.grant_dialog_button_allow);
        mAlertParams.mPositiveButtonListener = this;
        mAlertParams.mNegativeButtonText = getString(R.string.grant_dialog_button_deny);
        mAlertParams.mNegativeButtonListener = this;
        mAlertParams.mCancelable = false;
        mAlertParams.mView = thumbnailView;
        setupAlert();

        getWindow().setCloseOnTouchOutside(false);
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        final Uri uri = getIntent().getData();
        switch (which) {
            case BUTTON_POSITIVE:
                Log.d(TAG, "User allowed grant for " + uri);
                grantUriPermission(getCallingPackage(), uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                setResult(Activity.RESULT_OK);
                finish();
                break;
            case BUTTON_NEGATIVE:
                Log.d(TAG, "User declined grant for " + uri);
                finish();
                break;
        }
    }

    private CharSequence getCallingLabel() throws NameNotFoundException {
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

    private int getMessageId() throws NameNotFoundException {
        final Uri uri = getIntent().getData();
        final String type = uri.getPathSegments().get(1);
        switch (type) {
            case "audio": return R.string.permission_audio;
            case "video": return R.string.permission_video;
            case "images": return R.string.permission_images;
        }
        throw new NameNotFoundException("Unknown media type " + uri);
    }

    private static class Thumbnail {
        public final Bitmap bitmap;
        public final CharSequence contentDescription;

        public Thumbnail(Context context, Uri uri) {
            final Resources res = context.getResources();
            final ContentResolver resolver = context.getContentResolver();

            final Size size = new Size(res.getDisplayMetrics().widthPixels,
                    res.getDisplayMetrics().widthPixels);
            try {
                bitmap = resolver.loadThumbnail(uri, size, null);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            try (Cursor c = resolver.query(uri,
                    new String[] { MediaColumns.DISPLAY_NAME }, null, null)) {
                if (c.moveToFirst()) {
                    contentDescription = c.getString(0);
                } else {
                    throw new IllegalStateException();
                }
            }
        }
    }
}
