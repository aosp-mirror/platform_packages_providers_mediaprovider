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

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;

import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class ItemsProviderTest {

    /**
     * To help avoid flaky tests, give ourselves a unique nonce to be used for
     * all filesystem paths, so that we don't risk conflicting with previous
     * test runs.
     */
    private static final String NONCE = String.valueOf(System.nanoTime());
    private static final String TAG = "ItemsProviderTest";
    private static final String VIDEO_FILE_NAME = TAG + "_file_" + NONCE + ".mp4";
    private static final String IMAGE_FILE_NAME = TAG + "_file_" + NONCE + ".jpg";
    private static final String HIDDEN_DIR_NAME = TAG + "_hidden_dir_" + NONCE;

    private static Context sIsolatedContext;
    private static ContentResolver sIsolatedResolver;
    private static ItemsProvider sItemsProvider;

    @Before
    public void setUp() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.READ_DEVICE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS);

        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        sIsolatedResolver = sIsolatedContext.getContentResolver();
        sItemsProvider = new ItemsProvider(sIsolatedContext);

        // Wait for MediaStore to be Idle to reduce flakes caused by database updates
        MediaStore.waitForIdle(sIsolatedResolver);
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_CAMERA}.
     */
    @Test
    public void testGetCategories_camera() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Camera dir to test
        // {@link ItemsProvider#getCategories(UserId)}.
        final File cameraDir = getCameraDir();
        File imageFile = assertCreateNewImage(cameraDir);
        try {
            assertGetCategoriesMatchSingle(Category.CATEGORY_CAMERA, /* numberOfItems */ 1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_CAMERA}.
     */
    @Test
    public void testGetCategories_not_camera() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // negative test case: image file which should not be returned in Camera category
        final File picturesDir = getPicturesDir();
        File nonCameraImageFile = assertCreateNewImage(picturesDir);
        try {
            assertGetCategoriesMatchSingle(Category.CATEGORY_CAMERA, /* numberOfItems */ 0);
        } finally {
            nonCameraImageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_VIDEOS}.
     */
    @Test
    public void testGetCategories_videos() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 video file in Movies dir to test
        // {@link ItemsProvider#getCategories(UserId)}.
        final File moviesDir = getMoviesDir();
        File videoFile = assertCreateNewVideo(moviesDir);
        try {
           assertGetCategoriesMatchSingle(Category.CATEGORY_VIDEOS, /* numberOfItems */ 1);
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_VIDEOS}.
     */
    @Test
    public void testGetCategories_not_videos() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // negative test case: image file which should not be returned in Videos category
        final File picturesDir = getPicturesDir();
        File imageFile = assertCreateNewImage(picturesDir);
        try {
            assertGetCategoriesMatchSingle(Category.CATEGORY_VIDEOS, /* numberOfItems */ 0);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_SCREENSHOTS}.
     */
    @Test
    public void testGetCategories_screenshots() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Screenshots dir to test
        // {@link ItemsProvider#getCategories(UserId)}
        final File screenshotsDir = getScreenshotsDir();
        File imageFile = assertCreateNewImage(screenshotsDir);
        try {
            assertGetCategoriesMatchSingle(Category.CATEGORY_SCREENSHOTS, /* numberOfItems */ 1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_SCREENSHOTS}.
     */
    @Test
    public void testGetCategories_not_screenshots() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // negative test case: image file which should not be returned in Screenshots category
        final File cameraDir = getCameraDir();
        File imageFile = assertCreateNewImage(cameraDir);
        try {
            assertGetCategoriesMatchSingle(Category.CATEGORY_SCREENSHOTS, /* numberOfItems */ 0);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_FAVORITES}.
     */
    @Test
    public void testGetCategories_favorites() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // positive test case: image file which should be returned in favorites category
        final File picturesDir = getPicturesDir();
        final File imageFile = assertCreateNewImage(picturesDir);
        setIsFavorite(imageFile);
        try {
            assertGetCategoriesMatchSingle(Category.CATEGORY_FAVORITES, /* numberOfItems */1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_FAVORITES}.
     */
    @Test
    public void testGetCategories_not_favorites() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // negative test case: image file which should not be returned in favorites category
        final File picturesDir = getPicturesDir();
        final File nonFavImageFile = assertCreateNewImage(picturesDir);
        try {
            assertGetCategoriesMatchSingle(Category.CATEGORY_FAVORITES, /* numberOfItems */ 0);
        } finally {
            nonFavImageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_DOWNLOADS}.
     */
    @Test
    public void testGetCategories_downloads() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Downloads dir to test {@link ItemsProvider#getCategories(UserId)}.
        final File downloadsDir = getDownloadsDir();
        final File imageFile = assertCreateNewImage(downloadsDir);
        try {
            assertGetCategoriesMatchSingle(Category.CATEGORY_DOWNLOADS, /* numberOfItems */ 1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_DOWNLOADS}.
     */
    @Test
    public void testGetCategories_not_downloads() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // negative test case: image file which should not be returned in Downloads category
        final File picturesDir = getPicturesDir();
        final File nonDownloadsImageFile = assertCreateNewImage(picturesDir);
        try {
            assertGetCategoriesMatchSingle(Category.CATEGORY_DOWNLOADS, /* numberOfItems */ 0);
        } finally {
            nonDownloadsImageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_CAMERA} and {@link Category#CATEGORY_VIDEOS}.
     */
    @Test
    public void testGetCategories_camera_and_videos() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 video file in Camera dir to test
        // {@link ItemsProvider#getCategories(UserId)}.
        final File cameraDir = getCameraDir();
        File videoFile = assertCreateNewVideo(cameraDir);
        try {
            assertGetCategoriesMatchMultiple(Category.CATEGORY_CAMERA, Category.CATEGORY_VIDEOS,
                    /* numberOfItemsInCamera */ 1,
                    /* numberOfItemsInVideos */ 1);
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_SCREENSHOTS} and {@link Category#CATEGORY_FAVORITES}.
     */
    @Test
    public void testGetCategories_screenshots_and_favorites() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Screenshots dir to test
        // {@link ItemsProvider#getCategories(UserId)}
        final File screenshotsDir = getScreenshotsDir();
        File imageFile = assertCreateNewImage(screenshotsDir);
        setIsFavorite(imageFile);
        try {
            assertGetCategoriesMatchMultiple(Category.CATEGORY_SCREENSHOTS,
                    Category.CATEGORY_FAVORITES,
                    /* numberOfItemsInScreenshots */ 1,
                    /* numberOfItemsInFavorites */ 1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(UserId)} to return correct info about
     * {@link Category#CATEGORY_DOWNLOADS} and {@link Category#CATEGORY_FAVORITES}.
     */
    @Test
    public void testGetCategories_downloads_and_favorites() throws Exception {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Screenshots dir to test
        // {@link ItemsProvider#getCategories(UserId)}
        final File downloadsDir = getDownloadsDir();
        File imageFile = assertCreateNewImage(downloadsDir);
        setIsFavorite(imageFile);
        try {
            assertGetCategoriesMatchMultiple(Category.CATEGORY_DOWNLOADS,
                    Category.CATEGORY_FAVORITES,
                    /* numberOfItemsInScreenshots */ 1,
                    /* numberOfItemsInFavorites */ 1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getItems(String, int, int, String, UserId)} to return all
     * images and videos.
     */
    @Test
    public void testGetItems() throws Exception {
        Cursor res = sItemsProvider.getItems(/* category */ null, /* offset */ 0,
                /* limit */ -1, /* mimeType */ null, /* userId */ null);
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file to test
        // {@link ItemsProvider#getItems(String, int, int, String, UserId)}.
        // Both files should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            res = sItemsProvider.getItems(/* category */ null, /* offset */ 0, /* limit */ -1,
                    /* mimeType */ null, /* userId */ null);
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
     * Tests {@link {@link ItemsProvider#getItems(String, int, int, String, UserId)}} does not
     * return hidden images/videos.
     */
    @Test
    public void testGetItems_nonMedia() throws Exception {
        Cursor res = sItemsProvider.getItems(/* category */ null, /* offset */ 0,
                /* limit */ -1, /* mimeType */ null, /* userId */ null);
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file in a hidden dir to test
        // {@link ItemsProvider#getItems(String, int, int, String, UserId)}.
        // Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            res = sItemsProvider.getItems(/* category */ null, /* offset */ 0, /* limit */ -1,
                    /* mimeType */ null, /* userId */ null);
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
     * Tests {@link ItemsProvider#getItems(String, int, int, String, UserId)} to return all
     * images and videos based on the mimeType. Image mimeType should only return images.
     */
    @Test
    public void testGetItemsImages() throws Exception {
        Cursor res = sItemsProvider.getItems(/* category */ null, /* offset */ 0,
                /* limit */ -1, /* mimeType */ "image/*", /* userId */ null);
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file to test
        // {@link ItemsProvider#getItems(String, int, int, String, UserId)}.
        // Only 1 should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            res = sItemsProvider.getItems(/* category */ null, /* offset */ 0, /* limit */ -1,
                    /* mimeType */ "image/*", /* userId */ null);
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
     * Tests {@link ItemsProvider#getItems(String, int, int, String, UserId)} to return all
     * images and videos based on the mimeType. Image mimeType should only return images.
     */
    @Test
    public void testGetItemsImages_png() throws Exception {
        Cursor res = sItemsProvider.getItems(/* category */ null, /* offset */ 0,
                /* limit */ -1, /* mimeType */ "image/png", /* userId */ null);
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create a jpg file image. Tests negative use case, this should not be returned below.
        File imageFile = assertCreateNewImage();
        try {
            res = sItemsProvider.getItems(/* category */ null, /* offset */ 0, /* limit */ -1,
                    /* mimeType */ "image/png", /* userId */ null);
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getItems(String, int, int, String, UserId)} does not return
     * hidden images/videos.
     */
    @Test
    public void testGetItemsImages_nonMedia() throws Exception {
        Cursor res = sItemsProvider.getItems(/* category */ null, /* offset */ 0,
                /* limit */ -1, /* mimeType */ "image/*", /* userId */ null);
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file in a hidden dir to test
        // {@link ItemsProvider#getItems(String, int, int, String)}.
        // Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            res = sItemsProvider.getItems(/* category */ null, /* offset */ 0, /* limit */ -1,
                    /* mimeType */ "image/*", /* userId */ null);
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
     * Tests {@link ItemsProvider#getItems(String, int, int, String, UserId)} to return all
     * images and videos based on the mimeType. Video mimeType should only return videos.
     */
    @Test
    public void testGetItemsVideos() throws Exception {
        Cursor res = sItemsProvider.getItems(/* category */ null, /* offset */ 0,
                /* limit */ -1,  /* mimeType */ "video/*", /* userId */ null);
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file to test
        // {@link ItemsProvider#getItems(String, int, int, String)}.
        // Only 1 should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            res = sItemsProvider.getItems(/* category */ null, /* offset */ 0, /* limit */ -1,
                    /* mimeType */ "video/*", /* userId */ null);
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
     * Tests {@link ItemsProvider#getItems(String, int, int, String, UserId)} to return all
     * images and videos based on the mimeType. Image mimeType should only return images.
     */
    @Test
    public void testGetItemsVideos_mp4() throws Exception {
        Cursor res = sItemsProvider.getItems(/* category */ null, /* offset */ 0,
                /* limit */ -1, /* mimeType */ "video/mp4", /* userId */ null);
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create a mp4 video file. Tests positive use case, this should be returned below.
        File videoFile = assertCreateNewVideo();
        try {
            res = sItemsProvider.getItems(/* category */ null, /* offset */ 0, /* limit */ -1,
                    /* mimeType */ "video/mp4", /* userId */ null);
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems + 1);
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getItems(String, int, int, String, UserId)} does not return
     * hidden images/videos.
     */
    @Test
    public void testGetItemsVideos_nonMedia() throws Exception {
        Cursor res = sItemsProvider.getItems(/* category */ null, /* offset */ 0,
                /* limit */ -1, /* mimeType */ "video/*", /* userId */ null);
        assertThat(res).isNotNull();
        final int initialCountOfItems = res.getCount();

        // Create 1 image and 1 video file in a hidden dir to test the API.
        // Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            res = sItemsProvider.getItems(/* category */ null, /* offset */ 0, /* limit */ -1,
                    /* mimeType */ "video/*", /* userId */ null);
            assertThat(res).isNotNull();
            final int laterCountOfItems = res.getCount();

            assertThat(laterCountOfItems).isEqualTo(initialCountOfItems);
        } finally {
            imageFileHidden.delete();
            videoFileHidden.delete();
            hiddenDir.delete();
        }
    }

    private void assertGetCategoriesMatchSingle(String expectedCategoryName,
            int expectedNumberOfItems) {
        if (expectedNumberOfItems == 0) {
            assertCategoriesNoMatch(expectedCategoryName);
            return;
        }

        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c).isNotNull();
        assertThat(c.getCount()).isEqualTo(1);

        // Assert that only expected category is returned and has expectedNumberOfItems items in it
        assertThat(c.moveToFirst()).isTrue();
        final int nameColumnIndex = c.getColumnIndexOrThrow(Category.CategoryColumns.NAME);
        final int numOfItemsColumnIndex = c.getColumnIndexOrThrow(
                Category.CategoryColumns.NUMBER_OF_ITEMS);

        final String categoryName = c.getString(nameColumnIndex);
        final int numOfItems = c.getInt(numOfItemsColumnIndex);

        assertThat(categoryName).isEqualTo(expectedCategoryName);
        assertThat(numOfItems).isEqualTo(expectedNumberOfItems);
    }

    private void assertCategoriesNoMatch(String expectedCategoryName) {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        while (c != null && c.moveToNext()) {
            final int nameColumnIndex = c.getColumnIndexOrThrow(Category.CategoryColumns.NAME);
            final String categoryName = c.getString(nameColumnIndex);
            assertThat(categoryName).isNotEqualTo(expectedCategoryName);
        }
    }

    private void assertGetCategoriesMatchMultiple(String category1, String category2,
            int numberOfItems1, int numberOfItems2) {
        Cursor c = sItemsProvider.getCategories(/* userId */ null);
        assertThat(c).isNotNull();
        assertThat(c.getCount()).isEqualTo(2);

        // Assert that category1 and category2 is returned and has numberOfItems1 and
        // numberOfItems2 items in them respectively.
        boolean isCategory1Returned = false;
        boolean isCategory2Returned = false;
        while (c.moveToNext()) {
            final int nameColumnIndex = c.getColumnIndexOrThrow(Category.CategoryColumns.NAME);
            final int numOfItemsColumnIndex = c.getColumnIndexOrThrow(
                    Category.CategoryColumns.NUMBER_OF_ITEMS);

            final String categoryName = c.getString(nameColumnIndex);
            final int numOfItems = c.getInt(numOfItemsColumnIndex);


            if (categoryName.equals(category1)) {
                isCategory1Returned = true;
                assertThat(numOfItems).isEqualTo(numberOfItems1);
            } else if (categoryName.equals(category2)) {
                isCategory2Returned = true;
                assertThat(numOfItems).isEqualTo(numberOfItems2);
            }
        }

        assertThat(isCategory1Returned).isTrue();
        assertThat(isCategory2Returned).isTrue();
    }

    private void setIsFavorite(File file) {
        final Uri uri = MediaStore.scanFile(sIsolatedResolver, file);
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_FAVORITE, 1);
        // Assert that 1 row corresponding to this file is updated.
        assertThat(sIsolatedResolver.update(uri, values, null)).isEqualTo(1);
        // Wait for MediaStore to be Idle to reduce flakes caused by database updates
        MediaStore.waitForIdle(sIsolatedResolver);
    }

    private void assertThatOnlyImagesVideos(Cursor c) throws Exception {
        while (c.moveToNext()) {
            int mimeTypeColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            String mimeType = c.getString(mimeTypeColumn);
            assertThat(isImageMimeType(mimeType) || isVideoMimeType(mimeType)).isTrue();
        }
    }

    private void assertThatOnlyImages(Cursor c) throws Exception {
        while (c.moveToNext()) {
            int mimeTypeColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            String mimeType = c.getString(mimeTypeColumn);
            assertThat(isImageMimeType(mimeType)).isTrue();
        }
    }

    private void assertThatOnlyVideos(Cursor c) throws Exception {
        while (c.moveToNext()) {
            int mimeTypeColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            String mimeType = c.getString(mimeTypeColumn);
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
        try (Cursor c = sIsolatedResolver.query(
                MediaStore.Images.Media.getContentUri(VOLUME_EXTERNAL), null, null, null)) {
            assertThat(c.moveToFirst()).isTrue();
            return c.getCount();
        }
    }

    private int getCountOfMediaStoreVideos() {
        try (Cursor c = sIsolatedResolver.query(
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
        if (!dir.exists()) {
            dir.mkdirs();
        }
        assertThat(dir.exists()).isTrue();
        final File file = new File(dir, fileName);
        assertThat(file.createNewFile()).isTrue();

        MediaStore.scanFile(sIsolatedResolver, file);
        return file;
    }

    private File getDownloadsDir() {
        return new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS);
    }

    private File getDcimDir() {
        return new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DCIM);
    }

    private File getPicturesDir() {
        return new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_PICTURES);
    }

    private File getMoviesDir() {
        return new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_MOVIES);
    }

    private File getCameraDir() {
        return new File(getDcimDir(), "Camera");
    }

    private File getScreenshotsDir() {
        return new File(getPicturesDir(), Environment.DIRECTORY_SCREENSHOTS);
    }

    private File createHiddenDir() throws Exception {
        File parentDir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(parentDir, HIDDEN_DIR_NAME);
        dir.mkdirs();
        File nomedia = new File(dir, ".nomedia");
        nomedia.createNewFile();

        MediaStore.scanFile(sIsolatedResolver, nomedia);

        return dir;
    }
}
