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

package com.android.providers.media.photopicker.data;

import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.model.Item;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PickerResultTest {
    private static final String TAG = "PickerResultTest";
    private static final String IMAGE_FILE_NAME = TAG + "_file_" + "%d" + ".jpg";

    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
    }

    /**
     * Tests {@link PickerResult#getPickerResponseIntent(boolean, List)} with single item
     * @throws Exception
     */
    @Test
    public void testGetResultSingle() throws Exception {
        List<Item> items = null;
        try {
            items = createItemSelection(1);
            final Uri expectedPickerUri = PickerResult.getPickerUri(items.get(0).getContentUri());
            final Intent intent = PickerResult.getPickerResponseIntent(
                    /* canSelectMultiple */ false, items);

            final Uri result = intent.getData();
            assertPickerUriFormat(result);
            assertThat(result).isEqualTo(expectedPickerUri);

            final ClipData clipData = intent.getClipData();
            assertThat(clipData).isNotNull();
            final int count = clipData.getItemCount();
            assertThat(count).isEqualTo(1);
            assertThat(clipData.getItemAt(0).getUri()).isEqualTo(expectedPickerUri);
        } finally {
            deleteFiles(items);
        }
    }

    /**
     * Tests {@link PickerResult#getPickerResponseIntent(boolean, List)} with multiple items
     * @throws Exception
     */
    @Test
    public void testGetResultMultiple() throws Exception {
        ArrayList<Item> items = null;
        try {
            final int itemCount = 3;
            items = createItemSelection(itemCount);
            List<Uri> expectedPickerUris = new ArrayList<>();
            for (Item item: items) {
                expectedPickerUris.add(PickerResult.getPickerUri(item.getContentUri()));
            }
            final Intent intent = PickerResult.getPickerResponseIntent(/* canSelectMultiple */ true,
                    items);

            final ClipData clipData = intent.getClipData();
            final int count = clipData.getItemCount();
            assertThat(count).isEqualTo(itemCount);
            for (int i = 0; i < count; i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                assertPickerUriFormat(uri);
                assertThat(uri).isEqualTo(expectedPickerUris.get(i));
            }
        } finally {
            deleteFiles(items);
        }
    }

    /**
     * Tests {@link PickerResult#getPickerResponseIntent(boolean, List)} when the user selected
     * only one item in multi-select mode
     * @throws Exception
     */
    @Test
    public void testGetResultMultiple_onlyOneItemSelected() throws Exception {
        ArrayList<Item> items = null;
        try {
            final int itemCount = 1;
            items = createItemSelection(itemCount);
            final Uri expectedPickerUri = PickerResult.getPickerUri(items.get(0).getContentUri());
            final Intent intent = PickerResult.getPickerResponseIntent(/* canSelectMultiple */ true,
                    items);

            final ClipData clipData = intent.getClipData();
            final int count = clipData.getItemCount();
            assertThat(count).isEqualTo(itemCount);
            assertPickerUriFormat(clipData.getItemAt(0).getUri());
            assertThat(clipData.getItemAt(0).getUri()).isEqualTo(expectedPickerUri);
        } finally {
            deleteFiles(items);
        }
    }

    private void assertPickerUriFormat(Uri uri) {
        final String pickerUriPrefix = PickerUriResolver.PICKER_URI.toString();
        assertThat(uri.toString().startsWith(pickerUriPrefix)).isTrue();
    }

    /**
     * Returns a PhotoSelection on which the test app does not have access to.
     */
    private ArrayList<Item> createItemSelection(int count) throws Exception {
        ArrayList<Item> selectedItemList = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            selectedItemList.add(createImageItem());
        }
        return selectedItemList;
    }

    /**
     * Returns a PhotoSelection item on which the test app does not have access to.
     */
    private Item createImageItem() throws Exception {
        // Create an image and revoke test app's access on it
        Uri imageUri = assertCreateNewImage();
        clearMediaOwner(imageUri, mContext.getUserId());
        // Create with a picker URI with picker db enabled
        imageUri = PickerUriResolver
                .getMediaUri(PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY)
                .buildUpon()
                .appendPath(String.valueOf(ContentUris.parseId(imageUri)))
                .build();

        return new Item(imageUri.getLastPathSegment(), "image/jpeg", /* dateTaken */ 0,
                /* generationModified */ 0, /* duration */ 0, imageUri, _SPECIAL_FORMAT_NONE);
    }

    private Uri assertCreateNewImage() throws Exception {
        return assertCreateNewFile(getDownloadsDir(), getImageFileName());
    }

    private Uri assertCreateNewFile(File dir, String fileName) throws Exception {
        final File file = new File(dir, fileName);
        assertThat(file.createNewFile()).isTrue();

        // Write 1 byte because 0byte files are not valid in the picker db
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(1);
        }

        final Uri uri = MediaStore.scanFile(mContext.getContentResolver(), file);
        MediaStore.waitForIdle(mContext.getContentResolver());
        return uri;
    }

    private String getImageFileName() {
        // To help avoid flaky tests, give ourselves a unique nonce to be used for
        // all filesystem paths, so that we don't risk conflicting with previous
        // test runs.
        return IMAGE_FILE_NAME + System.nanoTime() + ".jpeg";
    }

    private File getDownloadsDir() {
        return new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS);
    }

    private static void clearMediaOwner(Uri uri, int userId) throws IOException {
        final String cmd = String.format(
                "content update --uri %s --user %d --bind owner_package_name:n:",
                uri, userId);
        runShellCommand(InstrumentationRegistry.getInstrumentation(), cmd);
    }

    private void deleteFiles(List<Item> items) {
        if (items == null) return;

        for (Item item : items) {
            deleteFile(item);
        }
    }

    private void deleteFile(Item item) {
        if (item == null) return;

        final String cmd = String.format("content delete --uri %s --user %d ",
                item.getContentUri(), mContext.getUserId());
        try {
            runShellCommand(InstrumentationRegistry.getInstrumentation(), cmd);
        } catch (Exception e) {
            // Ignore the exception but log it to help debug test failures
            Log.d(TAG, "Couldn't delete file " + item.getContentUri(), e);
        }
    }
}
