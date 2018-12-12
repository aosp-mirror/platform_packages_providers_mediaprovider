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
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.internal.app.AlertActivity;

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

        // TODO: track down a thumbnail for this item, instead of giant thing
        final ImageView thumbnailView = new ImageView(this);
        thumbnailView.setImageURIAsync(getIntent().getData());

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
        switch (which) {
            case BUTTON_POSITIVE:
                grantUriPermission(getCallingPackage(), getIntent().getData(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                setResult(Activity.RESULT_OK);
                finish();
                break;
            case BUTTON_NEGATIVE:
                Log.d(TAG, "NEG");
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
}
