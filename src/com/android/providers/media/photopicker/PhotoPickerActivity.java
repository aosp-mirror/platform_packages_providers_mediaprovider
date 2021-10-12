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

package com.android.providers.media.photopicker;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import com.android.providers.media.R;

import com.google.common.collect.ImmutableList;

/**
 * Photo Picker allows users to choose one or more photos and/or videos to share with an app. The
 * app does not get access to all photos/videos.
 */
public class PhotoPickerActivity extends Activity {

    public static final String TAG = "PhotoPickerActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // TODO(b/168001592) Change layout to show photos & options.
        setContentView(R.layout.photo_picker);
        Button button = findViewById(R.id.button);
        button.setOnClickListener(v -> respondEmpty());

        // TODO(b/168001592) Handle multiple selection option.

        // TODO(b/168001592) Filter using given mime type.

        // TODO(b/168001592) Show a photo grid instead of  ListView.
        ListView photosList = findViewById(R.id.names_list);
        ArrayAdapter<PhotoEntry> photosAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1);
        photosList.setAdapter(photosAdapter);
        // Clicking an item in the list returns its URI for now.
        photosList.setOnItemClickListener((parent, view, position, id) -> {
            respondPhoto(photosAdapter.getItem(position));
        });

        // Show the list of photo names for now.
        ImmutableList.Builder<PhotoEntry> imageRowsBuilder = ImmutableList.builder();
        String[] projection = new String[] {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME
        };
        // TODO(b/168001592) call query() from worker thread.
        Cursor cursor = getApplicationContext().getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null);
        int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
        int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
        // TODO(b/168001592) Use better image loading (e.g. use paging, glide).
        while (cursor.moveToNext()) {
            imageRowsBuilder.add(
                    new PhotoEntry(cursor.getLong(idColumn), cursor.getString(nameColumn)));
        }
        photosAdapter.addAll(imageRowsBuilder.build());
    }

    private void respondPhoto(PhotoEntry photoEntry) {
        Uri contentUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                photoEntry.id);

        Intent response = new Intent();
        // TODO(b/168001592) Confirm if this flag is enough to grant the access we want.
        response.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // TODO(b/168001592) Use a better label and accurate mime types.
        if (getIntent().getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)) {
            ClipDescription clipDescription = new ClipDescription(
                    "Photo Picker ClipData",
                    new String[]{"image/*", "video/*"});
            ClipData clipData = new ClipData(clipDescription, new ClipData.Item(contentUri));
            response.setClipData(clipData);
        } else {
            response.setData(contentUri);
        }

        setResult(Activity.RESULT_OK, response);
        finish();
    }


    private void respondEmpty() {
        setResult(Activity.RESULT_OK);
        finish();
    }

    private static class PhotoEntry {
        private long id;
        private String name;

        PhotoEntry(long id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
