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

package com.android.providers.media.photopicker.viewmodel;

import static android.provider.CloudMediaProviderContract.AlbumColumns;
import static android.provider.CloudMediaProviderContract.MediaColumns;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.MediaStore;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.ModelTestUtils;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.util.ForegroundThread;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PickerViewModelTest {
    private static final String FAKE_CATEGORY_NAME = "testCategoryName";
    private static final String FAKE_ID = "5";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Mock
    private Application mApplication;

    private PickerViewModel mPickerViewModel;
    private TestItemsProvider mItemsProvider;
    private TestConfigStore mConfigStore;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final Context context = InstrumentationRegistry.getTargetContext();
        when(mApplication.getApplicationContext()).thenReturn(context);
        mConfigStore = new TestConfigStore();
        mConfigStore.enableCloudMediaFeature();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPickerViewModel = new PickerViewModel(mApplication) {
                @Override
                protected ConfigStore getConfigStore() {
                    return mConfigStore;
                }
            };
        });
        mItemsProvider = new TestItemsProvider(context);
        mPickerViewModel.setItemsProvider(mItemsProvider);
        UserIdManager userIdManager = mock(UserIdManager.class);
        when(userIdManager.getCurrentUserProfileId()).thenReturn(UserId.CURRENT_USER);
        mPickerViewModel.setUserIdManager(userIdManager);
    }

    @Test
    public void testGetItems_noItems() {
        final int itemCount = 0;
        mItemsProvider.setItems(generateFakeImageItemList(itemCount));
        mPickerViewModel.updateItems();
        // We use ForegroundThread to execute the loadItems in updateItems(), wait for the thread
        // idle
        ForegroundThread.waitForIdle();

        final List<Item> itemList = mPickerViewModel.getItems().getValue();

        // No date headers, the size should be 0
        assertThat(itemList.size()).isEqualTo(itemCount);
    }

    @Test
    public void testGetCategories() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final int categoryCount = 2;
        try (final Cursor fakeCursor = generateCursorForFakeCategories(categoryCount)) {
            fakeCursor.moveToFirst();
            final Category fakeFirstCategory = Category.fromCursor(fakeCursor, UserId.CURRENT_USER);
            fakeCursor.moveToNext();
            final Category fakeSecondCategory = Category.fromCursor(fakeCursor,
                    UserId.CURRENT_USER);
            mItemsProvider.setCategoriesCursor(fakeCursor);
            // move the cursor to original position
            fakeCursor.moveToPosition(-1);
            mPickerViewModel.updateCategories();
            // We use ForegroundThread to execute the loadCategories in updateCategories(), wait for
            // the thread idle
            ForegroundThread.waitForIdle();

            final List<Category> categoryList = mPickerViewModel.getCategories().getValue();

            assertThat(categoryList.size()).isEqualTo(categoryCount);
            // Verify the first category
            final Category firstCategory = categoryList.get(0);
            assertThat(firstCategory.getDisplayName(context)).isEqualTo(
                    fakeFirstCategory.getDisplayName(context));
            assertThat(firstCategory.getItemCount()).isEqualTo(fakeFirstCategory.getItemCount());
            assertThat(firstCategory.getCoverUri()).isEqualTo(fakeFirstCategory.getCoverUri());
            // Verify the second category
            final Category secondCategory = categoryList.get(1);
            assertThat(secondCategory.getDisplayName(context)).isEqualTo(
                    fakeSecondCategory.getDisplayName(context));
            assertThat(secondCategory.getItemCount()).isEqualTo(fakeSecondCategory.getItemCount());
            assertThat(secondCategory.getCoverUri()).isEqualTo(fakeSecondCategory.getCoverUri());
        }
    }

    private static Item generateFakeImageItem(String id) {
        final long dateTakenMs = System.currentTimeMillis()
                + Long.parseLong(id) * DateUtils.DAY_IN_MILLIS;
        return ModelTestUtils.generateJpegItem(id, dateTakenMs, /* generationModified */ 1L);
    }

    private static List<Item> generateFakeImageItemList(int num) {
        final List<Item> itemList = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            itemList.add(generateFakeImageItem(String.valueOf(i)));
        }
        return itemList;
    }

    private static Cursor generateCursorForFakeCategories(int num) {
        final MatrixCursor cursor = new MatrixCursor(AlbumColumns.ALL_PROJECTION);
        final int itemCount = 5;
        for (int i = 0; i < num; i++) {
            cursor.addRow(new Object[]{
                    FAKE_ID + String.valueOf(i),
                    System.currentTimeMillis(),
                    FAKE_CATEGORY_NAME + i,
                    FAKE_ID + String.valueOf(i),
                    itemCount + i,
                    PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
                    });
        }
        return cursor;
    }

    private static class TestItemsProvider extends ItemsProvider {

        private List<Item> mItemList = new ArrayList<>();
        private Cursor mCategoriesCursor;

        public TestItemsProvider(Context context) {
            super(context);
        }

        @Override
        public Cursor getAllItems(Category category, int limit, @Nullable String[] mimeType,
                @Nullable UserId userId) throws
                IllegalArgumentException, IllegalStateException {
            final MatrixCursor c = new MatrixCursor(MediaColumns.ALL_PROJECTION);

            for (Item item : mItemList) {
                c.addRow(new String[] {
                        item.getId(),
                        String.valueOf(item.getDateTaken()),
                        String.valueOf(item.getGenerationModified()),
                        item.getMimeType(),
                        String.valueOf(item.getSpecialFormat()),
                        "1", // size_bytes
                        null, // media_store_uri
                        String.valueOf(item.getDuration()),
                        "0", // is_favorite
                        "/storage/emulated/0/foo",
                        PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
                });
            }

            return c;
        }

        @Nullable
        public Cursor getAllCategories(@Nullable String[] mimeType, @Nullable UserId userId) {
            if (mCategoriesCursor != null) {
                return mCategoriesCursor;
            }

            final MatrixCursor c = new MatrixCursor(AlbumColumns.ALL_PROJECTION);
            return c;
        }

        public void setItems(@NonNull List<Item> itemList) {
            mItemList = itemList;
        }

        public void setCategoriesCursor(@NonNull Cursor cursor) {
            mCategoriesCursor = cursor;
        }
    }

    @Test
    public void testParseValuesFromPickImagesIntent_noMimeType_defaultFalse() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.hasMimeTypeFilters()).isFalse();
    }

    @Test
    public void testParseValuesFromGetContentIntent_noMimeType_defaultFalse() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.setType("*/*");

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.hasMimeTypeFilters()).isFalse();
    }

    @Test
    public void testParseValuesFromIntent_validMimeType() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.setType("image/png");

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.hasMimeTypeFilters()).isTrue();
    }

    @Test
    public void testParseValuesFromPickImagesIntent_validExtraMimeType() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/gif", "video/*"});

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.hasMimeTypeFilters()).isTrue();
    }

    @Test
    public void testParseValuesFromPickImagesIntent_invalidExtraMimeType() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"audio/*", "video/*"});

        try {
            mPickerViewModel.parseValuesFromIntent(intent);
            fail("Photo Picker does not support non-media mime type filters");
        } catch (IllegalArgumentException expected) {
            // Expected
        }
    }

    @Test
    public void testParseValuesFromPickImagesIntent_localOnlyTrue() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.isLocalOnly()).isTrue();
    }

    @Test
    public void testParseValuesFromPickImagesIntent_localOnlyFalse() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.isLocalOnly()).isFalse();
    }

    @Test
    public void testParseValuesFromGetContentIntent_validExtraMimeType() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/gif", "video/*"});

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.hasMimeTypeFilters()).isTrue();
    }

    @Test
    public void testParseValuesFromGetContentIntent_invalidExtraMimeType() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"audio/*", "video/*"});

        mPickerViewModel.parseValuesFromIntent(intent);

        // non-media filters for GET_CONTENT show all images and videos
        assertThat(mPickerViewModel.hasMimeTypeFilters()).isFalse();
    }

    @Test
    public void testParseValuesFromGetContentIntent_localOnlyTrue() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"video/*"});
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.isLocalOnly()).isTrue();
    }

    @Test
    public void testParseValuesFromGetContentIntent_localOnlyFalse() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"video/*"});

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.isLocalOnly()).isFalse();
    }

    @Test
    public void testShouldShowOnlyLocalFeatures() {
        mConfigStore.enableCloudMediaFeature();

        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        mPickerViewModel.parseValuesFromIntent(intent);
        assertThat(mPickerViewModel.isLocalOnly()).isTrue();
        assertThat(mPickerViewModel.shouldShowOnlyLocalFeatures()).isTrue();

        intent.removeExtra(Intent.EXTRA_LOCAL_ONLY);
        mPickerViewModel.parseValuesFromIntent(intent);
        assertThat(mPickerViewModel.isLocalOnly()).isFalse();
        assertThat(mPickerViewModel.shouldShowOnlyLocalFeatures()).isFalse();

        mConfigStore.disableCloudMediaFeature();
        assertThat(mPickerViewModel.shouldShowOnlyLocalFeatures()).isTrue();
    }
}
