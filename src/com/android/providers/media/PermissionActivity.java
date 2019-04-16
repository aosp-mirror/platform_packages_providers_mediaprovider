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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.TextView;

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
        final FrameLayout view = new FrameLayout(this);
        final int padding = res.getDimensionPixelSize(com.android.internal.R.dimen.default_gap);
        view.setPadding(padding, padding, padding, padding);
        new AsyncTask<Void, Void, Description>() {
            @Override
            protected Description doInBackground(Void... params) {
                try {
                    return new Description(PermissionActivity.this, getIntent().getData());
                } catch (Exception e) {
                    Log.w(TAG, e);
                    finish();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Description result) {
                if (result == null) return;

                if (result.thumbnail != null) {
                    Log.d(TAG, "Found thumbnail " + result.thumbnail.getWidth() + "x"
                            + result.thumbnail.getHeight());

                    final ImageView child = new ImageView(PermissionActivity.this);
                    child.setScaleType(ScaleType.CENTER_INSIDE);
                    child.setImageBitmap(result.thumbnail);
                    child.setContentDescription(result.contentDescription);
                    view.addView(child);
                } else {
                    Log.d(TAG, "Found description " + result.contentDescription);

                    final TextView child = new TextView(PermissionActivity.this);
                    child.setText(result.contentDescription);
                    view.addView(child);
                }
            }
        }.execute();

        mAlertParams.mMessage = TextUtils.expandTemplate(getText(resId), label);
        mAlertParams.mPositiveButtonText = getString(R.string.grant_dialog_button_allow);
        mAlertParams.mPositiveButtonListener = this;
        mAlertParams.mNegativeButtonText = getString(R.string.grant_dialog_button_deny);
        mAlertParams.mNegativeButtonListener = this;
        mAlertParams.mCancelable = false;
        mAlertParams.mView = view;
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

    private static class Description {
        public Bitmap thumbnail;
        public CharSequence contentDescription;

        public Description(Context context, Uri uri) {
            final Resources res = context.getResources();
            final ContentResolver resolver = context.getContentResolver();

            final Size size = new Size(res.getDisplayMetrics().widthPixels,
                    res.getDisplayMetrics().widthPixels);
            try {
                thumbnail = resolver.loadThumbnail(uri, size, null);
            } catch (IOException e) {
                Log.w(TAG, e);
            }
            try (Cursor c = resolver.query(uri,
                    new String[] { MediaColumns.DISPLAY_NAME }, null, null)) {
                if (c.moveToFirst()) {
                    contentDescription = c.getString(0);
                }
            }

            if (TextUtils.isEmpty(contentDescription)) {
                throw new IllegalStateException();
            }
        }
    }
}
