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

package com.android.providers.media.tools.photopicker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.google.android.material.snackbar.Snackbar;

public class PhotoPickerToolActivity extends Activity {

    private static final String TAG = "PhotoPickerToolActivity";
    private static final String EXTRA_PICK_IMAGES_MAX = "android.provider.extra.PICK_IMAGES_MAX";
    private static final String ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES";
    private static final int PICK_IMAGES_MAX_LIMIT = 100;
    private static final int REQUEST_CODE = 42;

    private int mMaxCount = 10;
    private boolean mIsShowImageOnly;
    private boolean mIsShowVideoOnly;
    private boolean mSetMimeType;
    private ScrollView mScrollView;

    private CheckBox mSetImageOnlyCheckBox;
    private CheckBox mSetVideoOnlyCheckBox;
    private CheckBox mSetMimeTypeCheckBox;
    private CheckBox mSetSelectionCountCheckBox;
    private CheckBox mAllowMultipleCheckBox;
    private CheckBox mGetContentCheckBox;
    private EditText mMaxCountText;
    private EditText mMimeTypeText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAllowMultipleCheckBox = findViewById(R.id.cbx_allow_multiple);
        mGetContentCheckBox = findViewById(R.id.cbx_get_content);
        mSetImageOnlyCheckBox = findViewById(R.id.cbx_set_image_only);
        mSetMimeTypeCheckBox = findViewById(R.id.cbx_set_mime_type);
        mSetSelectionCountCheckBox = findViewById(R.id.cbx_set_selection_count);
        mSetVideoOnlyCheckBox = findViewById(R.id.cbx_set_video_only);
        mMaxCountText = findViewById(R.id.edittext_max_count);
        mMimeTypeText = findViewById(R.id.edittext_mime_type);
        mScrollView = findViewById(R.id.scrollview);

        mSetImageOnlyCheckBox.setOnCheckedChangeListener(this::onShowImageOnlyCheckedChanged);
        mSetVideoOnlyCheckBox.setOnCheckedChangeListener(this::onShowVideoOnlyCheckedChanged);
        mSetMimeTypeCheckBox.setOnCheckedChangeListener(this::onSetMimeTypeCheckedChanged);
        mSetSelectionCountCheckBox.setOnCheckedChangeListener(
                this::onSetSelectionCountCheckedChanged);

