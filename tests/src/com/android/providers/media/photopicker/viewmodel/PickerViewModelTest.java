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

import static com.android.providers.media.photopicker.data.model.Category.CATEGORY_DOWNLOADS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.ItemTest;
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

    private static final String FAKE_IMAGE_MIME_TYPE = "image/jpg";
    private static final String FAKE_CATEGORY_NAME = "testCategoryName";
    private static final String FAKE_ID = "5";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Mock
    private Application mApplication;

    private PickerViewModel mPickerViewModel;
    private TestItemsProvider mItemsProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final Context context = InstrumentationRegistry.getTargetContext();
        when(mApplication.getApplicationContext()).thenReturn(context);
        mPickerViewModel = new PickerViewModel(mApplication);
        mItemsProvider = new TestItemsProvider(context);
        mPickerViewModel.setItemsProvider(mItemsProvider);
    }

    @Test
    public void testGetItems_noItems() throws Exception {
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
    public void testGetItems_hasRecentItem() throws Exception {
        final int itemCount = 1;
        final List<Item> fakeItemList = generateFakeImageItemList(itemCount);
        final Item fakeItem = fakeItemList.get(0);
        mItemsProvider.setItems(generateFakeImageItemList(itemCount));
        mPickerViewModel.updateItems();
        // We use ForegroundThread to execute the loadItems in updateItems(), wait for the thread
        // idle
        ForegroundThread.waitForIdle();

        final List<Item> itemList = mPickerViewModel.getItems().getValue();

        // Original one item + 1 Recent item
        assertThat(itemList.size()).isEqualTo(itemCount + 1);
        // Check the first item is recent item
        final Item recentItem = itemList.get(0);
        assertThat(recentItem.isDate()).isTrue();
        assertThat(recentItem.getDateTaken()).isEqualTo(0);
        // Check the second item is fakeItem
        final Item firstPhotoItem = itemList.get(1);
        assertThat(firstPhotoItem.getId()).isEqualTo(fakeItem.getId());
    }

    @Test
    public void testGetItems_exceedMinCount_notSameDay_hasRecentItemAndOneDateItem()
            throws Exception {
        final int itemCount = 13;
        mItemsProvider.setItems(generateFakeImageItemList(itemCount));
        mPickerViewModel.updateItems();
        // We use ForegroundThread to execute the loadItems in updateItems(), wait for the thread
        // idle
        ForegroundThread.waitForIdle();

        final List<Item> itemList = mPickerViewModel.getItems().getValue();

        // Original item count + 1 Recent item + 1 date item
        assertThat(itemList.size()).isEqualTo(itemCount + 2);
        assertThat(itemList.get(0).isDate()).isTrue();
        assertThat(itemList.get(0).getDateTaken()).isEqualTo(0);
        // The index 13 is the next date header because the minimum item count in recent section is
        // 12
        assertThat(itemList.get(13).isDate()).isTrue();
        assertThat(itemList.get(13).getDateTaken()).isNotEqualTo(0);
    }

    /**
     * Test that The total number in `Recent` may exceed the minimum count. If the photo items are
     * taken on same day, they should not be split apart.
     */
    @Test
    public void testGetItems_exceedMinCount_sameDay_hasRecentItemNoDateItem() throws Exception {
        final int originalItemCount = 12;
        final String lastItemId = "13";
        final List<Item> fakeItemList = generateFakeImageItemList(originalItemCount);
        final long dateTakenMs = fakeItemList.get(originalItemCount - 1).getDateTaken();
        final long generationModified = 1L;
        final Item lastItem = ItemTest.generateItem(lastItemId, FAKE_IMAGE_MIME_TYPE,
                dateTakenMs, generationModified, /* duration= */ 1000L);
        fakeItemList.add(lastItem);
        final int itemCount = fakeItemList.size();
        mItemsProvider.setItems(fakeItemList);
        mPickerViewModel.updateItems();
        // We use ForegroundThread to execute the loadItems in updateItems(), wait for the thread
        // idle
        ForegroundThread.waitForIdle();

        final List<Item> itemList = mPickerViewModel.getItems().getValue();

        // Original item count + 1 new Recent item
        assertThat(itemList.size()).isEqualTo(itemCount + 1);
        assertThat(itemList.get(0).isDate()).isTrue();
        assertThat(itemList.get(0).getDateTaken()).isEqualTo(0);
    }

    @Test
    public void testGetCategoryItems() throws Exception {
        final int itemCount = 3;
        mItemsProvider.setItems(generateFakeImageItemList(itemCount));
        mPickerViewModel.updateCategoryItems(CATEGORY_DOWNLOADS);
        // We use ForegroundThread to execute the loadItems in updateCategoryItems(), wait for the
        // thread idle
        ForegroundThread.waitForIdle();

        final List<Item> itemList = mPickerViewModel.getCategoryItems(
                CATEGORY_DOWNLOADS).getValue();

        // Original item count + 3 date items
        assertThat(itemList.size()).isEqualTo(itemCount + 3);
        // Test the first item is date item
        final Item firstDateItem = itemList.get(0);
        assertThat(firstDateItem.isDate()).isTrue();
        assertThat(firstDateItem.getDateTaken()).isNotEqualTo(0);
        // Test the third item is date item and the dateTaken is larger than previous date item
        final Item secondDateItem = itemList.get(2);
        assertThat(secondDateItem.isDate()).isTrue();
        assertThat(secondDateItem.getDateTaken()).isGreaterThan(firstDateItem.getDateTaken());
        // Test the fifth item is date item and the dateTaken is larger than previous date item
        final Item thirdDateItem = itemList.get(4);
        assertThat(thirdDateItem.isDate()).isTrue();
        assertThat(thirdDateItem.getDateTaken()).isGreaterThan(secondDateItem.getDateTaken());
    }

    @Test
    public void testGetCategoryItems_dataIsUpdated() throws Exception {
        final int itemCount = 3;
        mItemsProvider.setItems(generateFakeImageItemList(itemCount));
        mPickerViewModel.updateCategoryItems(CATEGORY_DOWNLOADS);
        // We use ForegroundThread to execute the loadItems in updateCategoryItems(), wait for the
        // thread idle
        ForegroundThread.waitForIdle();

        final List<Item> itemList = mPickerViewModel.getCategoryItems(
                CATEGORY_DOWNLOADS).getValue();

        // Original item count + 3 date items
        assertThat(itemList.size()).isEqualTo(itemCount + 3);

        final int updatedItemCount = 5;
        mItemsProvider.setItems(generateFakeImageItemList(updatedItemCount));

        // trigger updateCategoryItems in getCategoryItems first and wait the idle
        mPickerViewModel.getCategoryItems(CATEGORY_DOWNLOADS).getValue();

        // We use ForegroundThread to execute the loadItems in updateCategoryItems(), wait for the
        // thread idle
        ForegroundThread.waitForIdle();

        // Get the result again to check the result is as expected
        final List<Item> updatedItemList = mPickerViewModel.getCategoryItems(
                CATEGORY_DOWNLOADS).getValue();

        // Original item count + 5 date items
        assertThat(updatedItemList.size()).isEqualTo(updatedItemCount + 5);
    }

    @Test
    public void testGetCategories() throws Exception {
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
            assertThat(firstCategory.getCategoryType()).isEqualTo(
                    fakeFirstCategory.getCategoryType());
            assertThat(firstCategory.getCategoryName(/* context= */ null)).isEqualTo(
                    fakeFirstCategory.getCategoryName(/* context= */ null));
            assertThat(firstCategory.getItemCount()).isEqualTo(fakeFirstCategory.getItemCount());
            assertThat(firstCategory.getCoverUri()).isEqualTo(fakeFirstCategory.getCoverUri());
            // Verify the second category
            final Category secondCategory = categoryList.get(1);
            assertThat(secondCategory.getCategoryType()).isEqualTo(
                    fakeSecondCategory.getCategoryType());
            assertThat(secondCategory.getCategoryName(/* context= */ null)).isEqualTo(
                    fakeSecondCategory.getCategoryName(/* context= */ null));
            assertThat(secondCategory.getItemCount()).isEqualTo(fakeSecondCategory.getItemCount());
            assertThat(secondCategory.getCoverUri()).isEqualTo(fakeSecondCategory.getCoverUri());
        }
    }


    private static Item generateFakeImageItem(String id) {
        final long dateTakenMs = System.currentTimeMillis() + Long.parseLong(id)
                * DateUtils.DAY_IN_MILLIS;
        final long generationModified = 1L;

        return ItemTest.generateItem(id, FAKE_IMAGE_MIME_TYPE, dateTakenMs, generationModified,
                /* duration= */ 1000L);
    }

    private static List<Item> generateFakeImageItemList(int num) {
        final List<Item> itemList = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            itemList.add(generateFakeImageItem(String.valueOf(i)));
        }
        return itemList;
    }

    private static Cursor generateCursorForFakeCategories(int num) {
        final MatrixCursor cursor = new MatrixCursor(Category.CategoryColumns.getAllColumns());
        final int itemCount = 5;
        for (int i = 0; i < num; i++) {
            cursor.addRow(new Object[]{
                    FAKE_CATEGORY_NAME + i,
                    FAKE_ID + String.valueOf(i),
                    itemCount + i,
                    CATEGORY_DOWNLOADS});
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
        public Cursor getItems(@Nullable @Category.CategoryType String category, int offset,
                int limit, @Nullable String mimeType, @Nullable UserId userId) throws
                IllegalArgumentException, IllegalStateException {
            final String[] columns = Item.ItemColumns.ALL_COLUMNS;
            final MatrixCursor c = new MatrixCursor(columns);

            for (Item item : mItemList) {
                c.addRow(new String[] {
                        item.getId(),
                        item.getMimeType(),
                        String.valueOf(item.getDateTaken()),
                        String.valueOf(item.getDateTaken()),
                        String.valueOf(item.getGenerationModified()),
                        String.valueOf(item.getDuration()),
                });
            }

            return c;
        }

        @Nullable
        public Cursor getCategories(@Nullable String mimeType, @Nullable UserId userId) {
            if (mCategoriesCursor != null) {
                return mCategoriesCursor;
            }

            final MatrixCursor c = new MatrixCursor(Category.CategoryColumns.getAllColumns());
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
    public void testParseValuesFromIntent_noMimeType_defaultFalse() {
        final Intent intent = new Intent();

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.hasMimeTypeFilter()).isFalse();
    }

    @Test
    public void testParseValuesFromIntent_validMimeType() {
        final Intent intent = new Intent();
        intent.setType("image/png");

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.hasMimeTypeFilter()).isTrue();
    }

    @Test
    public void testParseValuesFromIntent_ignoreInvalidMimeType() {
        final Intent intent = new Intent();
        intent.setType("audio/*");

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.hasMimeTypeFilter()).isFalse();
    }
}
