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

import static android.provider.MediaStore.VOLUME_EXTERNAL;

import static com.android.providers.media.util.MimeUtils.isImageMimeType;
import static com.android.providers.media.util.MimeUtils.isVideoMimeType;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.providers.media.photopicker.data.LocalItemsProvider;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class LocalItemsProviderTest {

    /**
     * To help avoid flaky tests, give ourselves a unique nonce to be used for
     * all filesystem paths, so that we don't risk conflicting with previous
     * test runs.
     */
    private static final String NONCE = String.valueOf(System.nanoTime());
    private static final String TAG = "LocalItemsProviderTest";
    private static final String VIDEO_FILE_NAME = TAG + "_file_" + NONCE + ".mp4";
    private static final String IMAGE_FILE_NAME = TAG + "_file_" + NONCE + ".jpg";
    private static final String HIDDEN_DIR_NAME = TAG + "_hidden_dir_" + NONCE;

    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        // Wait for MediaStore to be Idle to reduce flakes caused by database updates
        MediaStore.waitForIdle(mContext.getContentResolver());
    }

    /**
     * Tests {@link LocalItemsProvider#getItems(String)} to return all images and videos.
     *
     * @throws Exception
     */
    @Test
    public void testGetItems() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider(mContext);
        Cursor res = localItemsProvider.getItems(null);
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file to test {@link LocalItemsProvider#getItems(String)}.
        // Both files should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            res = localItemsProvider.getItems(null);
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems + 2);

            assertThatOnlyImagesVideos(res);
            assertThatAllImagesVideos(res.getCount());
        } finally {
            imageFile.delete();
            videoFile.delete();
        }
    }

    /**
     * Tests {@link {@link LocalItemsProvider#getItems(String)}} does not return hidden
     * images/videos.
     *
     * @throws Exception
     */
    @Test
    public void testGetItems_nonMedia() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider(mContext);
        Cursor res = localItemsProvider.getItems(null);
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file in a hidden dir to test
        // {@link LocalItemsProvider#getItems(String)}. Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            res = localItemsProvider.getItems(null);
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems);
        } finally {
            imageFileHidden.delete();
            videoFileHidden.delete();
            hiddenDir.delete();
        }
    }

    /**
     * Tests {@link LocalItemsProvider#getItems(String)} to return all images and videos based on
     * the mimeType. Image mimeType should only return images.
     *
     * @throws Exception
     */
    @Test
    public void testGetItemsImages() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider(mContext);
        Cursor res = localItemsProvider.getItems("image/*");
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file to test {@link LocalItemsProvider#getItems(String)}.
        // Only 1 should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            res = localItemsProvider.getItems("image/*");
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems + 1);

            assertThatOnlyImages(res);
            assertThatAllImages(res.getCount());
        } finally {
            imageFile.delete();
            videoFile.delete();
        }
    }

    /**
     * Tests {@link LocalItemsProvider#getItems(String)} to return all images and videos based on
     * the mimeType. Image mimeType should only return images.
     *
     * @throws Exception
     */
    @Test
    public void testGetItemsImages_png() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider(mContext);
        Cursor res = localItemsProvider.getItems("image/png");
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create a jpg file image. Tests negative use case, this should not be returned below.
        File imageFile = assertCreateNewImage();
        try {
            res = localItemsProvider.getItems("image/png");
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link LocalItemsProvider#getItems(String)} does not return hidden images/videos.
     *
     * @throws Exception
     */
    @Test
    public void testGetItemsImages_nonMedia() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider(mContext);
        Cursor res = localItemsProvider.getItems("image/*");
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file in a hidden dir to test
        // {@link LocalItemsProvider#getItems(String)}. Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            res = localItemsProvider.getItems("image/*");
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems);
        } finally {
            imageFileHidden.delete();
            videoFileHidden.delete();
            hiddenDir.delete();
        }
    }

    /**
     * Tests {@link LocalItemsProvider#getItems(String)} to return all images and videos based on
     * the mimeType. Video mimeType should only return videos.
     *
     * @throws Exception
     */
    @Test
    public void testGetItemsVideos() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider(mContext);
        Cursor res = localItemsProvider.getItems("video/*");
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file to test {@link LocalItemsProvider#getItems(String)}.
        // Only 1 should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            res = localItemsProvider.getItems("video/*");
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems + 1);

            assertThatOnlyVideos(res);
            assertThatAllVideos(res.getCount());
        } finally {
            imageFile.delete();
            videoFile.delete();
        }
    }

    /**
     * Tests {@link LocalItemsProvider#getItems(String)} to return all images and videos based on
     * the mimeType. Image mimeType should only return images.
     *
     * @throws Exception
     */
    @Test
    public void testGetItemsVideos_mp4() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider(mContext);
        Cursor res = localItemsProvider.getItems("video/mp4");
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create a mp4 video file. Tests positive use case, this should be returned below.
        File videoFile = assertCreateNewVideo();
        try {
            res = localItemsProvider.getItems("video/mp4");
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems + 1);
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Tests {@link LocalItemsProvider#getItems(String)} does not return hidden images/videos.
     *
     * @throws Exception
     */
    @Test
    public void testGetItemsVideos_nonMedia() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider(mContext);
        Cursor res = localItemsProvider.getItems("video/*");
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file in a hidden dir to test the API.
        // Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            res = localItemsProvider.getItems("video/*");
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems);
        } finally {
            imageFileHidden.delete();
            videoFileHidden.delete();
            hiddenDir.delete();
        }
    }

    /**
     * Tests {@link LocalItemsProvider#getItems(String)} throws error for invalid param for
     * mimeType.
     *
     * @throws Exception
     */
    @Test
    public void testGetItemsInvalidParam() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider(mContext);
        try {
            localItemsProvider.getItems("audio/*");
            fail("Expected IllegalArgumentException for audio mimeType");
        } catch (IllegalArgumentException expected) {
            // Expected flow
        }
    }

    /**
     * Tests {@link LocalItemsProvider#getItems(String)} throws error for invalid param for
     * mimeType.
     *
     * @throws Exception
     */
    @Test
    public void testGetItemsAllMimeType() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider(mContext);
        try {
            localItemsProvider.getItems("*/*");
            fail("Expected IllegalArgumentException for audio mimeType");
        } catch (IllegalArgumentException expected) {
            // Expected flow
        }
    }

    private void assertThatOnlyImagesVideos(Cursor c) throws Exception {
        while (c.moveToNext()) {
            String mimeType = c.getString(2);
            assertThat(isImageMimeType(mimeType) || isVideoMimeType(mimeType)).isTrue();
        }
    }

    private void assertThatOnlyImages(Cursor c) throws Exception {
        while (c.moveToNext()) {
            String mimeType = c.getString(2);
            assertThat(isImageMimeType(mimeType)).isTrue();
        }
    }

    private void assertThatOnlyVideos(Cursor c) throws Exception {
        while (c.moveToNext()) {
            String mimeType = c.getString(2);
            assertThat(isVideoMimeType(mimeType)).isTrue();
        }
    }

    private void assertThatAllImagesVideos(int count) {
        int countOfImages = getCountOfMediaStoreImages();
        int countOfVideos = getCountOfMediaStoreVideos();
        assertThat(count).isEqualTo(countOfImages + countOfVideos);
    }

    private void assertThatAllImages(int count) {
        int countOfImages = getCountOfMediaStoreImages();
        assertThat(count).isEqualTo(countOfImages);
    }

    private void assertThatAllVideos(int count) {
        int countOfVideos = getCountOfMediaStoreVideos();
        assertThat(count).isEqualTo(countOfVideos);
    }

    private int getCountOfMediaStoreImages() {
        try (Cursor c = mContext.getContentResolver().query(
                MediaStore.Images.Media.getContentUri(VOLUME_EXTERNAL), null, null, null)) {
            assertThat(c.moveToFirst()).isTrue();
            return c.getCount();
        }
    }

    private int getCountOfMediaStoreVideos() {
        try (Cursor c = mContext.getContentResolver().query(
                MediaStore.Video.Media.getContentUri(VOLUME_EXTERNAL), null, null, null)) {
            assertThat(c.moveToFirst()).isTrue();
            return c.getCount();
        }
    }

    private File assertCreateNewVideo(File dir) throws Exception {
        return assertCreateNewFile(dir, VIDEO_FILE_NAME);
    }

    private File assertCreateNewImage(File dir) throws Exception {
        return assertCreateNewFile(dir, IMAGE_FILE_NAME);
    }

    private File assertCreateNewVideo() throws Exception {
        return assertCreateNewFile(getDownloadsDir(), VIDEO_FILE_NAME);
    }

    private File assertCreateNewImage() throws Exception {
        return assertCreateNewFile(getDownloadsDir(), IMAGE_FILE_NAME);
    }

    private File assertCreateNewFile(File dir, String fileName) throws Exception {
        final File file = new File(dir, fileName);
        assertThat(file.createNewFile()).isTrue();
        MediaStore.scanFile(mContext.getContentResolver(), file);
        return file;
    }

    private File getDownloadsDir() {
        return new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS);
    }

    private File createHiddenDir() throws Exception {
        File parentDir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(parentDir, HIDDEN_DIR_NAME);
        dir.mkdirs();
        File nomedia = new File(dir, ".nomedia");
        nomedia.createNewFile();
        MediaStore.scanFile(mContext.getContentResolver(), nomedia);

        return dir;
    }
}
