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
import android.content.res.Configuration;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.photopicker.EmbeddedPhotoPickerClient;
import android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo;
import android.widget.photopicker.EmbeddedPhotoPickerProvider;
import android.widget.photopicker.EmbeddedPhotoPickerProviderFactory;
import android.widget.photopicker.EmbeddedPhotoPickerSession;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.snackbar.Snackbar;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class PhotoPickerToolActivity extends Activity {

    private static final String TAG = "PhotoPickerToolActivity";
    private static final String EXTRA_PICK_IMAGES_MAX = "android.provider.extra.PICK_IMAGES_MAX";
    public static final String EXTRA_PICK_IMAGES_ACCENT_COLOR =
            "android.provider.extra.PICK_IMAGES_ACCENT_COLOR";

    private static final String ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES";
    private static final String EXTRA_PICK_IMAGES_LAUNCH_TAB =
            "android.provider.extra.PICK_IMAGES_LAUNCH_TAB";
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
    private CheckBox mEmbeddedPhotoPickerCheckBox;

    private CheckBox mOrderedSelectionCheckBox;

    private CheckBox mPickerLaunchTabCheckBox;
    private CheckBox mPickerAccentColorCheckBox;
    private CheckBox mEmbeddedThemeNightModeCheckBox;

    private EditText mMaxCountText;
    private EditText mMimeTypeText;
    private EditText mAccentColorText;

    private RadioButton mAlbumsRadioButton;
    private RadioButton mPhotosRadioButton;
    private RadioButton mSystemThemeButton;
    private RadioButton mLightThemeButton;
    private RadioButton mNightThemeButton;
    private EmbeddedPhotoPickerProvider mEmbeddedPickerProvider;
    private SurfaceView mSurfaceView;
    private EmbeddedPhotoPickerSession mSession = null;
    private BottomSheetBehavior<View> mBottomSheetBehavior;

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
        mOrderedSelectionCheckBox = findViewById(R.id.cbx_ordered_selection);
        mEmbeddedPhotoPickerCheckBox = findViewById(R.id.cbx_embedded_photopicker);
        mEmbeddedThemeNightModeCheckBox = findViewById(R.id.cbx_set_theme_night_mode);
        mMaxCountText = findViewById(R.id.edittext_max_count);
        mMimeTypeText = findViewById(R.id.edittext_mime_type);
        mScrollView = findViewById(R.id.scrollview);
        mPickerLaunchTabCheckBox = findViewById(R.id.cbx_set_picker_launch_tab);
        mAlbumsRadioButton = findViewById(R.id.rb_albums);
        mPhotosRadioButton = findViewById(R.id.rb_photos);
        mSystemThemeButton = findViewById(R.id.rb_system);
        mLightThemeButton = findViewById(R.id.rb_light);
        mNightThemeButton = findViewById(R.id.rb_night);
        mPickerAccentColorCheckBox = findViewById(R.id.cbx_set_accent_color);
        mAccentColorText = findViewById(R.id.edittext_accent_color);
        mSetImageOnlyCheckBox.setOnCheckedChangeListener(this::onShowImageOnlyCheckedChanged);
        mSetVideoOnlyCheckBox.setOnCheckedChangeListener(this::onShowVideoOnlyCheckedChanged);
        mSetMimeTypeCheckBox.setOnCheckedChangeListener(this::onSetMimeTypeCheckedChanged);
        mSetSelectionCountCheckBox.setOnCheckedChangeListener(
                this::onSetSelectionCountCheckedChanged);
        mPickerLaunchTabCheckBox.setOnCheckedChangeListener(
                this::onSetPickerLaunchTabCheckedChanged);
        mPickerAccentColorCheckBox.setOnCheckedChangeListener(
                this::onSetPickerAccentColorCheckedChanged);
        mEmbeddedThemeNightModeCheckBox.setOnCheckedChangeListener(
                this::onSetEmbeddedThemeCheckedChanged);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            enableEmbeddedPhotoPickerSupport();
        }
        final Button launchButton = findViewById(R.id.launch_button);
        launchButton.setOnClickListener(this::onLaunchButtonClicked);
    }


    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    /** Enable checkbox and initialise bottom sheet to support Embedded PhotoPicker */
    private void enableEmbeddedPhotoPickerSupport() {
        mEmbeddedPhotoPickerCheckBox.setVisibility(View.VISIBLE);
        // Prepare Bottom Sheet
        View bottomSheet = findViewById(R.id.bottomSheet);
        mBottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
        BottomSheetBehavior.BottomSheetCallback bottomSheetCallback =
                new BottomSheetBehavior.BottomSheetCallback() {
                    @Override
                    public void onStateChanged(@NonNull View bottomSheet, int newState) {
                        // notify current opened session about current bottom sheet state
                        if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                            if (mSession != null) {
                                mSession.notifyPhotoPickerExpanded(true);
                            }
                        } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                            if (mSession != null) {
                                mSession.notifyPhotoPickerExpanded(false);
                            }
                        }
                    }

                    @Override
                    public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                        // Optional: Handle sliding behavior here
                    }
                };
        mBottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback);
        // Initially hide the BottomSheet
        mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);


        mEmbeddedPickerProvider =
                EmbeddedPhotoPickerProviderFactory.create(getApplicationContext());
        mSurfaceView = findViewById(R.id.surface);
        mSurfaceView.setZOrderOnTop(true);
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

    private void onSetPickerLaunchTabCheckedChanged(View view, boolean isChecked) {
        mAlbumsRadioButton.setEnabled(isChecked);
        mPhotosRadioButton.setEnabled(isChecked);
    }

    private void onSetPickerAccentColorCheckedChanged(View view, boolean isChecked) {
        mAccentColorText.setEnabled(isChecked);
    }

    private void onSetEmbeddedThemeCheckedChanged(View view, boolean isChecked) {
        mSystemThemeButton.setEnabled(isChecked);
        mLightThemeButton.setEnabled(isChecked);
        mNightThemeButton.setEnabled(isChecked);
    }

    /** Implements {@link EmbeddedPhotoPickerClient} necessary methods to respond
     * the notifications sent by the service */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private class ClientCallback implements EmbeddedPhotoPickerClient {
        @Override
        public void onSessionOpened(EmbeddedPhotoPickerSession session) {
            mSession = session;
            // Initially bottom sheet should be open in collapsed state
            if (mBottomSheetBehavior != null) {
                mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
            mSurfaceView.setChildSurfacePackage(session.getSurfacePackage());
            Log.d(TAG, "Embedded PhotoPicker session opened successfully");
        }

        @Override
        public void onSessionError(@NonNull Throwable cause) {
            mSession = null;
            Log.e(TAG, "Error occurred in Embedded PhotoPicker session", cause);
        }

        @Override
        public void onUriPermissionGranted(@NonNull List<Uri> uris) {
            Log.d(TAG, "Uri permission granted for: " + uris);
        }

        @Override
        public void onUriPermissionRevoked(@NonNull List<Uri> uris) {
            Log.d(TAG, "Uri permission revoked for: " + uris);
        }

        @Override
        public void onSelectionComplete() {
            mSession.close();
            Log.d(TAG, "User is done with their selection in Embedded PhotoPicker");
        }
    }

    private void onLaunchButtonClicked(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && mEmbeddedPhotoPickerCheckBox.isChecked()) {
            launchEmbeddedPhotoPicker();
            return;
        }
        final Intent intent;
        if (mGetContentCheckBox.isChecked()) {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
        } else {
            intent = new Intent(ACTION_PICK_IMAGES);

            // This extra is not permitted in GET_CONTENT
            if (mPickerLaunchTabCheckBox.isChecked()) {
                int launchTab;
                if (mAlbumsRadioButton.isChecked()) {
                    launchTab = 0;
                } else {
                    launchTab = 1;
                }
                intent.putExtra(EXTRA_PICK_IMAGES_LAUNCH_TAB, launchTab);
            }

            if (mPickerAccentColorCheckBox.isChecked()) {
                long accentColor = Long.decode(mAccentColorText.getText().toString());
                intent.putExtra(EXTRA_PICK_IMAGES_ACCENT_COLOR, accentColor);
            }
        }

        if (mAllowMultipleCheckBox.isChecked()) {
            if (mGetContentCheckBox.isChecked()) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            } else {
                intent.putExtra(EXTRA_PICK_IMAGES_MAX, PICK_IMAGES_MAX_LIMIT);
                // ordered selection is not allowed in get content.
                if (mOrderedSelectionCheckBox.isChecked()) {
                    intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, true);
                }

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
            try {
                mMaxCount = Integer.parseInt(mMaxCountText.getText().toString().trim());
            } catch (NumberFormatException ex) {
                // The input is not an integer type, set the mMaxCount to -1.
                mMaxCount = -1;
                logErrorAndShowToast("The count format is wrong! Please input"
                        + " correct number!");
            }
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

    /** Set {@link EmbeddedPhotoPickerFeatureInfo} attributes and launch Embedded PhotoPicker */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void launchEmbeddedPhotoPicker() {
        EmbeddedPhotoPickerFeatureInfo.Builder embeddedPhotoPickerFeatureInfoBuilder =
                new EmbeddedPhotoPickerFeatureInfo.Builder();

        int displayId = getSystemService(DisplayManager.class).getDisplays()[0].getDisplayId();

        // Set feature info attributes in EmbeddedPhotoPickerFeatureInfo builder
        // TODO(b/365914283) Enable pre selected Uri feature

        // Set mime types
        List<String> mimeTypes = null;
        if (mSetImageOnlyCheckBox.isChecked()) {
            mimeTypes = List.of("image/*");
        } else if (mSetVideoOnlyCheckBox.isChecked()) {
            mimeTypes = List.of("video/*");
        } else if (mSetMimeTypeCheckBox.isChecked()) {
            final String inputText = mMimeTypeText.getText().toString();
            mimeTypes = Arrays.stream(inputText.split(","))
                    .map(String::trim)
                    .collect(Collectors.toList());
        }
        if (mimeTypes != null) {
            try {
                embeddedPhotoPickerFeatureInfoBuilder.setMimeTypes(mimeTypes);
            } catch (NullPointerException | IllegalArgumentException e) {
                logErrorAndShowToast(e.getMessage());
                return;
            }
        }

        // Set Embedded Picker Accent color
        if (mPickerAccentColorCheckBox.isChecked()) {
            try {
                long accentColor = Long.decode(mAccentColorText.getText().toString());
                embeddedPhotoPickerFeatureInfoBuilder.setAccentColor(accentColor);
            } catch (NumberFormatException e) {
                logErrorAndShowToast("Invalid accent color format");
                return;
            }
        }

        // Set Embedded Picker Theme
        if (mEmbeddedThemeNightModeCheckBox.isChecked()) {
            if (mSystemThemeButton.isChecked()) {
                embeddedPhotoPickerFeatureInfoBuilder.setThemeNightMode(
                        Configuration.UI_MODE_NIGHT_UNDEFINED);
            } else if (mLightThemeButton.isChecked()) {
                embeddedPhotoPickerFeatureInfoBuilder.setThemeNightMode(
                        Configuration.UI_MODE_NIGHT_NO);
            } else if (mNightThemeButton.isChecked()) {
                embeddedPhotoPickerFeatureInfoBuilder.setThemeNightMode(
                        Configuration.UI_MODE_NIGHT_YES);
            }
        }

        // Set if Ordered selection enabled in Embedded PhotoPicker
        if (mOrderedSelectionCheckBox.isChecked()) {
            embeddedPhotoPickerFeatureInfoBuilder.setOrderedSelection(true);
        }

        // Set selection limit in Embedded PhotoPicker
        if (mSetSelectionCountCheckBox.isChecked()) {
            try {
                mMaxCount = Integer.parseInt(mMaxCountText.getText().toString().trim());
                embeddedPhotoPickerFeatureInfoBuilder.setMaxSelectionLimit(mMaxCount);
            } catch (NumberFormatException ex) {
                logErrorAndShowToast("The count format is wrong!"
                        + " Please input correct number!");
                return;
            } catch (IllegalArgumentException e) {
                logErrorAndShowToast(e.getMessage());
                return;
            }
        }

        // open a new embedded PhotoPicker session
        mEmbeddedPickerProvider.openSession(
                mSurfaceView.getHostToken(),
                displayId, mSurfaceView.getWidth(),
                mSurfaceView.getHeight(),
                embeddedPhotoPickerFeatureInfoBuilder.build(),
                Executors.newSingleThreadExecutor(),
                new ClientCallback());
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
