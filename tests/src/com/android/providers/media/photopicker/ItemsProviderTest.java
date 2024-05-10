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

import static com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;
import static com.android.providers.media.util.MimeUtils.isImageMimeType;
import static com.android.providers.media.util.MimeUtils.isVideoMimeType;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.PickerProviderMediaGenerator;
import com.android.providers.media.PickerProviderMediaGenerator.MediaGenerator;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.cloudproviders.CloudProviderPrimary;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.PaginationParameters;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;

import com.google.common.io.ByteStreams;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

    private static final Instrumentation sInstrumentation =
            InstrumentationRegistry.getInstrumentation();
    private static final Context sTargetContext = sInstrumentation.getTargetContext();
    // We are in a self-instrumentation test - MediaProviderTests - so "target" package name and
    // "own" package are the same: com.android.providers.media.tests.
    private static final String sTargetPackageName = sTargetContext.getPackageName();
    private ContentResolver mIsolatedResolver;
    private ItemsProvider mItemsProvider;
    private TestConfigStore mConfigStore;

    @Before
    public void setUp() throws Exception {
        final UiAutomation uiAutomation = sInstrumentation.getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                Manifest.permission.READ_DEVICE_CONFIG,
                Manifest.permission.INTERACT_ACROSS_USERS);

        mConfigStore = new TestConfigStore();

        final Context isolatedContext = new IsolatedContext(sTargetContext, /* tag */ "databases",
                /* asFuseThread */ false, sTargetContext.getUser(), mConfigStore);
        mIsolatedResolver = isolatedContext.getContentResolver();
        mItemsProvider = new ItemsProvider(isolatedContext);

        // Wait for MediaStore to be Idle to reduce flakes caused by database updates
        MediaStore.waitForIdle(mIsolatedResolver);
    }

    @After
    public void tearDown() throws Exception {
        setCloudProvider(null);
    }

    /**
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)}
     * to return correct info about {@link AlbumColumns#ALBUM_ID_CAMERA}.
     */
    @Test
    public void testGetCategories_camera() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Camera dir to test
        final File cameraDir = getCameraDir();
        File imageFile = assertCreateNewImage(cameraDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_CAMERA, /* numberOfItems */ 1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_CAMERA}.
     */
    @Test
    public void testGetCategories_not_camera() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
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
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_VIDEOS}.
     */
    @Test
    public void testGetCategories_videos() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 video file in Movies dir to test
        final File moviesDir = getMoviesDir();
        File videoFile = assertCreateNewVideo(moviesDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_VIDEOS, /* numberOfItems */ 1);
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_VIDEOS}.
     */
    @Test
    public void testGetCategories_not_videos() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
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
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_SCREENSHOTS}.
     */
    @Test
    public void testGetCategories_screenshots() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Screenshots dir to test
        final File screenshotsDir = getScreenshotsDir();
        File imageFile = assertCreateNewImage(screenshotsDir);
        // Create 1 image file in Screenshots dir of Downloads dir
        final File screenshotsDirInDownloadsDir = getScreenshotsDirFromDownloadsDir();
        File imageFileInScreenshotDirInDownloads =
                assertCreateNewImage(screenshotsDirInDownloadsDir);

        // Add a top level /Screenshots directory and add a test image inside of it.
        createTestScreenshotImages();

        // This file should not be included since it's not a valid screenshot directory, even though
        // it looks like one.
        final File myAlbumScreenshotsDir =
                new File(getPicturesDir(), "MyAlbum" + Environment.DIRECTORY_SCREENSHOTS);
        final File myAlbumScreenshotsImg = assertCreateNewImage(myAlbumScreenshotsDir);

        try {
            assertGetCategoriesMatchMultiple(Arrays.asList(
                    Pair.create(ALBUM_ID_SCREENSHOTS, 3),
                    Pair.create(ALBUM_ID_DOWNLOADS, 1)
            ));
        } finally {
            imageFile.delete();
            imageFileInScreenshotDirInDownloads.delete();
            myAlbumScreenshotsImg.delete();
            myAlbumScreenshotsDir.delete();
            deleteTopLevelScreenshotDir();
        }
    }

    /**
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_SCREENSHOTS}.
     */
    @Test
    public void testGetCategories_not_screenshots() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
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
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_FAVORITES}.
     */
    @Test
    public void testGetCategories_favorites() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
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
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_FAVORITES}.
     */
    @Test
    public void testGetCategories_not_favorites() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
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
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_DOWNLOADS}.
     */
    @Test
    public void testGetCategories_downloads() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Downloads dir to test
        final File downloadsDir = getDownloadsDir();
        final File imageFile = assertCreateNewImage(downloadsDir);
        try {
            assertGetCategoriesMatchSingle(ALBUM_ID_DOWNLOADS, /* numberOfItems */ 1);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_DOWNLOADS}.
     */
    @Test
    public void testGetCategories_not_downloads() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
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
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_VIDEOS}.
     */
    @Test
    public void testGetCategories_camera_and_videos() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 video file in Camera dir to test
        final File cameraDir = getCameraDir();
        File videoFile = assertCreateNewVideo(cameraDir);
        try {
            assertGetCategoriesMatchMultiple(Arrays.asList(
                    Pair.create(ALBUM_ID_VIDEOS, 1),
                    Pair.create(ALBUM_ID_CAMERA, 1)
            ));
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_FAVORITES}.
     */
    @Test
    public void testGetCategories_screenshots_and_favorites() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Screenshots dir to test
        final File screenshotsDir = getScreenshotsDir();
        File imageFile = assertCreateNewImage(screenshotsDir);
        setIsFavorite(imageFile);
        try {
            assertGetCategoriesMatchMultiple(Arrays.asList(
                    Pair.create(ALBUM_ID_FAVORITES, 1),
                    Pair.create(ALBUM_ID_SCREENSHOTS, 1)
            ));
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)} to return
     * correct info about {@link AlbumColumns#ALBUM_ID_DOWNLOADS} and
     * {@link AlbumColumns#ALBUM_ID_FAVORITES}.
     */
    @Test
    public void testGetCategories_downloads_and_favorites() throws Exception {
        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
        assertThat(c.getCount()).isEqualTo(0);

        // Create 1 image file in Screenshots dir to test
        final File downloadsDir = getDownloadsDir();
        File imageFile = assertCreateNewImage(downloadsDir);
        setIsFavorite(imageFile);
        try {
            assertGetCategoriesMatchMultiple(Arrays.asList(
                    Pair.create(ALBUM_ID_FAVORITES, 1),
                    Pair.create(ALBUM_ID_DOWNLOADS, 1)
            ));
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId,
     * CancellationSignal)}
     * to return all images and videos.
     */
    @Test
    public void testGetItems() throws Exception {
        // Create 1 image and 1 video file to test
        // Both files should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(),
                    /* mimeType */ null, /* userId */ null, /* cancellationSignal */ null);
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

    /**
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId,
     * CancellationSignal)}
     * (Category, int, String[], UserId)} to stop execution when cancellation signal
     * is triggered before query execution.
     */
    @Test(expected = OperationCanceledException.class)
    public void testGetItems_canceledBeforeQuery_ThrowsImmediately() throws Exception {
        // Create 1 image and 1 video file to test
        // Both files should be returned.
        CancellationSignal cancellationSignal = new CancellationSignal();
        cancellationSignal.cancel();

        final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                new PaginationParameters(),
                /* mimeType */ null, /* userId */ null,
                /* cancellationSignal */ cancellationSignal);
    }

    /**
     * Tests
     * {@link ItemsProvider#getLocalItems(Category, PaginationParameters, String[], UserId,
     * CancellationSignal)}
     * (Category, int, String[], UserId)} to stop execution when cancellation signal
     * is triggered before query execution.
     */
    @Test(expected = OperationCanceledException.class)
    public void testGetLocalItems_canceledBeforeQuery_ThrowsImmediately() throws Exception {
        CancellationSignal cancellationSignal = new CancellationSignal();
        cancellationSignal.cancel();

        mItemsProvider.getLocalItems(Category.DEFAULT,
                new PaginationParameters(),
                /* mimeType */ null, /* userId */ null,
                /* cancellationSignal */ cancellationSignal);
    }

    /**
     * Tests
     * {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)}
     * (Category, int, String[], UserId)} to stop execution when cancellation signal
     * is triggered before query execution.
     */
    @Test(expected = OperationCanceledException.class)
    public void testGetCategories_canceledBeforeQuery_ThrowsImmediately() throws Exception {
        CancellationSignal cancellationSignal = new CancellationSignal();
        cancellationSignal.cancel();

        mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null, cancellationSignal);
    }

    /**
     * Tests
     * {@link ItemsProvider#getAllCategories(String[], UserId, CancellationSignal)}
     * (Category, int, String[], UserId)} to stop execution when cancellation signal
     * is triggered before query execution.
     */
    @Test(expected = OperationCanceledException.class)
    public void testGetLocalCategories_canceledBeforeQuery_ThrowsImmediately() throws Exception {
        CancellationSignal cancellationSignal = new CancellationSignal();
        cancellationSignal.cancel();

        mItemsProvider.getLocalCategories(/* mimeType */ null, /* userId */ null,
                cancellationSignal);
    }

    /**
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId,
     * CancellationSignal)}
     * (Category, int, String[], UserId)} to return all
     * images and videos.
     */
    @Test
    public void testGetItems_withLimit() throws Exception {
        // Create 10 new files.
        List<File> imageFiles = assertCreateNewImagesWithDifferentDateModifiedTimes(10);
        try {
            // Set the limit and ensure that only that number of items are returned.
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(/* limit */ 5, /*dateBeforeMs*/ Long.MIN_VALUE, -1),
                    /* mimeType */ null, /* userId */ null, /* cancellationSignal */ null);
            assertThat(res).isNotNull();

            // Since the limit was set to 5 only 5 items should be returned.
            assertThat(res.getCount()).isEqualTo(5);
            assertThatOnlyImagesVideos(res);
            // Reset the cursor back. Cursor#moveToPosition(-1) will reset the position to -1,
            // but since there is no such valid cursor position, it returns false.
            assertThat(res.moveToPosition(-1)).isFalse();
        } finally {
            for (File file : imageFiles) {
                file.delete();
            }
        }
    }

    /**
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId,
     * CancellationSignal)}
     * (Category, int, String[], UserId)} to return paginated items.
     */
    @Test
    public void testGetItems_withPagination_sameDateModified() throws Exception {
        // Create 10 new files, all with same time stamp.
        List<File> imageFiles = assertCreateNewImagesWithSameDateModifiedTimes(
                /* number of images */ 10);
        try {
            // all files should be returned.
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(),
                    /* mimeType */ null, /* userId */ null, /* cancellationSignal */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(10);
            // create a list from the cursor.
            List<Item> itemList = new ArrayList<>(10);
            while (res.moveToNext()) {
                Item item = Item.fromCursor(res, UserId.CURRENT_USER);
                itemList.add(item);
            }
            res.moveToPosition(0);
            assertThatOnlyImagesVideos(res);

            // For this test, paginate the above list by returning second half of the items using
            // the pagingParameters created by the middle item of the above list.
            PaginationParameters paginationParameters = new PaginationParameters(
                    /* pageSize */ 5,
                    /* dateTaken for the last item of the previous page */
                    itemList.get(4).getDateTaken(),
                    /* rowId for the last item of the previous page */ itemList.get(4).getRowId());

            // Now set pagination parameters and get items. Since all items have the same time
            // taken
            // the pagination would be based on rowIDs.
            // Files after the middle item should be returned.
            final Cursor res2 = mItemsProvider.getAllItems(Category.DEFAULT,
                    paginationParameters, /* mimeType */ null, /* userId */ null,
                    /* cancellationSignal */ null);
            assertThat(res2).isNotNull();
            // Only 5 items should be returned.
            assertThat(res2.getCount()).isEqualTo(5);

            // Verify that the second half of the expected list has been returned.
            int itr = 5;
            while (res2.moveToNext()) {
                assertThat(Item.fromCursor(res2, UserId.CURRENT_USER).compareTo(
                        itemList.get(itr))).isEqualTo(0);
                itr++;
            }
            // Ensure all items were verified.
            assertThat(itr).isEqualTo(10);

            res2.moveToPosition(0);
            assertThatOnlyImagesVideos(res2);
        } finally {
            for (File file : imageFiles) {
                file.delete();
            }
        }
    }

    /**
     * Tests {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId)}
     * (Category, int, String[], UserId)} to return paginated items.
     */
    @Test
    public void testGetItems_withPagination_differentTimeModified() throws Exception {
        // Create 10 new files, all with different time taken.
        List<File> imageFiles = assertCreateNewImagesWithDifferentDateModifiedTimes(
                /* number of images */ 10);
        try {
            // all files should be returned.
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(),
                    /* mimeType */ null, /* userId */ null, /* cancellationSignal */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(10);
            // create a list from the cursor.
            List<Item> itemList = new ArrayList<>(10);
            while (res.moveToNext()) {
                Item item = Item.fromCursor(res, UserId.CURRENT_USER);
                itemList.add(item);
            }
            res.moveToPosition(0);
            assertThatOnlyImagesVideos(res);

            // For this test, paginate the above list by returning second half of the items using
            // the pagingParameters created by the middle item of the above list.
            PaginationParameters paginationParameters = new PaginationParameters(
                    /* pageSize */ 5,
                    /* dateTaken for the last item of the previous page */
                    itemList.get(4).getDateTaken(),
                    /* rowId for the last item of the previous page */ itemList.get(4).getRowId());

            // Now set pagination parameters and get items.
            // Files after the middle item should be returned.
            final Cursor res2 = mItemsProvider.getAllItems(Category.DEFAULT,
                    paginationParameters, /* mimeType */ null, /* userId */ null,
                    /* cancellationSignal */ null);
            assertThat(res2).isNotNull();
            // Only 5 items should be returned.
            assertThat(res2.getCount()).isEqualTo(5);

            // Verify that the second half of the expected list has been returned.
            int itr = 5;
            while (res2.moveToNext()) {
                assertThat(Item.fromCursor(res2, UserId.CURRENT_USER).compareTo(
                        itemList.get(itr))).isEqualTo(0);
                itr++;
            }
            // Ensure all items were verified.
            assertThat(itr).isEqualTo(10);

            res2.moveToPosition(0);
            assertThatOnlyImagesVideos(res2);
        } finally {
            for (File file : imageFiles) {
                file.delete();
            }
        }
    }

    /**
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[],
     * UserId)} (Category, PaginationParameters, String[], UserId)} (Category, int, String[],
     * UserId)}} does not
     * return hidden images/videos.
     */
    @Test
    public void testGetItems_nonMedia() throws Exception {
        // Create 1 image and 1 video file in a hidden dir to test
        // Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(),
                    /* mimeType */ null, /* userId */ null, /* cancellationSignal */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(0);
        } finally {
            imageFileHidden.delete();
            videoFileHidden.delete();
            hiddenDir.delete();
        }
    }

    /**
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId)}
     * (Category, PaginationParameters, String[], UserId)} (Category, int, String[], UserId)}
     * to return all
     * images and videos based on the mimeType. Image mimeType should only return images.
     */
    @Test
    public void testGetItemsImages() throws Exception {
        // Create 1 image and 1 video file to test
        // Only 1 should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(),
                    /* mimeType */ new String[]{"image/*"}, /* userId */ null,
                    /* cancellationSignal */ null);
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
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId)}
     * (Category, PaginationParameters, String[], UserId)} (Category, int, String[], UserId)}
     * to return all
     * images and videos based on the mimeType. Image mimeType should only return images.
     */
    @Test
    public void testGetItemsImages_png() throws Exception {
        // Create a jpg file image. Tests negative use case, this should not be returned below.
        File imageFile = assertCreateNewImage();
        try {
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(),
                    /* mimeType */ new String[]{"image/png"}, /* userId */ null,
                    /* cancellationSignal */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(0);
        } finally {
            imageFile.delete();
        }
    }

    /**
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId)}
     * (Category, PaginationParameters, String[], UserId)} (Category, int, String[], UserId)}
     * does not return
     * hidden images/videos.
     */
    @Test
    public void testGetItemsImages_nonMedia() throws Exception {
        // Create 1 image and 1 video file in a hidden dir to test
        // Both should not be returned.
        File hiddenDir = createHiddenDir();
        File imageFileHidden = assertCreateNewImage(hiddenDir);
        File videoFileHidden = assertCreateNewVideo(hiddenDir);
        try {
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(),
                    /* mimeType */ new String[]{"image/*"}, /* userId */ null,
                    /* cancellationSignal */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(0);
        } finally {
            imageFileHidden.delete();
            videoFileHidden.delete();
            hiddenDir.delete();
        }
    }

    /**
     * Tests {@link ItemsProvider#getLocalItemsForSelection(Category, List, String[],
     * UserId, CancellationSignal)} to return only selected items from the media table for ids
     * defined in the localId selection list.
     */
    @Test
    public void testGetItemsImages_withLocalIdSelection() throws Exception {
        List<Uri> imageFilesUris = assertCreateNewImagesWithSameDateModifiedTimesAndReturnUri(10);
        // Put the id of random items from the inserted set. say 4th and 6th item.
        ArrayList<Long> inputIds = new ArrayList<>(1);
        inputIds.add(ContentUris.parseId(imageFilesUris.get(4)));
        inputIds.add(ContentUris.parseId(imageFilesUris.get(6)));
        ArrayList<Integer> inputIdsAsIntegers =
                (ArrayList<Integer>) inputIds.stream().map(
                        (Long id) -> Integer.valueOf(Math.toIntExact(id))).collect(
                        Collectors.toList());
        try {
            // get the item objects for the provided ids.
            final Cursor res = mItemsProvider.getLocalItemsForSelection(Category.DEFAULT,
                    /* local id selection list */ inputIdsAsIntegers,
                    /* mimeType */ new String[]{"image/*"}, /* userId */ null,
                    /* cancellationSignal */ null);

            // verify that the correct number of items are returned and that they have the correct
            // ids.
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(2);
            res.moveToPosition(0);
            while (res.moveToNext()) {
                Item item = Item.fromCursor(res, UserId.CURRENT_USER);
                assertTrue(inputIds.contains(Long.parseLong(item.getId())));
            }
            assertThatOnlyImages(res);
        } finally {
            // clean up.
            deleteAllFilesNoThrow();
        }
    }

    /**
     * Tests {@link ItemsProvider#getLocalItemsForSelection(Category, List, String[],
     * UserId, CancellationSignal)} to return only selected items from the media table for ids
     * defined in the localId selection list.
     */
    @Test
    public void testGetItemsImages_withLocalIdSelection_largeDataSet() throws Exception {
        List<Uri> imageFilesUris = assertCreateNewImagesWithSameDateModifiedTimesAndReturnUri(200);
        // Try to fetch all items via selection. 200 items, this will hit the split query and
        // verify that it is working.
        List<Integer> inputIdsAsIntegers = imageFilesUris.stream().map(ContentUris::parseId).map(
                Long::intValue).collect(Collectors.toList());
        try {
            // get the item objects for the provided ids.
            final Cursor res = mItemsProvider.getLocalItemsForSelection(Category.DEFAULT,
                    /* local id selection list */ inputIdsAsIntegers,
                    /* mimeType */ new String[]{"image/*"}, /* userId */ null,
                    /* cancellationSignal */ null);

            // verify that the correct number of items are returned and that they have the correct
            // ids.
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(inputIdsAsIntegers.size());
            res.moveToPosition(0);
            while (res.moveToNext()) {
                Item item = Item.fromCursor(res, UserId.CURRENT_USER);
                assertTrue(inputIdsAsIntegers.contains(Integer.parseInt(item.getId())));
            }
            assertThatOnlyImages(res);
        } finally {
            // clean up.
            deleteAllFilesNoThrow();
        }
    }

    /**
     * Tests {@link ItemsProvider#getLocalItemsForSelection(Category, List, String[],
     * UserId, CancellationSignal)} to return only selected items from the media table for ids
     * defined in the localId selection list. Here the list is empty so the parameter is ignored and
     * the list is returned without any selection.
     */
    @Test
    public void testGetItemsImages_withLocalIdSelectionEmpty() throws Exception {
        assertCreateNewImagesWithSameDateModifiedTimesAndReturnUri(10);
        try {
            // get the item objects for the empty list.
            final Cursor res = mItemsProvider.getLocalItemsForSelection(Category.DEFAULT,
                    /* local id selection list */ new ArrayList<>(),
                    /* mimeType */ new String[]{"image/*"}, /* userId */ null,
                    /* cancellationSignal */ null);

            assertThat(res).isNotNull();
            // All images are returned and selection is ignored.
            assertThat(res.getCount()).isEqualTo(10);
            assertThatOnlyImages(res);
        } finally {
            // clean up.
            deleteAllFilesNoThrow();
        }
    }


    /**
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId)}
     * (Category, PaginationParameters, String[], UserId)} (Category, int, String[], UserId)}
     * to return all
     * images and videos based on the mimeType. Video mimeType should only return videos.
     */
    @Test
    public void testGetItemsVideos() throws Exception {
        // Create 1 image and 1 video file to test
        // Only 1 should be returned.
        File imageFile = assertCreateNewImage();
        File videoFile = assertCreateNewVideo();
        try {
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(),
                    /* mimeType */ new String[]{"video/*"}, /* userId */ null,
                    /* cancellationSignal */ null);
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
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId)}
     * (Category, PaginationParameters, String[], UserId)} (Category, int, String[], UserId)}
     * to return all
     * images and videos based on the mimeType. Image mimeType should only return images.
     */
    @Test
    public void testGetItemsVideos_mp4() throws Exception {
        // Create a mp4 video file. Tests positive use case, this should be returned below.
        File videoFile = assertCreateNewVideo();
        try {
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(),
                    /* mimeType */ new String[]{"video/mp4"}, /* userId */ null,
                    /* cancellationSignal */ null);
            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(1);
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Tests
     * {@link ItemsProvider#getAllItems(Category, PaginationParameters, String[], UserId)}
     * (Category, PaginationParameters, String[], UserId)} does not return
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
            final Cursor res = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(),
                    /* mimeType */ new String[]{"video/*"}, /* userId */ null,
                    /* cancellationSignal */ null);

            assertThat(res).isNotNull();
            assertThat(res.getCount()).isEqualTo(0);
        } finally {
            imageFileHidden.delete();
            videoFileHidden.delete();
            hiddenDir.delete();
        }
    }

    /**
     * Tests
     * {@link ItemsProvider#getLocalItems(Category, PaginationParameters, String[], UserId)}
     * to returns only
     * local content.
     */
    @Test
    public void testGetLocalItems_withCloudFeatureOn() throws Exception {
        File videoFile = assertCreateNewVideo();
        try {
            mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                    sTargetPackageName);
            // Init cloud provider with no items. We cannot test for cloud items because
            // getAllItems query does not block on cloud sync.
            setupCloudProvider((cloudMediaGenerator) -> {});
            mItemsProvider.initPhotoPickerData(/* albumId */ null,
                    /* albumAuthority */ null,
                    /*initLocalOnlyData */ false,
                    UserId.CURRENT_USER);

            // Verify that getLocalItems includes all local contents
            try (Cursor c = mItemsProvider.getLocalItems(Category.DEFAULT,
                    new PaginationParameters(), new String[]{},
                    UserId.CURRENT_USER, /* cancellationSignal */ null)) {
                assertThat(c.getCount()).isEqualTo(1);

                assertThat(c.moveToFirst()).isTrue();
                assertThat(c.getString(c.getColumnIndexOrThrow(MediaColumns.AUTHORITY)))
                        .isEqualTo(LOCAL_PICKER_PROVIDER_AUTHORITY);
            }

            // Verify that getAllItems also includes local items. We cannot check for cloud items
            // because getAllItems query does not block on cloud sync.
            try (Cursor c = mItemsProvider.getAllItems(Category.DEFAULT,
                    new PaginationParameters(), new String[]{},
                    UserId.CURRENT_USER,
                    /* cancellationSignal */ null)) {
                assertThat(c.getCount()).isEqualTo(1);

                // Verify that the first item is cloud item
                assertThat(c.moveToFirst()).isTrue();
                assertThat(c.getString(c.getColumnIndexOrThrow(MediaColumns.AUTHORITY)))
                        .isEqualTo(LOCAL_PICKER_PROVIDER_AUTHORITY);
            }
        } finally {
            videoFile.delete();
            setCloudProvider(null);
            mConfigStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();
        }
    }

    @Test
    public void testGetLocalItems_mergedAlbum_withCloudFeatureOn() throws Exception {
        File videoFile = assertCreateNewVideo();
        Category videoAlbum = new Category(CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS,
                LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 10, true);
        try {
            mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                    sTargetPackageName);
            // Init cloud provider with no items. We cannot test for cloud items because
            // getAllItems query does not block on cloud sync.
            setupCloudProvider((cloudMediaGenerator) -> {});
            mItemsProvider.initPhotoPickerData(/* albumId */ null,
                    /* albumAuthority */ null,
                    /*initLocalOnlyData */ false,
                    UserId.CURRENT_USER);

            // Verify that getLocalItems for merged album "Video" includes all local contents
            try (Cursor c = mItemsProvider.getLocalItems(videoAlbum,
                    new PaginationParameters(), new String[]{},
                    UserId.CURRENT_USER, /* cancellationSignal */ null)) {
                assertThat(c.getCount()).isEqualTo(1);
                assertThat(c.moveToFirst()).isTrue();
                assertThat(c.getString(c.getColumnIndexOrThrow(MediaColumns.AUTHORITY)))
                        .isEqualTo(LOCAL_PICKER_PROVIDER_AUTHORITY);
            }

            // Verify that getAllItems for merged album "Video" also includes all local contents.
            // We cannot check for cloud items because getAllItems query does not block on cloud
            // sync.
            try (Cursor c = mItemsProvider.getAllItems(videoAlbum, new PaginationParameters(),
                    new String[]{},
                    UserId.CURRENT_USER,
                    /* cancellationSignal */ null)) {
                assertThat(c.getCount()).isEqualTo(1);
                // Verify that the first item is cloud item
                assertThat(c.moveToFirst()).isTrue();
                assertThat(c.getString(c.getColumnIndexOrThrow(MediaColumns.AUTHORITY)))
                        .isEqualTo(LOCAL_PICKER_PROVIDER_AUTHORITY);
            }
        } finally {
            videoFile.delete();
            setCloudProvider(null);
            mConfigStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();
        }
    }

    @Test
    public void testGetLocalCategories_withCloudFeatureOn() throws Exception {
        File videoFile = assertCreateNewVideo(getMoviesDir());
        File screenshotFile = assertCreateNewImage(getScreenshotsDir());
        final String cloudAlbum = "testAlbum";

        try {
            mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                    sTargetPackageName);
            // Init cloud provider with 2 items and one cloud album
            setupCloudProvider((cloudMediaGenerator) -> {
                cloudMediaGenerator.addMedia(null, "cloud_id1", null, "video/mp4", 0, 1024, false);
                cloudMediaGenerator.addMedia(null, "cloud_id2", cloudAlbum, "image/jpeg", 0, 1024,
                        false);
                cloudMediaGenerator.createAlbum(cloudAlbum);
            });
            mItemsProvider.initPhotoPickerData(/* albumId */ null,
                    /* albumAuthority */ null,
                    /*initLocalOnlyData */ false,
                    UserId.CURRENT_USER);

            // Verify that getLocalCategories only returns local albums
            try (Cursor c = mItemsProvider.getLocalCategories(/* mimeType */ null,
                    /* userId */ null, /* cancellationSignal*/ null)) {
                assertGetCategoriesMatchMultiple(c, Arrays.asList(
                        Pair.create(ALBUM_ID_VIDEOS, 1),
                        Pair.create(ALBUM_ID_SCREENSHOTS, 1)
                ));
            }


            // Verify that getAllCategories returns local + cloud albums
            try (Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null,
                    /* userId */ null, /* cancellationSignal*/ null)) {
                assertGetCategoriesMatchMultiple(c, Arrays.asList(
                        Pair.create(ALBUM_ID_FAVORITES, 0),
                        Pair.create(ALBUM_ID_VIDEOS, 2),
                        Pair.create(ALBUM_ID_SCREENSHOTS, 1),
                        Pair.create(cloudAlbum, 1)
                ));
            }
        } finally {
            videoFile.delete();
            screenshotFile.delete();
            setCloudProvider(null);
            mConfigStore.clearAllowedCloudProviderPackagesAndDisableCloudMediaFeature();
        }
    }

    private void setupCloudProvider(Consumer<MediaGenerator> initMedia) {
        MediaGenerator cloudPrimaryMediaGenerator =
                PickerProviderMediaGenerator.getMediaGenerator(CloudProviderPrimary.AUTHORITY);
        cloudPrimaryMediaGenerator.resetAll();
        cloudPrimaryMediaGenerator.setMediaCollectionId("COLLECTION_1");
        initMedia.accept(cloudPrimaryMediaGenerator);

        // Set the newly initialized cloud provider.
        setCloudProvider(CloudProviderPrimary.AUTHORITY);

        mIsolatedResolver.call(MediaStore.AUTHORITY, MediaStore.SYNC_PROVIDERS_CALL, null,
                Bundle.EMPTY);
    }

    private void setCloudProvider(String authority) {
        Bundle in = new Bundle();
        in.putString(MediaStore.EXTRA_CLOUD_PROVIDER, authority);
        mIsolatedResolver.call(MediaStore.AUTHORITY, MediaStore.SET_CLOUD_PROVIDER_CALL, null, in);
        if (authority != null) {
            assertWithMessage("Expected " + authority + " to be current cloud provider.")
                    .that(MediaStore.isCurrentCloudMediaProviderAuthority(mIsolatedResolver,
                            authority)).isTrue();
        }
    }

    private void assertGetCategoriesMatchSingle(String expectedCategoryName,
            int expectedNumberOfItems) throws Exception {
        if (expectedNumberOfItems == 0) {
            assertCategoriesNoMatch(expectedCategoryName);
            return;
        }

        Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null);
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
                LOCAL_PICKER_PROVIDER_AUTHORITY, UserId.CURRENT_USER);

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
        try (Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null)) {
            while (c != null && c.moveToNext()) {
                final int nameColumnIndex = c.getColumnIndexOrThrow(AlbumColumns.DISPLAY_NAME);
                final String categoryName = c.getString(nameColumnIndex);
                assertThat(categoryName).isNotEqualTo(expectedCategoryName);
            }
        }
    }

    private void assertGetCategoriesMatchMultiple(List<Pair<String, Integer>> categories) {
        try (Cursor c = mItemsProvider.getAllCategories(/* mimeType */ null, /* userId */ null,
                /* cancellationSignal*/ null)) {
            assertGetCategoriesMatchMultiple(c, categories);
        }
    }

    private void assertGetCategoriesMatchMultiple(Cursor c,
            List<Pair<String, Integer>> categories) {
        assertThat(c).isNotNull();
        assertWithMessage("Expected number of albums")
                .that(c.getCount()).isEqualTo(categories.size());

        final int nameColumnIndex = c.getColumnIndexOrThrow(AlbumColumns.DISPLAY_NAME);
        final int numOfItemsColumnIndex = c.getColumnIndexOrThrow(
                AlbumColumns.MEDIA_COUNT);
        for (Pair<String, Integer> category : categories) {
            c.moveToNext();

            assertThat(category.first).isEqualTo(c.getString(nameColumnIndex));
            assertWithMessage("Expected item count for " + category.first).that(
                    c.getInt(numOfItemsColumnIndex)).isEqualTo(category.second);
        }
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

    private void createTestScreenshotImages() throws IOException {
        // Top Level /Screenshots/ directory is not allowed by MediaProvider, so the directory
        // and test files in it are created via shell commands.

        final String createTopLevelScreenshotDirCommand =
                "mkdir -p "
                        + Environment.getExternalStorageDirectory().getPath()
                        + "/"
                        + Environment.DIRECTORY_SCREENSHOTS;
        final String createTopLevelScreenshotImgCommand =
                "touch " + getTopLevelScreenshotsDir().getPath() + "/" + IMAGE_FILE_NAME;
        final String writeDataTopLevelScreenshotImgCommand =
                "echo 1 > " + getTopLevelScreenshotsDir().getPath() + "/" + IMAGE_FILE_NAME;

        executeShellCommand(createTopLevelScreenshotDirCommand);
        executeShellCommand(createTopLevelScreenshotImgCommand);
        // Writes 1 byte to the file.
        executeShellCommand(writeDataTopLevelScreenshotImgCommand);

        final File topLevelScreenshotsDirImage =
                new File(getTopLevelScreenshotsDir(), IMAGE_FILE_NAME);

        // Force the mock MediaProvider to scan.
        final Uri uri = MediaStore.scanFile(mIsolatedResolver, topLevelScreenshotsDirImage);
        assertWithMessage("Uri obtained by scanning file " + topLevelScreenshotsDirImage)
                .that(uri)
                .isNotNull();
        // Wait for picker db sync
        MediaStore.waitForIdle(mIsolatedResolver);
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

    private List<File> assertCreateNewImagesWithDifferentDateModifiedTimes(int numberOfImages)
            throws Exception {
        List<File> imageFiles = new ArrayList<>();
        for (int itr = 0; itr < numberOfImages; itr++) {
            String fileName = TAG + "_file_" + String.valueOf(System.nanoTime()) + ".jpg";
            imageFiles.add(assertCreateNewFileWithLastModifiedTime(getDownloadsDir(), fileName,
                    System.nanoTime() / 1000));
        }
        return imageFiles;
    }

    private List<File> assertCreateNewImagesWithSameDateModifiedTimes(int numberOfImages)
            throws Exception {
        List<File> imageFiles = new ArrayList<>();
        long currentTime = System.nanoTime() / 1000;
        for (int itr = 0; itr < numberOfImages; itr++) {
            String fileName = TAG + "_file_" + String.valueOf(System.nanoTime()) + ".jpg";
            imageFiles.add(assertCreateNewFileWithLastModifiedTime(getDownloadsDir(), fileName,
                    currentTime));
        }
        return imageFiles;
    }


    private List<Uri> assertCreateNewImagesWithSameDateModifiedTimesAndReturnUri(int numberOfImages)
            throws Exception {
        List<Uri> imageFiles = new ArrayList<>();
        long currentTime = System.nanoTime() / 1000;
        for (int itr = 0; itr < numberOfImages; itr++) {
            String fileName = TAG + "_file_" + String.valueOf(System.nanoTime()) + ".jpg";
            imageFiles.add(assertCreateNewFileWithLastModifiedTimeAndReturnUri(
                    getDownloadsDir(), fileName, currentTime));
        }
        return imageFiles;
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

    private File assertCreateNewFileWithLastModifiedTime(File parentDir, String fileName,
            long lastModifiedTime) throws Exception {
        final File file = new File(parentDir, fileName);
        prepareFileAndGetUri(file, lastModifiedTime);
        return file;
    }
    private Uri assertCreateNewFileWithLastModifiedTimeAndReturnUri(File parentDir, String fileName,
            long lastModifiedTime) throws Exception {
        final File file = new File(parentDir, fileName);
        return prepareFileAndGetUri(file, lastModifiedTime);
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

    private File getTopLevelScreenshotsDir() {
        return new File(
                Environment.getExternalStorageDirectory(), Environment.DIRECTORY_SCREENSHOTS);
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
                new String[]{MediaStore.MediaColumns.DATA}, null, null)) {
            while (c.moveToNext()) {
                (new File(c.getString(
                        c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)))).delete();
            }
        }
    }

    private void deleteTopLevelScreenshotDir() throws IOException {
        final String removeTopLevelScreenshotDirCommand =
                "rm -rf " + getTopLevelScreenshotsDir().getPath();
        executeShellCommand(removeTopLevelScreenshotDirCommand);
    }

    /** Executes a shell command. */
    private static String executeShellCommand(String command) throws IOException {
        int attempt = 0;
        while (attempt++ < 5) {
            try {
                return executeShellCommandInternal(command);
            } catch (InterruptedIOException e) {
                Log.v(TAG, "Trouble executing " + command + "; trying again", e);
            }
        }
        throw new IOException("Failed to execute " + command);
    }

    private static String executeShellCommandInternal(String cmd) throws IOException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (FileInputStream output = new FileInputStream(
                uiAutomation.executeShellCommand(cmd).getFileDescriptor())) {
            return new String(ByteStreams.toByteArray(output));
        }
    }
}
