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

import static android.provider.CloudMediaProviderContract.AlbumColumns;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS;
import static android.provider.CloudMediaProviderContract.MediaColumns;
import static android.provider.MediaStore.VOLUME_EXTERNAL;

import static com.android.providers.media.util.MimeUtils.isImageMimeType;
import static com.android.providers.media.util.MimeUtils.isVideoMimeType;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.Manifest;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;

import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    private ContentResolver mIsolatedResolver;
    private ItemsProvider mItemsProvider;

    @Before
    public void setUp() {
        final UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation();

        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.READ_DEVICE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS);

        // Remove sync delay to avoid flaky tests
        final String setSyncDelayCommand =
                "device_config put storage pickerdb.default_sync_delay_ms 0";
        uiAutomation.executeShellCommand(setSyncDelayCommand);

        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext
                = new IsolatedContext(context, "databases", /*asFuseThread*/ false);
        mIsolatedResolver = isolatedContext.getContentResolver();
        mItemsProvider = new ItemsProvider(isolatedContext);

        // Wait for MediaStore to be Idle to reduce flakes caused by database updates
        MediaStore.waitForIdle(mIsolatedResolver);
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link #ALBUM_ID_CAMERA}.
     */
    @Test
    public void testGetCategories_camera() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Camera dir to test
        // {@link ItemsProvider#getCategories(String, UserId)}.
        final File cameraDir = getCameraDir();
        File imageFile = assertCreateNewImage(cameraDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_CAMERA, /* numberOfItems */ 1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link #ALBUM_ID_CAMERA}.
     */
    @Test
    public void testGetCategories_not_camera() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // negative test case: image file which should not be returned in Camera category
        final File picturesDir = getPicturesDir();
        File nonCameraImageFile = assertCreateNewImage(picturesDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_CAMERA, /* numberOfItems */ 0);
        } finally {
            nonCameraImageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link #ALBUM_ID_VIDEOS}.
     */
    @Test
    public void testGetCategories_videos() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 video file in Movies dir to test
        // {@link ItemsProvider#getCategories(String, UserId)}.
        final File moviesDir = getMoviesDir();
        File videoFile = assertCreateNewVideo(moviesDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_VIDEOS, /* numberOfItems */ 1);
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link #ALBUM_ID_VIDEOS}.
     */
    @Test
    public void testGetCategories_not_videos() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // negative test case: image file which should not be returned in Videos category
        final File picturesDir = getPicturesDir();
        File imageFile = assertCreateNewImage(picturesDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_VIDEOS, /* numberOfItems */ 0);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link #ALBUM_ID_SCREENSHOTS}.
     */
    @Test
    public void testGetCategories_screenshots() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Screenshots dir to test
        // {@link ItemsProvider#getCategories(String, UserId)}
        final File screenshotsDir = getScreenshotsDir();
        File imageFile = assertCreateNewImage(screenshotsDir);
        // Create 1 image file in Screenshots dir of Downloads dir
        // {@link ItemsProvider#getCategories(String, UserId)}
        final File screenshotsDirInDownloadsDir = getScreenshotsDirFromDownloadsDir();
        File imageFileInScreenshotDirInDownloads =
                assertCreateNewImage(screenshotsDirInDownloadsDir);
        try {
            assertGetCategoriesMatchMultiple(ALBUM_ID_SCREENSHOTS,
                    ALBUM_ID_DOWNLOADS, /* numberOfItemsInScreenshots */ 2,
                                             /* numberOfItemsInDownloads */ 1);
        } finally {
            imageFile.delete();
            imageFileInScreenshotDirInDownloads.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link #ALBUM_ID_SCREENSHOTS}.
     */
    @Test
    public void testGetCategories_not_screenshots() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // negative test case: image file which should not be returned in Screenshots category
        final File cameraDir = getCameraDir();
        File imageFile = assertCreateNewImage(cameraDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_SCREENSHOTS, /* numberOfItems */ 0);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link AlbumColumns#ALBUM_ID_FAVORITES}.
     */
    @Test
    public void testGetCategories_favorites() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // positive test case: image file which should be returned in favorites category
        final File picturesDir = getPicturesDir();
        final File imageFile = assertCreateNewImage(picturesDir);
        setIsFavorite(imageFile);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_FAVORITES, /* numberOfItems */1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link AlbumColumns#ALBUM_ID_FAVORITES}.
     */
    @Test
    public void testGetCategories_not_favorites() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // negative test case: image file which should not be returned in favorites category
        final File picturesDir = getPicturesDir();
        final File nonFavImageFile = assertCreateNewImage(picturesDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_FAVORITES, /* numberOfItems */ 0);
        } finally {
            nonFavImageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link #ALBUM_ID_DOWNLOADS}.
     */
    @Test
    public void testGetCategories_downloads() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Downloads dir to test
        // {@link ItemsProvider#getCategories(String, UserId)}.
        final File downloadsDir = getDownloadsDir();
        final File imageFile = assertCreateNewImage(downloadsDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_DOWNLOADS, /* numberOfItems */ 1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link #ALBUM_ID_DOWNLOADS}.
     */
    @Test
    public void testGetCategories_not_downloads() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // negative test case: image file which should not be returned in Downloads category
        final File picturesDir = getPicturesDir();
        final File nonDownloadsImageFile = assertCreateNewImage(picturesDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_DOWNLOADS, /* numberOfItems */ 0);
        } finally {
            nonDownloadsImageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link #ALBUM_ID_CAMERA} and {@link #ALBUM_ID_VIDEOS}.
     */
    @Test
    public void testGetCategories_camera_and_videos() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 video file in Camera dir to test
        // {@link ItemsProvider#getCategories(String, UserId)}.
        final File cameraDir = getCameraDir();
        File videoFile = assertCreateNewVideo(cameraDir);
        try {
            assertGetCategoriesMatchMultiple(ALBUM_ID_CAMERA, ALBUM_ID_VIDEOS,
                    /* numberOfItemsInCamera */ 1,
                    /* numberOfItemsInVideos */ 1);
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link AlbumColumns#ALBUM_ID_SCREENSHOTS} and {@link AlbumColumns#ALBUM_ID_FAVORITES}.
     */
    @Test
    public void testGetCategories_screenshots_and_favorites() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Screenshots dir to test
        // {@link ItemsProvider#getCategories(String, UserId)}
        final File screenshotsDir = getScreenshotsDir();
        File imageFile = assertCreateNewImage(screenshotsDir);
        setIsFavorite(imageFile);
        try {
            assertGetCategoriesMatchMultiple(ALBUM_ID_SCREENSHOTS,
                    ALBUM_ID_FAVORITES,
                    /* numberOfItemsInScreenshots */ 1,
                    /* numberOfItemsInFavorites */ 1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getCategories(String, UserId)} to return correct info about
     * {@link AlbumColumns#ALBUM_ID_DOWNLOADS} and {@link AlbumColumns#ALBUM_ID_FAVORITES}.
     */
    @Test
    public void testGetCategories_downloads_and_favorites() throws Exception {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Screenshots dir to test
        // {@link ItemsProvider#getCategories(String, UserId)}
        final File downloadsDir = getDownloadsDir();
        File imageFile = assertCreateNewImage(downloadsDir);
        setIsFavorite(imageFile);
        try {
            assertGetCategoriesMatchMultiple(ALBUM_ID_DOWNLOADS,
                    ALBUM_ID_FAVORITES,
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
        // Create 1 image and 1 video file to test
        // {@link ItemsProvider#getItems(String, int, int, String, UserId)}.
        // Both files should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            final Cursor res = mItemsProvider.getItems(Category.DEFAULT, /* offset */ 0,
                    /* limit */ -1, /* mimeType */ null, /* userId */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(2);

            assertThatOnlyImagesVideos(res);
            // Reset the cursor back. Cursor#moveToPosition(-1) will reset the position to -1,
            // but since there is no such valid cursor position, it returns false.
            assertThat(res.moveToPosition(-1)).isFalse();
            assertThatAllImagesVideos(res.getCount());
        } finally {
            imageFile.delete();
            videoFile.delete();
        }
    }

    @Test
    public void testGetItems_sortOrder() throws Exception {
        try {
            final long timeNow = System.nanoTime() / 1000;
            final Uri imageFileDateNowPlus1Uri = prepareFileAndGetUri(
                    new File(getDownloadsDir(),  "latest_" + IMAGE_FILE_NAME), timeNow + 1000);
            final Uri imageFileDateNowUri
                    = prepareFileAndGetUri(new File(getDcimDir(), IMAGE_FILE_NAME), timeNow);
            final Uri videoFileDateNowUri
                    = prepareFileAndGetUri(new File(getCameraDir(), VIDEO_FILE_NAME), timeNow);

            // This is the list of uris based on the expected sort order of items returned by
            // ItemsProvider#getItems
            List<Uri> uris = new ArrayList<>();
            // This is the latest image file
            uris.add(imageFileDateNowPlus1Uri);
            // Video file was scanned after image file, hence has higher _id than image file
            uris.add(videoFileDateNowUri);
            uris.add(imageFileDateNowUri);

            try (Cursor cursor = mItemsProvider.getItems(Category.DEFAULT, /* offset */ 0,
                    /* limit */ -1, /* mimeType */ null, /* userId */ null)) {
                assertThat(cursor).isNotNull();

                final int expectedCount = uris.size();
                assertThat(cursor.getCount()).isEqualTo(expectedCount);

                int rowNum = 0;
                assertThat(cursor.moveToFirst()).isTrue();
                final int idColumnIndex = cursor.getColumnIndexOrThrow(MediaColumns.ID);
                while (rowNum < expectedCount) {
                    assertWithMessage("id at row:" + rowNum + " is expected to be"
                            + " same as id in " + uris.get(rowNum))
                            .that(String.valueOf(cursor.getLong(idColumnIndex)))
                            .isEqualTo(uris.get(rowNum).getLastPathSegment());
                    cursor.moveToNext();
                    rowNum++;
                }
            }
        } finally {
            deleteAllFilesNoThrow();
        }
    }

    /**
     * Tests {@link {@link ItemsProvider#getItems(String, int, int, String, UserId)}} does not
     * return hidden images/videos.
     */
    @Test
    public void testGetItems_nonMedia() throws Exception {
        // Create 1 image and 1 video file in a hidden dir to test
        // {@link ItemsProvider#getItems(String, int, int, String, UserId)}.
        // Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            final Cursor res = mItemsProvider.getItems(Category.DEFAULT, /* offset */ 0,
                    /* limit */ -1, /* mimeType */ null, /* userId */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(0);
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
        // Create 1 image and 1 video file to test
        // {@link ItemsProvider#getItems(String, int, int, String, UserId)}.
        // Only 1 should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            final Cursor res = mItemsProvider.getItems(Category.DEFAULT, /* offset */ 0,
                    /* limit */ -1, /* mimeType */ new String[]{ "image/*"}, /* userId */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(1);

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
        // Create a jpg file image. Tests negative use case, this should not be returned below.
        File imageFile = assertCreateNewImage();
        try {
            final Cursor res = mItemsProvider.getItems(Category.DEFAULT, /* offset */ 0,
                    /* limit */ -1, /* mimeType */ new String[]{"image/png"}, /* userId */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(0);
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
        // Create 1 image and 1 video file in a hidden dir to test
        // {@link ItemsProvider#getItems(String, int, int, String)}.
        // Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            final Cursor res = mItemsProvider.getItems(Category.DEFAULT, /* offset */ 0,
                    /* limit */ -1, /* mimeType */ new String[]{"image/*"}, /* userId */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(0);
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
        // Create 1 image and 1 video file to test
        // {@link ItemsProvider#getItems(String, int, int, String)}.
        // Only 1 should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            final Cursor res = mItemsProvider.getItems(Category.DEFAULT, /* offset */ 0,
                    /* limit */ -1, /* mimeType */ new String[]{"video/*"}, /* userId */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(1);

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
        // Create a mp4 video file. Tests positive use case, this should be returned below.
        File videoFile = assertCreateNewVideo();
        try {
            final Cursor res = mItemsProvider.getItems(Category.DEFAULT, /* offset */ 0,
                    /* limit */ -1, /* mimeType */ new String[]{"video/mp4"}, /* userId */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(1);
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
        // Create 1 image and 1 video file in a hidden dir to test the API.
        // Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            final Cursor res = mItemsProvider.getItems(Category.DEFAULT, /* offset */ 0,
                    /* limit */ -1, /* mimeType */ new String[]{"video/*"}, /* userId */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(0);
        } finally {
            imageFileHidden.delete();
            videoFileHidden.delete();
            hiddenDir.delete();
        }
    }

    private void assertGetCategoriesMatchSingle(String expectedCategoryName,
            int expectedNumberOfItems) throws Exception {
        if (expectedNumberOfItems == 0) {
            assertCategoriesNoMatch(expectedCategoryName);
            return;
        }

        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c).isNotNull();
        assertThat(c.getCount()).isEqualTo(1);

        // Assert that only expected category is returned and has expectedNumberOfItems items in it
        assertThat(c.moveToFirst()).isTrue();
        final int nameColumnIndex = c.getColumnIndexOrThrow(AlbumColumns.DISPLAY_NAME);
        final int numOfItemsColumnIndex = c.getColumnIndexOrThrow(AlbumColumns.MEDIA_COUNT);
        final int coverIdIndex = c.getColumnIndexOrThrow(AlbumColumns.MEDIA_COVER_ID);

        final String categoryName = c.getString(nameColumnIndex);
        final int numOfItems = c.getInt(numOfItemsColumnIndex);
        final Uri coverUri = ItemsProvider.getItemsUri(c.getString(coverIdIndex),
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY, UserId.CURRENT_USER);

        assertThat(categoryName).isEqualTo(expectedCategoryName);
        assertThat(numOfItems).isEqualTo(expectedNumberOfItems);
        assertCategoryUriIsValid(coverUri);
    }

    private void assertCategoryUriIsValid(Uri uri) throws Exception {
        try (AssetFileDescriptor fd1 = mIsolatedResolver.openTypedAssetFile(uri, "image/*",
                null, null)) {
            assertThat(fd1).isNotNull();
        }
        try (ParcelFileDescriptor fd2 = mIsolatedResolver.openFileDescriptor(uri, "r")) {
            assertThat(fd2).isNotNull();
        }
    }

    private void assertCategoriesNoMatch(String expectedCategoryName) {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        while (c != null && c.moveToNext()) {
            final int nameColumnIndex = c.getColumnIndexOrThrow(AlbumColumns.DISPLAY_NAME);
            final String categoryName = c.getString(nameColumnIndex);
            assertThat(categoryName).isNotEqualTo(expectedCategoryName);
        }
    }

    private void assertGetCategoriesMatchMultiple(String category1, String category2,
            int numberOfItems1, int numberOfItems2) {
        Cursor c = mItemsProvider.getCategories(/* mimeType */ null, /* userId */ null);
        assertThat(c).isNotNull();
        assertThat(c.getCount()).isEqualTo(2);

        // Assert that category1 and category2 is returned and has numberOfItems1 and
        // numberOfItems2 items in them respectively.
        boolean isCategory1Returned = false;
        boolean isCategory2Returned = false;
        while (c.moveToNext()) {
            final int nameColumnIndex = c.getColumnIndexOrThrow(AlbumColumns.DISPLAY_NAME);
            final int numOfItemsColumnIndex = c.getColumnIndexOrThrow(
                    AlbumColumns.MEDIA_COUNT);

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
        final Uri uri = MediaStore.scanFile(mIsolatedResolver, file);
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_FAVORITE, 1);
        // Assert that 1 row corresponding to this file is updated.
        assertThat(mIsolatedResolver.update(uri, values, null)).isEqualTo(1);
        // Wait for MediaStore to be Idle to reduce flakes caused by database updates
        MediaStore.waitForIdle(mIsolatedResolver);
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
        try (Cursor c = mIsolatedResolver.query(
                MediaStore.Images.Media.getContentUri(VOLUME_EXTERNAL), null, null, null)) {
            assertThat(c.moveToFirst()).isTrue();
            return c.getCount();
        }
    }

    private int getCountOfMediaStoreVideos() {
        try (Cursor c = mIsolatedResolver.query(
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

    private File assertCreateNewFile(File parentDir, String fileName) throws Exception {
        final File file = new File(parentDir, fileName);
        prepareFileAndGetUri(file, /* lastModifiedTime */ -1);

        return file;
    }

    private Uri prepareFileAndGetUri(File file, long lastModifiedTime) throws IOException {
        ensureParentExists(file.getParentFile());

        assertThat(file.createNewFile()).isTrue();

        // Write 1 byte because 0byte files are not valid in the picker db
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(1);
        }

        if (lastModifiedTime != -1) {
            file.setLastModified(lastModifiedTime);
        }

        final Uri uri = MediaStore.scanFile(mIsolatedResolver, file);
        assertWithMessage("Uri obtained by scanning file " + file)
                .that(uri).isNotNull();
        // Wait for picker db sync
        MediaStore.waitForIdle(mIsolatedResolver);

        return uri;
    }

    private void ensureParentExists(File parent) {
        if (!parent.exists()) {
            parent.mkdirs();
        }
        assertThat(parent.exists()).isTrue();
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

    private File getScreenshotsDirFromDownloadsDir() {
        return new File(getDownloadsDir(), Environment.DIRECTORY_SCREENSHOTS);
    }

    private File createHiddenDir() throws Exception {
        File parentDir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(parentDir, HIDDEN_DIR_NAME);
        dir.mkdirs();
        File nomedia = new File(dir, ".nomedia");
        nomedia.createNewFile();

        MediaStore.scanFile(mIsolatedResolver, nomedia);

        return dir;
    }

    private void deleteAllFilesNoThrow() {
        try (Cursor c = mIsolatedResolver.query(
                MediaStore.Files.getContentUri(VOLUME_EXTERNAL),
                new String[] {MediaStore.MediaColumns.DATA}, null, null)) {
            while(c.moveToNext()) {
                (new File(c.getString(
                        c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)))).delete();
            }
        }
    }
}
