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

package com.android.providers.media.photopicker;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.providers.media.MediaProvider.REDACTED_URI_ID_SIZE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import com.android.providers.media.photopicker.data.PickerResult;
import com.android.providers.media.photopicker.data.model.Item;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
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
     * Tests {@link PickerResult#getPickerResponseIntent(Context, Item)} to get PickerResult to
     * callingPackage.
     *
     * @throws Exception
     */
    @Test
    public void testGetResultSingle() throws Exception {
        Item item = null;
        try {
            item = createImageItem();
            final Intent intent = PickerResult.getPickerResponseIntent(mContext, item);

            final Uri result = intent.getData();
            assertRedactedUri(result);
            // TODO (b/189086247): Test with non-RES app
            assertReadAccess(result);
            assertNoWriteAccess(result);
        } finally {
            deleteFile(item);
        }
    }

    /**
     * Tests {@link PickerResult#getPickerResponseIntent(Context, List)} to get PickerResult to
     * callingPackage.
     *
     * @throws Exception
     */
    @Test
    public void testGetResultMultiple() throws Exception {
        ArrayList<Item> items = null;
        try {
            items = createItemSelection();
            final Intent intent = PickerResult.getPickerResponseIntent(mContext, items);

            final Uri result = intent.getData();
            assertRedactedUri(result);
            // TODO (b/189086247): Test with non-RES app
            assertReadAccess(result);
            assertNoWriteAccess(result);
        } finally {
            deleteFiles(items);
        }
    }

    private void assertRedactedUri(Uri uri) {
        final String uriId = uri.getLastPathSegment();
        assertThat(uriId.startsWith("RUID")).isTrue();
        assertThat(uriId.length()).isEqualTo(REDACTED_URI_ID_SIZE);
    }

    private void assertReadAccess(Uri uri) throws Exception {
        try (ParcelFileDescriptor pfd = mContext.getContentResolver().openFileDescriptor(uri,
                "r")) {
        }
    }

    private void assertNoWriteAccess(Uri uri) throws Exception {
        try (ParcelFileDescriptor pfd = mContext.getContentResolver().openFileDescriptor(uri,
                "w")) {
            fail("Expected write access to be blocked");
        } catch (Exception expected) {
        }
    }

    /**
     * Returns a PhotoSelection on which the test app does not have access to.
     */
    private ArrayList<Item> createItemSelection() throws Exception {
        ArrayList<Item> selectedItemList = new ArrayList<>();

        // TODO(b/189086247): Adding 1 image for now. Add multiple uris when multi select is
        // supported.
        selectedItemList.add(createImageItem());

        return selectedItemList;
    }

    /**
     * Returns a PhotoSelection item on which the test app does not have access to.
     */
    private Item createImageItem() throws Exception {
        // Create an image and revoke test app's access on it
        final Uri imageUri = assertCreateNewImage();
        clearMediaOwner(imageUri, mContext.getUserId());

        // Create an item for the selection, since PickerResult only uses Item#getContentUri(),
        // no need to create actual item, and can mock the class.
        final Item imageItem = mock(Item.class);
        when(imageItem.getContentUri()).thenReturn(imageUri);

        return imageItem;
    }

    private Uri assertCreateNewImage() throws Exception {
        return assertCreateNewFile(getDownloadsDir(), getImageFileName());
    }

    private Uri assertCreateNewFile(File dir, String fileName) throws Exception {
        final File file = new File(dir, fileName);
        assertThat(file.createNewFile()).isTrue();
        return MediaStore.scanFile(mContext.getContentResolver(), file);
    }

    private String getImageFileName() {
        // To help avoid flaky tests, give ourselves a unique nonce to be used for
        // all filesystem paths, so that we don't risk conflicting with previous
        // test runs.
        return String.format(IMAGE_FILE_NAME, System.nanoTime());
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

    private void deleteFiles(ArrayList<Item> items) {
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