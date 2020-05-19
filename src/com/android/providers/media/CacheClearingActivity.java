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
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.AsyncTask;
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
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.providers.media.util.FileUtils;

public class CacheClearingActivity extends Activity implements DialogInterface.OnClickListener {
    private static final String TAG = "CacheClearingActivity";
    private static final float MAX_APP_NAME_SIZE_PX = 500f;
    private static final float TEXT_SIZE = 42f;

    private AlertDialog mActionDialog;
    private Dialog mLoadingDialog;

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

        // If the label contains new line characters it may push the security
        // message below the fold of the dialog. Labels shouldn't have new line
        // characters anyways, so we just delete all of the newlines (if there are any).
        final String label = aInfo.loadSafeLabel(packageManager, MAX_APP_NAME_SIZE_PX,
                TextUtils.SAFE_STRING_FLAG_SINGLE_LINE).toString();

        createActionDialog(label);
        mActionDialog.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissDialogs(mActionDialog, mLoadingDialog);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        dismissDialogs(mActionDialog);

        if (which == AlertDialog.BUTTON_POSITIVE) {
            new CacheClearingTask().execute();
        } else {
            finish();
        }
    }

    private class CacheClearingTask extends AsyncTask<Void, Void, Integer> {
        @Override
        protected void onPreExecute() {
            dismissDialogs(mActionDialog);
            createLoadingDialog();
            mLoadingDialog.show();
        }

        @Override
        public Integer doInBackground(Void... unused) {
            return FileUtils.clearAppCacheDirectories();
        }

        @Override
        protected void onPostExecute(Integer result) {
            // We take the convention of not using primitive wrapper pretty seriously
            int status = result.intValue();
            dismissDialogs(mLoadingDialog);
            if (result == 0) {
                setResult(RESULT_OK);
            } else {
                setResult(status);
            }
            finish();
        }
    }

    private void createLoadingDialog() {
        final CharSequence dialogTitle = getString(R.string.cache_clearing_in_progress_title);
        final View dialogTitleView = View.inflate(this, R.layout.cache_clearing_dialog, null);
        final TextView titleText = dialogTitleView.findViewById(R.id.dialog_title);
        titleText.setText(dialogTitle);
        mLoadingDialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView)
                .setView(new ProgressBar(CacheClearingActivity.this))
                .setCancelable(false)
                .create();
        mLoadingDialog.create();
        setDialogOverlaySettings(mLoadingDialog);
    }

    private void createActionDialog(CharSequence appLabel) {
        final TextPaint paint = new TextPaint();
        paint.setTextSize(TEXT_SIZE);

        final String unsanitizedAppName = TextUtils.ellipsize(appLabel,
                paint, MAX_APP_NAME_SIZE_PX, TextUtils.TruncateAt.END).toString();
        final String appName = BidiFormatter.getInstance().unicodeWrap(unsanitizedAppName);

        final String actionText = getString(R.string.cache_clearing_dialog_text, appName);
        final SpannableString message = new SpannableString(actionText);

        int appNameIndex = actionText.indexOf(appName);
        if (appNameIndex >= 0) {
            message.setSpan(new StyleSpan(Typeface.BOLD),
                    appNameIndex, appNameIndex + appName.length(), 0);
        }

        final CharSequence dialogTitle = getString(R.string.cache_clearing_dialog_title);

        final View dialogTitleView = View.inflate(this, R.layout.cache_clearing_dialog, null);
        final TextView titleText = dialogTitleView.findViewById(R.id.dialog_title);
        titleText.setText(dialogTitle);
        mActionDialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView)
                .setMessage(message)
                .setPositiveButton(R.string.allow, this)
                .setNegativeButton(R.string.deny, this)
                .setCancelable(false)
                .create();

        mActionDialog.create();
        mActionDialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);

        setDialogOverlaySettings(mActionDialog);
    }

    private static void setDialogOverlaySettings(Dialog d) {
        final Window w = d.getWindow();
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        w.addSystemFlags(WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
    }

    private static void dismissDialogs(Dialog... dialogs) {
        for (Dialog d : dialogs) {
            if (d != null) {
                d.dismiss();
            }
        }
    }
}