        mMaxCountText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                try {
                    mMaxCount = Integer.parseInt(mMaxCountText.getText().toString().trim());
                } catch (NumberFormatException ex) {
                    // The input is not an integer type, set the mMaxCount to -1.
                    mMaxCount = -1;
                    final String wrongFormatWarning =
                            "The count format is wrong! Please input correct number!";
                    Snackbar.make(mMaxCountText, wrongFormatWarning, Snackbar.LENGTH_LONG).show();
                }
            }
        });

        final Button launchButton = findViewById(R.id.launch_button);
        launchButton.setOnClickListener(this::onLaunchButtonClicked);
    }

    private void onShowImageOnlyCheckedChanged(View view, boolean isChecked) {
        if (mIsShowImageOnly == isChecked) {
            return;
        }

        mIsShowImageOnly = isChecked;
        if (isChecked) {
            mSetVideoOnlyCheckBox.setChecked(false);
            mSetMimeTypeCheckBox.setChecked(false);
        }
    }

    private void onShowVideoOnlyCheckedChanged(View view, boolean isChecked) {
        if (mIsShowVideoOnly == isChecked) {
            return;
        }

        mIsShowVideoOnly = isChecked;
        if (isChecked) {
            mSetImageOnlyCheckBox.setChecked(false);
            mSetMimeTypeCheckBox.setChecked(false);
        }
    }

    private void onSetMimeTypeCheckedChanged(View view, boolean isChecked) {
        if (mSetMimeType == isChecked) {
            return;
        }

        mSetMimeType = isChecked;
        if (isChecked) {
            mSetImageOnlyCheckBox.setChecked(false);
            mSetVideoOnlyCheckBox.setChecked(false);
        }
        mMimeTypeText.setEnabled(isChecked);
    }

    private void onSetSelectionCountCheckedChanged(View view, boolean isChecked) {
        mMaxCountText.setEnabled(isChecked);
    }

    private void onLaunchButtonClicked(View view) {
        final Intent intent;
        if (mGetContentCheckBox.isChecked()) {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
        } else {
            intent = new Intent(ACTION_PICK_IMAGES);
        }

        if (mAllowMultipleCheckBox.isChecked()) {
            if (mGetContentCheckBox.isChecked()) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            } else {
                intent.putExtra(EXTRA_PICK_IMAGES_MAX, PICK_IMAGES_MAX_LIMIT);
            }
        }

        if (mSetImageOnlyCheckBox.isChecked()) {
            intent.setType("image/*");
        } else if (mSetVideoOnlyCheckBox.isChecked()) {
            intent.setType("video/*");
        } else if (mSetMimeTypeCheckBox.isChecked()) {
            final String mimeType = mMimeTypeText.getText().toString().trim();
            intent.setType(mimeType);
        }

        if (mSetSelectionCountCheckBox.isChecked()) {
            intent.putExtra(EXTRA_PICK_IMAGES_MAX, mMaxCount);
        }

        try {
            startActivityForResult(intent, REQUEST_CODE);
        } catch (ActivityNotFoundException ex){
            final String errorMessage =
                    "No Activity found to handle Intent with type \"" + intent.getType() + "\"";
            logErrorAndShowToast(errorMessage);
        }
    }

    private void logErrorAndShowToast(String errorMessage) {
        Log.e(TAG, errorMessage);
        Snackbar.make(mScrollView, errorMessage, Snackbar.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_CANCELED) {
            Log.e(TAG, "The result code is canceled");
            return;
        };

        if (requestCode != REQUEST_CODE) {
            logErrorAndShowToast("The request code is not as we expected");
            return;
        }

        if (data == null) {
            logErrorAndShowToast("The result intent is null");
            return;
        }

        final Uri uri = data.getData();
        if (uri == null && data.getClipData() == null) {
            logErrorAndShowToast("The uri and clipData of result intent is null");
            return;
        }

        final LinearLayout itemContainer = findViewById(R.id.item_container);
        final int itemSize = (int) (300 * getResources().getDisplayMetrics().density);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(itemSize, itemSize);
        params.gravity = Gravity.CENTER;
        itemContainer.removeAllViews();
        if (uri != null) {
            itemContainer.addView(generateText(uri.toString()));
            itemContainer.addView(generateItems(uri, params));
        } else {
            final ClipData clipData = data.getClipData();
            final int count = clipData.getItemCount();
            for (int i = 0; i < count; i++) {
                Uri item = (Uri) clipData.getItemAt(i).getUri();
                itemContainer.addView(generateText("" + i + ". " + item.toString()));
                itemContainer.addView(generateItems(item, params));
            }
            // scroll to first item
            mScrollView.smoothScrollTo(0, 0);
        }
    }

    private TextView generateText(String text) {
        final TextView textView = new TextView(this);
        textView.setTextAppearance(R.style.HeaderTitle);
        textView.setText(text);
        return textView;
    }

    private ImageView generateImageView(Uri uri, LinearLayout.LayoutParams params) {
        final ImageView image = new ImageView(this);
        image.setLayoutParams(params);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Glide.with(this)
                .load(uri)
                .thumbnail()
                .into(image);
        return image;
    }

    private VideoView generateVideoView(Uri uri, LinearLayout.LayoutParams params) {
        final VideoView video = new VideoView(this);
        video.setLayoutParams(params);
        video.setVideoURI(uri);
        video.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            mp.seekTo(0);
            mp.start();
        });
        return video;
    }

    private View generateItems(Uri uri, LinearLayout.LayoutParams params) {
        String mimeType = null;
        // TODO: after getType issue is fixed, change to use getType
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{MediaStore.Files.FileColumns.MIME_TYPE}, null, null, null, null)) {
            cursor.moveToFirst();
            mimeType = cursor.getString(0);
        }

        if (isVideoMimeType(mimeType)) {
            return generateVideoView(uri, params);
        } else {
            return generateImageView(uri, params);
        }
    }

    private static boolean isVideoMimeType(@Nullable String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return startsWithIgnoreCase(mimeType, "video/");
    }

    /**
     * Variant of {@link String#startsWith(String)} but which tests with case-insensitivity.
     */
    private static boolean startsWithIgnoreCase(@Nullable String target, @Nullable String other) {
        if (target == null || other == null) {
            return false;
        }
        if (other.length() >= target.length()) {
            return false;
        }
        return target.regionMatches(true, 0, other, 0, other.length());
    }
}
