/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.BidiFormatter;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.providers.media.util.BackgroundThread;

public class CacheClearingActivity extends Activity implements DialogInterface.OnClickListener {
    private static final String TAG = "CacheClearingActivity";
    private static final float MAX_APP_NAME_SIZE_PX = 500f;
    private static final float TEXT_SIZE = 42f;

    private AlertDialog mDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String packageName = getCallingPackage();
        setResult(RESULT_CANCELED);

        if (packageName == null) {
            finish();
            return;
        }

        final PackageManager packageManager = getPackageManager();
        final ApplicationInfo aInfo;
        try {
            aInfo = packageManager.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "unable to look up package name", e);
            finish();
            return;
        }

        if (!MediaProvider.hasPermissionToClearCaches(this, aInfo)) {
            Log.i(TAG, "Calling package " + packageName + " has no permission clear app caches");
            finish();
            return;
        }

        final TextPaint paint = new TextPaint();
        paint.setTextSize(TEXT_SIZE);

        // If the label contains new line characters it may push the security
        // message below the fold of the dialog. Labels shouldn't have new line
        // characters anyways, so we just delete all of the newlines (if there are any).
        final String label = aInfo.loadSafeLabel(packageManager, MAX_APP_NAME_SIZE_PX,
                TextUtils.SAFE_STRING_FLAG_SINGLE_LINE).toString();

        final String unsanitizedAppName = TextUtils.ellipsize(label,
                paint, MAX_APP_NAME_SIZE_PX, TextUtils.TruncateAt.END).toString();
        final String appName = BidiFormatter.getInstance().unicodeWrap(unsanitizedAppName);

        final String actionText = getString(R.string.cache_clearing_dialog_text, appName);
        final SpannableString message = new SpannableString(actionText);

        int appNameIndex = actionText.indexOf(appName);
        if (appNameIndex >= 0) {
            message.setSpan(new StyleSpan(Typeface.BOLD),
                    appNameIndex, appNameIndex + appName.length(), 0);
        }
        final CharSequence dialogTitle = getString(R.string.cache_clearing_dialog_title, appName);

        final View dialogTitleView = View.inflate(this, R.layout.cache_clearing_dialog, null);
        final TextView titleText = dialogTitleView.findViewById(R.id.dialog_title);
        titleText.setText(dialogTitle);

        mDialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView)
                .setMessage(message)
                .setPositiveButton(R.string.allow, this)
                .setNegativeButton(R.string.deny, this)
                .create();

        mDialog.create();
        mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);

        final Window w = mDialog.getWindow();
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        w.addSystemFlags(WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        mDialog.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDialog != null) {
            mDialog.dismiss();
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        try {
            if (which == AlertDialog.BUTTON_POSITIVE) {
                BackgroundThread.getExecutor().execute(this::clearAppCache);
                setResult(RESULT_OK);
            }
        } finally {
            if (mDialog != null) {
                mDialog.dismiss();
            }
            finish();
        }
    }

    private void clearAppCache() {
        MediaProvider.clearAppCacheDirectories();
    }
}
