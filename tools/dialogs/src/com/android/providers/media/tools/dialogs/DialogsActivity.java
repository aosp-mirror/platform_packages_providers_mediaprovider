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

package com.android.providers.media.tools.dialogs;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.Activity;
import android.app.PendingIntent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DialogsActivity extends Activity {
    private LinearLayout mBody;
    private LinearLayout mSpinners;

    private NumberPicker mAudioCount;
    private NumberPicker mVideoCount;
    private NumberPicker mImageCount;

    private final ArrayList<Uri> mAudio = new ArrayList<>();
    private final ArrayList<Uri> mVideo = new ArrayList<>();
    private final ArrayList<Uri> mImage = new ArrayList<>();

    private final Supplier<Collection<Uri>> mRequestItems = () -> {
        final ArrayList<Uri> uris = new ArrayList<>();
        uris.addAll(mAudio.stream().limit(mAudioCount.getValue()).collect(Collectors.toList()));
        uris.addAll(mVideo.stream().limit(mVideoCount.getValue()).collect(Collectors.toList()));
        uris.addAll(mImage.stream().limit(mImageCount.getValue()).collect(Collectors.toList()));
        return uris;
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (checkSelfPermission(READ_EXTERNAL_STORAGE) != PERMISSION_GRANTED
                || checkSelfPermission(WRITE_EXTERNAL_STORAGE) != PERMISSION_GRANTED) {
            requestPermissions(new String[] { READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE }, 42);
            finish();
            return;
        }

        mBody = new LinearLayout(this);
        mBody.setOrientation(LinearLayout.VERTICAL);
        setContentView(mBody);

        final TextView header = new TextView(this);
        header.setText("Select the number of items to include in the request using the spinners "
                + "below; each spinnner in order selects the number audio, video, "
                + "and image items, respectively.");
        mBody.addView(header);

        mSpinners = new LinearLayout(this);
        mSpinners.setOrientation(LinearLayout.HORIZONTAL);
        mBody.addView(mSpinners);

        mAudioCount = addSpinner();
        mVideoCount = addSpinner();
        mImageCount = addSpinner();

        addAction("Request write", () -> {
            return MediaStore.createWriteRequest(getContentResolver(), mRequestItems.get());
        });
        addAction("Request trash", () -> {
            return MediaStore.createTrashRequest(getContentResolver(), mRequestItems.get(), true);
        });
        addAction("Request untrash", () -> {
            return MediaStore.createTrashRequest(getContentResolver(), mRequestItems.get(), false);
        });
        addAction("Request delete", () -> {
            return MediaStore.createDeleteRequest(getContentResolver(), mRequestItems.get());
        });

        new BackgroundTask().execute();
    }

    private class BackgroundTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            try (Cursor c = getContentResolver().query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    new String[] { FileColumns._ID, FileColumns.MEDIA_TYPE }, null, null, null)) {
                while (c.moveToNext()) {
                    final long id = c.getLong(0);
                    final int mediaType = c.getInt(1);
                    switch (mediaType) {
                        case FileColumns.MEDIA_TYPE_AUDIO:
                            mAudio.add(MediaStore.Audio.Media
                                    .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, id));
                            break;
                        case FileColumns.MEDIA_TYPE_VIDEO:
                            mVideo.add(MediaStore.Video.Media
                                    .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, id));
                            break;
                        case FileColumns.MEDIA_TYPE_IMAGE:
                            mImage.add(MediaStore.Images.Media
                                    .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, id));
                            break;
                    }
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void results) {
            mAudioCount.setMaxValue(mAudio.size());
            mVideoCount.setMaxValue(mVideo.size());
            mImageCount.setMaxValue(mImage.size());
        }
    }

    private NumberPicker addSpinner() {
        final NumberPicker spinner = new NumberPicker(this);
        spinner.setValue(0);
        spinner.setMaxValue(0);
        mSpinners.addView(spinner);
        return spinner;
    }

    private Button addAction(String title, Supplier<PendingIntent> supplier) {
        final Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener((v) -> {
            try {
                startIntentSenderForResult(supplier.get().getIntentSender(), 42, null, 0, 0, 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        mBody.addView(button);
        return button;
    }
}
