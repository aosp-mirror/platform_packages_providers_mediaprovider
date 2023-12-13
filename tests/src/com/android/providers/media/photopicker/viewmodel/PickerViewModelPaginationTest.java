/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media.photopicker.viewmodel;

import static android.provider.MediaStore.VOLUME_EXTERNAL;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_CLEAR_AND_UPDATE_LIST;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_LOAD_NEXT_PAGE;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_REFRESH_ITEMS;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_VIEW_CREATED;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;

import androidx.lifecycle.LiveData;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.photopicker.DataLoaderThread;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.PaginationParameters;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PickerViewModelPaginationTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Mock
    private Application mApplication;

    private PickerViewModel mPickerViewModel;

    private static final Instrumentation sInstrumentation = getInstrumentation();
    private static final Context sTargetContext = sInstrumentation.getTargetContext();

    private static final String TAG = "PickerViewModelTest";
    private ContentResolver mIsolatedResolver;

    public PickerViewModelPaginationTest() {

    }

    @Before
    public void setUp() {
        final UiAutomation uiAutomation = sInstrumentation.getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                Manifest.permission.READ_DEVICE_CONFIG,
                Manifest.permission.INTERACT_ACROSS_USERS);
        MockitoAnnotations.initMocks(this);

        final TestConfigStore testConfigStore = new TestConfigStore();
        testConfigStore.enableCloudMediaFeature();

        final Context isolatedContext = new IsolatedContext(sTargetContext, /* tag */ "databases",
                /* asFuseThread */ false, sTargetContext.getUser(), testConfigStore);
        when(mApplication.getApplicationContext()).thenReturn(isolatedContext);
        sInstrumentation.runOnMainSync(() -> {
            mPickerViewModel = new PickerViewModel(mApplication) {
                @Override
                protected void initConfigStore() {
                    setConfigStore(testConfigStore);
                }
            };
        });
        final UserIdManager userIdManager = mock(UserIdManager.class);
        when(userIdManager.getCurrentUserProfileId()).thenReturn(UserId.CURRENT_USER);
        mPickerViewModel.setUserIdManager(userIdManager);
        mIsolatedResolver = isolatedContext.getContentResolver();
        final ItemsProvider itemsProvider = new ItemsProvider(isolatedContext);
        mPickerViewModel.setItemsProvider(itemsProvider);
        mPickerViewModel.clearItemsAndCategoryItemsList();
    }

    @Test
    public void test_getItems_noItemsPresent() throws Exception {
        int pageSize = 4;
        final int numberOfTestItems = 0;
        try {
            // Generate test items.
            assertCreateNewImagesWithCategoryDownloads(numberOfTestItems);

            // Get live data for items, this also loads the first page.
            LiveData<PickerViewModel.PaginatedItemsResult> testItems =
                    mPickerViewModel.getPaginatedItemsForAction(
                            ACTION_VIEW_CREATED, new PaginationParameters(
                                    pageSize, /*dateBeforeMs*/ Long.MIN_VALUE, /* rowId*/ -1));
            DataLoaderThread.waitForIdle();

            // Empty list should be returned.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(numberOfTestItems);

            // Load next page size number of images.
            mPickerViewModel.getPaginatedItemsForAction(ACTION_LOAD_NEXT_PAGE, null);
            DataLoaderThread.waitForIdle();

            // Empty list should be returned.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(numberOfTestItems);
        } finally {
            mPickerViewModel.clearItemsAndCategoryItemsList();
            deleteAllFilesNoThrow();
        }
    }

    @Test
    public void test_getCategoryItems_noItemsPresent() throws Exception {
        int pageSize = 4;
        final int numberOfTestItems = 0;
        Category downloadsAlbum = new Category(
                CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS,
                LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true);
        try {
            // Generate test items.
            assertCreateNewImagesWithCategoryDownloads(
                    numberOfTestItems);

            // Get live data for items, this also loads the first page.
            LiveData<PickerViewModel.PaginatedItemsResult> testItems =
                    mPickerViewModel.getPaginatedCategoryItemsForAction(
                            downloadsAlbum, ACTION_VIEW_CREATED,
                            new PaginationParameters(
                                    pageSize, /*dateBeforeMs*/ Long.MIN_VALUE, /* rowId*/ -1));
            DataLoaderThread.waitForIdle();

            // Empty list should be returned.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(numberOfTestItems);

            // Load next page size number of images.
            mPickerViewModel.getPaginatedCategoryItemsForAction(
                    downloadsAlbum, ACTION_LOAD_NEXT_PAGE, null);
            DataLoaderThread.waitForIdle();

            // Empty list should be returned.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(numberOfTestItems);
        } finally {
            mPickerViewModel.clearItemsAndCategoryItemsList();
            deleteAllFilesNoThrow();
        }
    }

    @Test
    public void test_getItems_correctItemsReturned() throws Exception {
        int pageSize = 4;
        final int numberOfTestItems = 10;

        try {
            // Generate test items.
            assertCreateNewImagesWithCategoryDownloads(
                    numberOfTestItems);

            // Get live data for items, this also loads the first page.
            LiveData<PickerViewModel.PaginatedItemsResult> testItems =
                    mPickerViewModel.getPaginatedItemsForAction(
                            ACTION_VIEW_CREATED, new PaginationParameters(
                                    pageSize, /*dateBeforeMs*/ Long.MIN_VALUE, /* rowId*/ -1));
            DataLoaderThread.waitForIdle();

            // Page 1: Since the page size is set to 4, only 4 images should be returned.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(pageSize);

            // Load next page size number of images.
            mPickerViewModel.getPaginatedItemsForAction(ACTION_LOAD_NEXT_PAGE, null);
            DataLoaderThread.waitForIdle();

            // Page 2: 8 images should be returned.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(2 * pageSize);

            // Load next page size number of images.
            mPickerViewModel.getPaginatedItemsForAction(ACTION_LOAD_NEXT_PAGE, null);
            DataLoaderThread.waitForIdle();

            // Page 3: all 10 images should be returned. All items loaded.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(numberOfTestItems);

            // Try loading once more, but the number of images should not change since we have
            // exhausted the list.
            mPickerViewModel.getPaginatedItemsForAction(ACTION_LOAD_NEXT_PAGE, null);
            DataLoaderThread.waitForIdle();

            // All items loaded.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(numberOfTestItems);


        } finally {
            mPickerViewModel.clearItemsAndCategoryItemsList();
            deleteAllFilesNoThrow();
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    @Test
    public void test_differentCategories_getCategoryItems() throws Exception {
        int pageSize = 4;
        final int numberOfTestItemsInDownloads = 10;
        final int numberOfTestItemsInCamera = 7;
        try {
            // generate items in category downloads.
            assertCreateNewImagesWithCategoryDownloads(numberOfTestItemsInDownloads);

            // generate items in category camera.
            assertCreateNewImagesWithCategoryCamera(numberOfTestItemsInCamera);

            ////////////////// Verify Category Camera //////////////////

            Category cameraAlbum = new Category(
                    CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA,
                    LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true);

            mPickerViewModel.initPhotoPickerData(cameraAlbum);
            DataLoaderThread.waitForIdle();
            LiveData<PickerViewModel.PaginatedItemsResult> testItems =
                    mPickerViewModel.getPaginatedCategoryItemsForAction(
                            cameraAlbum, ACTION_VIEW_CREATED,
                            new PaginationParameters(
                                    pageSize, /*dateBeforeMs*/ Long.MIN_VALUE, /* rowId*/ -1));
            DataLoaderThread.waitForIdle();

            // Page 1: Since the page size is set to 4, only 4 images should be returned.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(pageSize);

            // Load next page size number of images.
            mPickerViewModel.getPaginatedCategoryItemsForAction(cameraAlbum,
                    ACTION_LOAD_NEXT_PAGE,
                    null);
            DataLoaderThread.waitForIdle();

            // Page 2: 7 images should be returned.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(numberOfTestItemsInCamera);

            // Try loading once more, but the number of images should not change since we have
            // exhausted the list.
            mPickerViewModel.getPaginatedCategoryItemsForAction(cameraAlbum,
                    ACTION_LOAD_NEXT_PAGE,
                    null);
            DataLoaderThread.waitForIdle();

            // All items loaded.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(numberOfTestItemsInCamera);

            ////////////////// Verify Category Downloads //////////////////

            Category downloadsAlbum = new Category(
                    CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS,
                    LOCAL_PICKER_PROVIDER_AUTHORITY, "", null, 100, true);

            mPickerViewModel.initPhotoPickerData(downloadsAlbum);
            DataLoaderThread.waitForIdle();
            LiveData<PickerViewModel.PaginatedItemsResult> testItemsDownloads =
                    mPickerViewModel.getPaginatedCategoryItemsForAction(
                            downloadsAlbum, ACTION_VIEW_CREATED,
                            new PaginationParameters(
                                    pageSize, /*dateBeforeMs*/ Long.MIN_VALUE, /* rowId*/ -1));
            DataLoaderThread.waitForIdle();

            // Page 1: Since the page size is set to 4, only 4 images should be returned.
            assertThat(testItemsDownloads.getValue().getItems().size()).isEqualTo(pageSize);

            // Load next page size number of images.
            mPickerViewModel.getPaginatedCategoryItemsForAction(
                    downloadsAlbum, ACTION_LOAD_NEXT_PAGE, null);
            DataLoaderThread.waitForIdle();

            // Page 2: 8 images should be returned.
            assertThat(testItemsDownloads.getValue().getItems().size()).isEqualTo(2 * pageSize);

            // Load next page size number of images.
            mPickerViewModel.getPaginatedCategoryItemsForAction(
                    downloadsAlbum, ACTION_LOAD_NEXT_PAGE, null);
            DataLoaderThread.waitForIdle();

            // Page 3: all 10 images should be returned.
            assertThat(testItemsDownloads.getValue().getItems().size()).isEqualTo(
                    numberOfTestItemsInDownloads);


            // Try loading once more, but the number of images should not change since we have
            // exhausted the list.
            mPickerViewModel.getPaginatedCategoryItemsForAction(
                    downloadsAlbum, ACTION_LOAD_NEXT_PAGE, null);
            DataLoaderThread.waitForIdle();

            // All items loaded.
            assertThat(testItemsDownloads.getValue().getItems().size()).isEqualTo(
                    numberOfTestItemsInDownloads);

        } finally {
            mPickerViewModel.clearItemsAndCategoryItemsList();
            deleteAllFilesNoThrow();
        }
    }

    @Test
    public void test_updateItems_itemsResetAndFirstPageLoaded() throws Exception {
        int pageSize = 4;
        final int numberOfTestItems = 10;

        try {
            // Generate test items.
            assertCreateNewImagesWithCategoryDownloads(
                    numberOfTestItems);

            // Get live data for items, this also loads the first page.
            LiveData<PickerViewModel.PaginatedItemsResult> testItems =
                    mPickerViewModel.getPaginatedItemsForAction(
                            ACTION_VIEW_CREATED, new PaginationParameters(pageSize,
                                    /*dateBeforeMs*/ Long.MIN_VALUE, /* rowId*/ -1));
            DataLoaderThread.waitForIdle();

            // Page 1: Since the page size is set to 4, only 4 images should be returned.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(pageSize);

            // Load next page size number of images.
            mPickerViewModel.getPaginatedItemsForAction(ACTION_LOAD_NEXT_PAGE, null);
            DataLoaderThread.waitForIdle();

            // Page 2: 8 images should be returned.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(2 * pageSize);

            // Now 8 items have been loaded in the item list.
            // Call updateItems which is usually called on profile switch or reset.
            // This should clear out the list and load the first page.
            mPickerViewModel.getPaginatedItemsForAction(ACTION_CLEAR_AND_UPDATE_LIST, null);
            DataLoaderThread.waitForIdle();

            // Assert that only one page of items are present now.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(pageSize);


        } finally {
            mPickerViewModel.clearItemsAndCategoryItemsList();
            deleteAllFilesNoThrow();
        }
    }

    @Test
    public void test_onReceivingNotification_itemsRefreshed() throws Exception {
        int pageSize = 10;
        final int numberOfTestItems = 10;

        try {
            // Generate test items.
            assertCreateNewImagesWithCategoryDownloads(
                    numberOfTestItems);

            // Get live data for items, this also loads the first page. Here all 10 items will be
            // loaded.
            LiveData<PickerViewModel.PaginatedItemsResult> testItems =
                    mPickerViewModel.getPaginatedItemsForAction(
                            ACTION_VIEW_CREATED, new PaginationParameters(pageSize,
                                    /*dateBeforeMs*/ Long.MIN_VALUE, /* rowId*/ -1));
            DataLoaderThread.waitForIdle();

            assertThat(testItems.getValue().getItems().size()).isEqualTo(pageSize);

            // Store this values.
            List<Item> previousList = testItems.getValue().getItems();

            // add 2 new images.
            assertCreateNewImagesWithCategoryDownloads(/* count of new items */ 2);

            mPickerViewModel.setNotificationForUpdateReceived(true);

            // Now 8 items have been loaded in the item list.
            // Call updateItems which is usually called on profile switch or reset.
            // This should clear out the list and load the first page.
            mPickerViewModel.getPaginatedItemsForAction(ACTION_REFRESH_ITEMS,
                    new PaginationParameters(
                            pageSize, /*dateBeforeMs*/ Long.MIN_VALUE, /* rowId*/ -1));
            DataLoaderThread.waitForIdle();

            // Assert that only one page of items are present now.
            assertThat(testItems.getValue().getItems().size()).isEqualTo(pageSize);
            List<Item> currentList = testItems.getValue().getItems();
            for (int itr = 0; itr < currentList.size(); itr++) {
                assertThat(currentList.get(itr).compareTo(previousList.get(itr))).isNotEqualTo(0);
                if (itr >= 2) {
                    // assert items have shifted by 2.
                    assertThat(currentList.get(itr).compareTo(previousList.get(itr - 2))).isEqualTo(
                            0);
                }
            }


        } finally {
            mPickerViewModel.clearItemsAndCategoryItemsList();
            deleteAllFilesNoThrow();
        }
    }

    private List<File> assertCreateNewImagesWithCategoryDownloads(int numberOfImages)
            throws Exception {
        List<File> imageFiles = new ArrayList<>();
        for (int itr = 0; itr < numberOfImages; itr++) {
            String fileName = TAG + "_file_" + String.valueOf(System.nanoTime()) + ".jpg";
            imageFiles.add(assertCreateNewFileWithLastModifiedTime(getDownloadsDir(), fileName,
                    System.nanoTime() / 1000));
        }
        return imageFiles;
    }

    private List<File> assertCreateNewImagesWithCategoryCamera(int numberOfImages)
            throws Exception {
        List<File> imageFiles = new ArrayList<>();
        for (int itr = 0; itr < numberOfImages; itr++) {
            String fileName = TAG + "_file_" + String.valueOf(System.nanoTime()) + ".jpg";
            imageFiles.add(assertCreateNewFileWithLastModifiedTime(getCameraDir(), fileName,
                    System.nanoTime() / 1000));
        }
        return imageFiles;
    }

    private File getDownloadsDir() {
        return new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS);
    }

    private File getCameraDir() {
        return new File(getDcimDir(), "Camera");
    }

    private File getDcimDir() {
        return new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DCIM);
    }

    private File assertCreateNewFileWithLastModifiedTime(File parentDir, String fileName,
            long lastModifiedTime) throws Exception {
        final File file = new File(parentDir, fileName);
        prepareFileAndGetUri(file, lastModifiedTime);
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
}
