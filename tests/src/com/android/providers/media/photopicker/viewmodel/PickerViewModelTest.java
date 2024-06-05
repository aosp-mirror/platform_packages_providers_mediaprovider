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
import static android.provider.CloudMediaProviderContract.MediaColumns.AUTHORITY;
import static android.provider.CloudMediaProviderContract.MediaColumns.DATA;
import static android.provider.CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS;
import static android.provider.CloudMediaProviderContract.MediaColumns.DURATION_MILLIS;
import static android.provider.CloudMediaProviderContract.MediaColumns.HEIGHT;
import static android.provider.CloudMediaProviderContract.MediaColumns.ID;
import static android.provider.CloudMediaProviderContract.MediaColumns.IS_FAVORITE;
import static android.provider.CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI;
import static android.provider.CloudMediaProviderContract.MediaColumns.MIME_TYPE;
import static android.provider.CloudMediaProviderContract.MediaColumns.ORIENTATION;
import static android.provider.CloudMediaProviderContract.MediaColumns.SIZE_BYTES;
import static android.provider.CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION;
import static android.provider.CloudMediaProviderContract.MediaColumns.SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaColumns.WIDTH;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.providers.media.PickerUriResolver.REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI;
import static com.android.providers.media.photopicker.data.model.Item.ROW_ID;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_CLEAR_AND_UPDATE_LIST;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_VIEW_CREATED;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.test.filters.SdkSuppress;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.photopicker.DataLoaderThread;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.PaginationParameters;
import com.android.providers.media.photopicker.data.PickerResult;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.UserManagerState;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.ModelTestUtils;
import com.android.providers.media.photopicker.data.model.RefreshRequest;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.espresso.PhotoPickerBaseTest;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RunWith(Parameterized.class)
public class PickerViewModelTest {
    private static final String FAKE_CATEGORY_NAME = "testCategoryName";
    private static final String FAKE_ID = "5";
    private static final Context sTargetContext = getInstrumentation().getTargetContext();
    private static final String TEST_PACKAGE_NAME = "com.android.providers.media.tests";
    private static final String CMP_AUTHORITY = "authority";
    private static final String CMP_ACCOUNT_NAME = "account_name";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Mock
    private Application mApplication;

    private PickerViewModel mPickerViewModel;
    private TestItemsProvider mItemsProvider;
    private TestConfigStore mConfigStore;
    private BannerManager mBannerManager;
    private BannerController mBannerController;
    @Parameterized.Parameter(0)
    public boolean isPrivateSpaceEnabled;

    /**
     * Parametrize values for {@code isPrivateSpaceEnabled} to run all the tests twice once with
     * private space flag enabled and once with it disabled.
     */
    @Parameterized.Parameters(name = "privateSpaceEnabled={0}")
    public static Iterable<?> data() {
        return Lists.newArrayList(true, false);
    }

    public PickerViewModelTest() {
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mApplication.getApplicationContext()).thenReturn(sTargetContext);
        mConfigStore = new TestConfigStore();
        mConfigStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(TEST_PACKAGE_NAME);
        mConfigStore.enablePickerChoiceManagedSelectionEnabled();
        if (isPrivateSpaceEnabled) {
            mConfigStore.enablePrivateSpaceInPhotoPicker();
        } else {
            mConfigStore.disablePrivateSpaceInPhotoPicker();
        }

        getInstrumentation().runOnMainSync(() -> {
            mPickerViewModel = new PickerViewModel(mApplication) {
                @Override
                protected void initConfigStore() {
                    setConfigStore(mConfigStore);
                }
            };
        });
        mItemsProvider = new TestItemsProvider(sTargetContext);
        mPickerViewModel.setItemsProvider(mItemsProvider);

        // set current user profile and banner manager
        if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS()) {
            final UserManagerState userManagerState = mock(UserManagerState.class);
            when(userManagerState.getCurrentUserProfileId()).thenReturn(UserId.CURRENT_USER);
            mPickerViewModel.setUserManagerState(userManagerState);
            mBannerManager = BannerTestUtils.getTestCloudBannerManager(
                    sTargetContext, userManagerState, mConfigStore);
        } else {
            final UserIdManager userIdManager = mock(UserIdManager.class);
            when(userIdManager.getCurrentUserProfileId()).thenReturn(UserId.CURRENT_USER);
            mPickerViewModel.setUserIdManager(userIdManager);
            mBannerManager = BannerTestUtils.getTestCloudBannerManager(
                    sTargetContext, userIdManager, mConfigStore);
        }

        mPickerViewModel.setBannerManager(mBannerManager);

        // Set default banner manager values
        mBannerController = mBannerManager.getBannerControllersPerUser().get(
                UserId.CURRENT_USER.getIdentifier());
        assertNotNull(mBannerController);
        mBannerController.onChangeCloudMediaInfo(
                /* cmpAuthority= */ null, /* cmpAccountName= */ null);
        mBannerManager.maybeInitialiseAndSetBannersForCurrentUser();
    }

    @Test
    public void testGetItems_noItems() {
        final int itemCount = 0;
        mItemsProvider.setItems(generateFakeImageItemList(itemCount));
        mPickerViewModel.getPaginatedItemsForAction(
                ACTION_CLEAR_AND_UPDATE_LIST, null);
        // We use DataLoader thread to execute the loadItems in updateItems(), wait for the thread
        // idle
        DataLoaderThread.waitForIdle();

        final List<Item> itemList = Objects.requireNonNull(
                mPickerViewModel.getPaginatedItemsForAction(
                        ACTION_VIEW_CREATED,
                        new PaginationParameters()).getValue()).getItems();

        // No date headers, the size should be 0
        assertThat(itemList.size()).isEqualTo(itemCount);
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
            // We use DataLoaderThread to execute the loadCategories in updateCategories(), wait for
            // the thread idle
            DataLoaderThread.waitForIdle();

            final List<Category> categoryList = mPickerViewModel.getCategories().getValue();

            assertThat(categoryList.size()).isEqualTo(categoryCount);
            // Verify the first category
            final Category firstCategory = categoryList.get(0);
            assertThat(firstCategory.getDisplayName(sTargetContext)).isEqualTo(
                    fakeFirstCategory.getDisplayName(sTargetContext));
            assertThat(firstCategory.getItemCount()).isEqualTo(fakeFirstCategory.getItemCount());
            assertThat(firstCategory.getCoverUri()).isEqualTo(fakeFirstCategory.getCoverUri());
            // Verify the second category
            final Category secondCategory = categoryList.get(1);
            assertThat(secondCategory.getDisplayName(sTargetContext)).isEqualTo(
                    fakeSecondCategory.getDisplayName(sTargetContext));
            assertThat(secondCategory.getItemCount()).isEqualTo(fakeSecondCategory.getItemCount());
            assertThat(secondCategory.getCoverUri()).isEqualTo(fakeSecondCategory.getCoverUri());
        }
    }

    @Test
    public void test_getItems_correctItemsReturned() {
        final int numberOfTestItems = 4;
        final List<Item> expectedItems = generateFakeImageItemList(numberOfTestItems);
        mItemsProvider.setItems(expectedItems);

        LiveData<PickerViewModel.PaginatedItemsResult> testItems =
                mPickerViewModel.getPaginatedItemsForAction(
                        ACTION_VIEW_CREATED,
                        new PaginationParameters());
        DataLoaderThread.waitForIdle();

        assertThat(testItems).isNotNull();
        assertThat(testItems.getValue()).isNotNull();
        assertThat(testItems.getValue().getItems().size()).isEqualTo(numberOfTestItems);

        for (int itr = 0; itr < numberOfTestItems; itr++) {
            // Assert that all test and expected items are equal.
            assertThat(testItems.getValue().getItems().get(itr).compareTo(
                    expectedItems.get(itr))).isEqualTo(0);
        }
    }

    @SdkSuppress(minSdkVersion = 34, codeName = "UpsideDownCake")
    @Test
    public void test_getRemainingPreGrantedItems_correctItemsLoaded() {
        // Enable managed selection for this test.
        Intent intent = new Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP);
        intent.putExtra(Intent.EXTRA_UID, 0);
        mPickerViewModel.parseValuesFromIntent(intent);

        final int numberOfTestItems = 4;
        final List<Item> expectedItems = generateFakeImageItemList(numberOfTestItems);
        for (Item item : expectedItems) {
            item.setPreGranted();
        }
        mItemsProvider.setItems(expectedItems);
        List<Uri> preGrantedItems = List.of(expectedItems.get(0).getContentUri(),
                expectedItems.get(1).getContentUri(),
                expectedItems.get(2).getContentUri());
        Selection selection = mPickerViewModel.getSelection();
        // Add 3 item ids is preGranted set.
        selection.setPreGrantedItems(preGrantedItems);

        // adding 1 item in selection item set.
        selection.addSelectedItem(expectedItems.get(1));

        // revoking grant for 1 id.
        selection.removeSelectedItem(expectedItems.get(0));

        // since only one item is added in selection set, the size should be one.
        assertThat(selection.getSelectedItems().size()).isEqualTo(1);

        // Since out of 3 one grant was removed, so there would be one item loaded when remaining
        // grants are loaded.
        mPickerViewModel.getRemainingPreGrantedItems();
        DataLoaderThread.waitForIdle();

        // Now the selection set should have 2 items.
        assertThat(selection.getSelectedItems().size()).isEqualTo(2);
    }

    @SdkSuppress(minSdkVersion = 34, codeName = "UpsideDownCake")
    @Test
    public void test_deselectPreGrantedItem_correctRevokeMapMaintained() {
        // Enable managed selection for this test.
        Intent intent = new Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP);
        intent.putExtra(Intent.EXTRA_UID, 0);
        mPickerViewModel.parseValuesFromIntent(intent);

        final int numberOfTestItems = 4;
        final List<Item> expectedItems = generateFakeImageItemList(numberOfTestItems);
        for (Item item : expectedItems) {
            item.setPreGranted();
        }
        mItemsProvider.setItems(expectedItems);

        List<Uri> preGrantedItems = List.of(
                expectedItems.get(0).getContentUri(),
                expectedItems.get(1).getContentUri(),
                expectedItems.get(2).getContentUri());


        Selection selection = mPickerViewModel.getSelection();
        // Add 3 item ids is preGranted set.
        selection.setPreGrantedItems(preGrantedItems);

        // adding 2 items in selection item set.
        selection.addSelectedItem(expectedItems.get(0));
        selection.addSelectedItem(expectedItems.get(1));

        // revoking grant for the 0th item id.
        selection.removeSelectedItem(expectedItems.get(0));

        // since only one item is added in selection set, the size should be one.
        assertThat(selection.getSelectedItems().size()).isEqualTo(1);

        // verify revoked item is present in the items to be revoked set.
        Set<Item> itemsToBeRevoked = selection.getDeselectedItemsToBeRevoked();
        assertThat(itemsToBeRevoked.size()).isEqualTo(1);
        assertThat(itemsToBeRevoked.contains(expectedItems.get(0))).isTrue();

        Set<Uri> itemUrisToBeRevoked = selection.getDeselectedUrisToBeRevoked();
        assertThat(itemUrisToBeRevoked.size()).isEqualTo(1);
        assertThat(itemUrisToBeRevoked.contains(expectedItems.get(0).getContentUri())).isTrue();
    }

    @Test
    public void test_initialisePreSelectionItems_correctItemsLoaded() {
        // Set the intent action as PICK_IMAGES
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        Bundle extras = new Bundle();
        extras.putInt(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        intent.putExtras(extras);

        mPickerViewModel.parseValuesFromIntent(intent);

        // generate test items
        final int numberOfTestItems = 4;
        final List<Item> expectedItems = generateFakeImageItemList(numberOfTestItems);


        // Mock the test items to return the required URI and id when used.
        final List<Item> mockedExpectedItems = new ArrayList<>();
        for (int i = 0; i < expectedItems.size(); i++) {
            Item item = mock(Item.class);
            when(item.getContentUri()).thenReturn(ItemsProvider.getItemsUri(
                    expectedItems.get(i).getId(),
                    PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY,
                    UserId.CURRENT_USER));
            when(item.getId()).thenReturn(expectedItems.get(i).getId());
            mockedExpectedItems.add(item);
        }
        mItemsProvider.setItems(mockedExpectedItems);

        // generate a list of input pre-selected picker URI and add them to test intent extras.
        ArrayList<Uri> preGrantedPickerUris = new ArrayList<>();
        for (int i = 0; i < expectedItems.size(); i++) {
            preGrantedPickerUris.add(
                    PickerResult.getPickerUrisForItems(MediaStore.ACTION_PICK_IMAGES,
                            List.of(mockedExpectedItems.get(i))).get(0));
        }
        Bundle intentExtras = new Bundle();
        intentExtras.putParcelableArrayList(MediaStore.EXTRA_PICKER_PRE_SELECTION_URIS,
                preGrantedPickerUris);

        Selection selection = mPickerViewModel.getSelection();
        // Since no item has been selected and no pre-granted URIs have been loaded, thus the size
        // of selection should be 0.
        assertThat(selection.getSelectedItems().size()).isEqualTo(0);

        DataLoaderThread.waitForIdle();

        // Initialise pre-granted items for selection.
        mPickerViewModel.initialisePreGrantsIfNecessary(selection, intentExtras,
                /* mimeTypeFilters */ null);
        DataLoaderThread.waitForIdle();

        // after initialization the items should have been added to selection.
        assertThat(selection.getPreGrantedUris()).isNotNull();
        assertThat(selection.getPreGrantedUris().size()).isEqualTo(4);
        assertThat(mPickerViewModel.getSelection().getSelectedItems().size()).isEqualTo(4);
    }


    @Test
    public void test_preSelectionItemsExceedMaxLimit_initialisationOfItemsFails() {
        // Generate a list of test uris, the size being 2 uris more than the max number of URIs
        // accepted.
        String testUriPrefix = "content://media/picker/0/com.test.package/media/";
        int numberOfPreselectedUris = MediaStore.getPickImagesMaxLimit() + 2;
        ArrayList<Uri> testUrisAsString = new ArrayList<>();
        for (int i = 0; i < numberOfPreselectedUris; i++) {
            testUrisAsString.add(Uri.parse(testUriPrefix + String.valueOf(i)));
        }

        // set up the intent extras to contain the test uris. Also, parse a test PICK_IMAGES intent
        // to ensure that PickerViewModel works in PICK_IMAGES action mode.
        Bundle intentExtras = new Bundle();
        intentExtras.putInt(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        intentExtras.putParcelableArrayList(MediaStore.EXTRA_PICKER_PRE_SELECTION_URIS,
                testUrisAsString);
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtras(intentExtras);
        mPickerViewModel.parseValuesFromIntent(intent);

        try {
            mPickerViewModel.initialisePreGrantsIfNecessary(null, intentExtras, null);
            fail("The initialisation of items should have failed since the number of pre-selected"
                    + "items exceeds the max limit");
        } catch (IllegalArgumentException illegalArgumentException) {
            assertThat(illegalArgumentException.getMessage()).isEqualTo(
                    "The number of URIs exceed the maximum allowed limit: "
                            + MediaStore.getPickImagesMaxLimit());
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
        public Cursor getAllItems(Category category,
                PaginationParameters paginationParameters, @Nullable String[] mimeType,
                @Nullable UserId userId,
                @Nullable CancellationSignal cancellationSignal) throws
                IllegalArgumentException, IllegalStateException {
            final String[] all_projection = new String[]{
                    ID,
                    // This field is unique to the cursor received by the pickerVIewModel.
                    // It is not a part of cloud provider contract.
                    ROW_ID,
                    DATE_TAKEN_MILLIS,
                    SYNC_GENERATION,
                    MIME_TYPE,
                    STANDARD_MIME_TYPE_EXTENSION,
                    SIZE_BYTES,
                    MEDIA_STORE_URI,
                    DURATION_MILLIS,
                    IS_FAVORITE,
                    WIDTH,
                    HEIGHT,
                    ORIENTATION,
                    DATA,
                    AUTHORITY,
            };
            final MatrixCursor c = new MatrixCursor(all_projection);

            int itr = 1;
            for (Item item : mItemList) {
                c.addRow(new String[]{
                        item.getId(),
                        String.valueOf(itr),
                        String.valueOf(item.getDateTaken()),
                        String.valueOf(item.getGenerationModified()),
                        item.getMimeType(),
                        String.valueOf(item.getSpecialFormat()),
                        "1", // size_bytes
                        null, // media_store_uri
                        String.valueOf(item.getDuration()),
                        "0", // is_favorite
                        String.valueOf(800), // width
                        String.valueOf(500), // height
                        String.valueOf(0), // orientation
                        "/storage/emulated/0/foo",
                        PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
                });
                itr++;
            }

            return c;
        }

        @Override
        public Cursor getLocalItems(Category category,
                PaginationParameters paginationParameters, @Nullable String[] mimeType,
                @Nullable UserId userId,
                @Nullable CancellationSignal cancellationSignal) throws
                IllegalArgumentException, IllegalStateException {
            final String[] all_projection = new String[]{
                    ID,
                    // This field is unique to the cursor received by the pickerVIewModel.
                    // It is not a part of cloud provider contract.
                    ROW_ID,
                    DATE_TAKEN_MILLIS,
                    SYNC_GENERATION,
                    MIME_TYPE,
                    STANDARD_MIME_TYPE_EXTENSION,
                    SIZE_BYTES,
                    MEDIA_STORE_URI,
                    DURATION_MILLIS,
                    IS_FAVORITE,
                    WIDTH,
                    HEIGHT,
                    ORIENTATION,
                    DATA,
                    AUTHORITY,
            };
            final MatrixCursor c = new MatrixCursor(all_projection);

            int itr = 1;
            for (Item item : mItemList) {
                c.addRow(new String[]{
                        item.getId(),
                        String.valueOf(itr),
                        String.valueOf(item.getDateTaken()),
                        String.valueOf(item.getGenerationModified()),
                        item.getMimeType(),
                        String.valueOf(item.getSpecialFormat()),
                        "1", // size_bytes
                        null, // media_store_uri
                        String.valueOf(item.getDuration()),
                        "0", // is_favorite
                        String.valueOf(800), // width
                        String.valueOf(500), // height
                        String.valueOf(0), // orientation
                        "/storage/emulated/0/foo",
                        PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
                });
                itr++;
            }

            return c;
        }

        @Override
        public Cursor getItemsForPreselectedMedia(Category category, @NonNull List<Uri>
                preselectedUris, @Nullable String[] mimeTypes, @Nullable UserId userId,
                boolean isLocalOnly, int callingPackageUid, boolean shouldScreenSelectionUris,
                @Nullable CancellationSignal cancellationSignal) throws IllegalArgumentException {
            final String[] all_projection = new String[]{
                    ID,
                    // This field is unique to the cursor received by the pickerVIewModel.
                    // It is not a part of cloud provider contract.
                    ROW_ID,
                    DATE_TAKEN_MILLIS,
                    SYNC_GENERATION,
                    MIME_TYPE,
                    STANDARD_MIME_TYPE_EXTENSION,
                    SIZE_BYTES,
                    MEDIA_STORE_URI,
                    DURATION_MILLIS,
                    IS_FAVORITE,
                    WIDTH,
                    HEIGHT,
                    ORIENTATION,
                    DATA,
                    AUTHORITY,
            };
            final MatrixCursor c = new MatrixCursor(all_projection);
            List<String> preSelectedIds = preselectedUris.stream().map(
                    Uri::getLastPathSegment).collect(Collectors.toList());
            int itr = 1;
            for (Item item : mItemList) {
                if (preSelectedIds.contains(item.getId())) {
                    c.addRow(new String[]{
                            item.getId(),
                            String.valueOf(itr),
                            String.valueOf(item.getDateTaken()),
                            String.valueOf(item.getGenerationModified()),
                            item.getMimeType(),
                            String.valueOf(item.getSpecialFormat()),
                            "1", // size_bytes
                            null, // media_store_uri
                            String.valueOf(item.getDuration()),
                            "0", // is_favorite
                            String.valueOf(800), // width
                            String.valueOf(500), // height
                            String.valueOf(0), // orientation
                            "/storage/emulated/0/foo",
                            PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
                    });
                    itr++;
                }
            }
            return c;

        }

        @Nullable
        public Cursor getAllCategories(@Nullable String[] mimeType, @Nullable UserId userId,
                @Nullable CancellationSignal cancellationSignal) {
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
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/gif", "video/*"});

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.hasMimeTypeFilters()).isTrue();
    }

    @Test
    public void testParseValuesFromPickImagesIntent_invalidExtraMimeType() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});

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
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/gif", "video/*"});

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.hasMimeTypeFilters()).isTrue();
    }

    @Test
    public void testParseValuesFromGetContentIntent_invalidExtraMimeType() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});

        mPickerViewModel.parseValuesFromIntent(intent);

        // non-media filters for GET_CONTENT show all images and videos
        assertThat(mPickerViewModel.hasMimeTypeFilters()).isFalse();
    }

    @Test
    public void testParseValuesFromGetContentIntent_localOnlyTrue() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*"});
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.isLocalOnly()).isTrue();
    }

    @Test
    public void testParseValuesFromGetContentIntent_localOnlyFalse() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*"});

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.isLocalOnly()).isFalse();
    }

    @Test
    public void testParseValuesFromPickImagesIntent_launchPickerInPhotosTab() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB, MediaStore.PICK_IMAGES_TAB_IMAGES);

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.getPickerLaunchTab()).isEqualTo(
                MediaStore.PICK_IMAGES_TAB_IMAGES);
    }

    @Test
    public void testParseValuesFromPickImagesIntent_launchPickerInAlbumsTab() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB, MediaStore.PICK_IMAGES_TAB_ALBUMS);

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(mPickerViewModel.getPickerLaunchTab()).isEqualTo(
                MediaStore.PICK_IMAGES_TAB_ALBUMS);
    }

    @Test
    public void testParseValuesFromPickImagesIntent_launchPickerWithIncorrectTabOption() {
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB, 2);

        try {
            mPickerViewModel.parseValuesFromIntent(intent);
            fail("Incorrect value passed for the picker launch tab option in the intent");
        } catch (IllegalArgumentException expected) {
            // Expected
        }
    }

    @Test
    public void testParseValuesFromPickImagesIntent_validAccentColor() {
        long accentColor = 0xFFFF0000;
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, accentColor);

        mPickerViewModel.parseValuesFromIntent(intent);

        assertThat(
                mPickerViewModel.getPickerAccentColorParameters().getPickerAccentColor()).isEqualTo(
                        Color.parseColor(String.format("#%06X", (0xFFFFFF & accentColor))));
        assertThat(
                mPickerViewModel.getPickerAccentColorParameters().isCustomPickerColorSet())
                .isTrue();
    }

    @Test
    public void testParseValuesFromPickImagesIntent_invalidAccentColor() {
        long accentColor = 0xFF6;

        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, accentColor);

        try {
            mPickerViewModel.parseValuesFromIntent(intent);
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    public void testParseValuesFromGetContentIntent_extraPickerLaunchTab() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB, MediaStore.PICK_IMAGES_TAB_ALBUMS);

        mPickerViewModel.parseValuesFromIntent(intent);

        // GET_CONTENT doesn't support this option. Launch tab will always default to photos
        assertThat(mPickerViewModel.getPickerLaunchTab()).isEqualTo(
                MediaStore.PICK_IMAGES_TAB_IMAGES);
    }

    @Test
    public void testParseValuesFromPickImagesIntent_accentColorsWithUnacceptedBrightness() {
        // Accent color brightness is less than the accepted brightness
        long[] accentColors = new long[] {
                0xFF000000, // black
                0xFFFFFFFF, // white
                0xFFFFFFF0  // variant of white
        };

        for (long accentColor: accentColors) {
            final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, accentColor);

            mPickerViewModel.parseValuesFromIntent(intent);

            // Fall back to the android theme
            assertWithMessage("Input accent color " + accentColor
                    + " does not fall within accepted luminance range.")
                    .that(mPickerViewModel.getPickerAccentColorParameters().getPickerAccentColor())
                    .isEqualTo(-1);
            assertWithMessage("Custom picker color flag for input color "
                    + accentColor + " should be false but was true.")
                    .that(mPickerViewModel.getPickerAccentColorParameters()
                            .isCustomPickerColorSet()).isFalse();
        }
    }

    @Test
    public void testParseValuesFromGetContentIntent_accentColor() {
        long accentColor = 0xFFFF0000;
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, accentColor);

        mPickerViewModel.parseValuesFromIntent(intent);

        // GET_CONTENT doesn't support this option. Accent color parameter object is not
        // created.
        assertThat(mPickerViewModel.getPickerAccentColorParameters().getPickerAccentColor())
                .isEqualTo(-1);
        assertThat(mPickerViewModel.getPickerAccentColorParameters().isCustomPickerColorSet())
                .isFalse();
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

    @Test
    public void testRefreshUiNotifications() throws InterruptedException {
        final LiveData<RefreshRequest> shouldRefreshUi = mPickerViewModel.refreshUiLiveData();
        assertFalse(shouldRefreshUi.getValue().shouldRefreshPicker());

        final ContentResolver contentResolver = sTargetContext.getContentResolver();
        contentResolver.notifyChange(REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI, null);

        TimeUnit.MILLISECONDS.sleep(100);
        assertTrue(shouldRefreshUi.getValue().shouldRefreshPicker());

        mPickerViewModel.resetAllContentInCurrentProfile(false);
        assertFalse(shouldRefreshUi.getValue().shouldRefreshPicker());
    }

    @Test
    public void testDismissChooseAppBanner() {
        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, CMP_ACCOUNT_NAME);
        mBannerManager.maybeInitialiseAndSetBannersForCurrentUser();

        mBannerController.onChangeCloudMediaInfo(
                /* cmpAuthority= */ null, /* cmpAccountName= */ null);
        mBannerManager.maybeInitialiseAndSetBannersForCurrentUser();
        assertTrue(mBannerController.shouldShowChooseAppBanner());
        assertTrue(mPickerViewModel.shouldShowChooseAppBannerLiveData().getValue());

        getInstrumentation().runOnMainSync(() -> mPickerViewModel.onUserDismissedChooseAppBanner());
        assertFalse(mBannerController.shouldShowChooseAppBanner());
        assertFalse(mPickerViewModel.shouldShowChooseAppBannerLiveData().getValue());

        // Assert no change on dismiss when the banner is already hidden
        getInstrumentation().runOnMainSync(() -> mPickerViewModel.onUserDismissedChooseAppBanner());
        assertFalse(mBannerController.shouldShowChooseAppBanner());
        assertFalse(mPickerViewModel.shouldShowChooseAppBannerLiveData().getValue());
    }

    @Test
    public void testDismissCloudMediaAvailableBanner() {
        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, CMP_ACCOUNT_NAME);
        mBannerManager.maybeInitialiseAndSetBannersForCurrentUser();
        assertTrue(mBannerController.shouldShowCloudMediaAvailableBanner());
        assertTrue(mPickerViewModel.shouldShowCloudMediaAvailableBannerLiveData().getValue());

        getInstrumentation().runOnMainSync(() ->
                mPickerViewModel.onUserDismissedCloudMediaAvailableBanner());
        assertFalse(mBannerController.shouldShowCloudMediaAvailableBanner());
        assertFalse(mPickerViewModel.shouldShowCloudMediaAvailableBannerLiveData().getValue());

        // Assert no change on dismiss when the banner is already hidden
        getInstrumentation().runOnMainSync(() ->
                mPickerViewModel.onUserDismissedCloudMediaAvailableBanner());
        assertFalse(mBannerController.shouldShowCloudMediaAvailableBanner());
        assertFalse(mPickerViewModel.shouldShowCloudMediaAvailableBannerLiveData().getValue());
    }

    @Test
    public void testDismissAccountUpdatedBanner() {
        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, /* cmpAccountName= */ null);
        mBannerManager.maybeInitialiseAndSetBannersForCurrentUser();

        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, CMP_ACCOUNT_NAME);
        mBannerManager.maybeInitialiseAndSetBannersForCurrentUser();
        assertTrue(mBannerController.shouldShowAccountUpdatedBanner());
        assertTrue(mPickerViewModel.shouldShowAccountUpdatedBannerLiveData().getValue());

        getInstrumentation().runOnMainSync(() ->
                mPickerViewModel.onUserDismissedAccountUpdatedBanner());
        assertFalse(mBannerController.shouldShowAccountUpdatedBanner());
        assertFalse(mPickerViewModel.shouldShowAccountUpdatedBannerLiveData().getValue());

        // Assert no change on dismiss when the banner is already hidden
        getInstrumentation().runOnMainSync(() ->
                mPickerViewModel.onUserDismissedAccountUpdatedBanner());
        assertFalse(mBannerController.shouldShowAccountUpdatedBanner());
        assertFalse(mPickerViewModel.shouldShowAccountUpdatedBannerLiveData().getValue());
    }

    @Test
    public void testDismissChooseAccountBanner() {
        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, /* cmpAccountName= */ null);
        mBannerManager.maybeInitialiseAndSetBannersForCurrentUser();
        assertTrue(mBannerController.shouldShowChooseAccountBanner());
        assertTrue(mPickerViewModel.shouldShowChooseAccountBannerLiveData().getValue());

        getInstrumentation().runOnMainSync(() ->
                mPickerViewModel.onUserDismissedChooseAccountBanner());
        assertFalse(mBannerController.shouldShowChooseAccountBanner());
        assertFalse(mPickerViewModel.shouldShowChooseAccountBannerLiveData().getValue());

        // Assert no change on dismiss when the banner is already hidden
        getInstrumentation().runOnMainSync(() ->
                mPickerViewModel.onUserDismissedChooseAccountBanner());
        assertFalse(mBannerController.shouldShowChooseAccountBanner());
        assertFalse(mPickerViewModel.shouldShowChooseAccountBannerLiveData().getValue());
    }

    @Test
    public void testGetCloudMediaProviderAuthorityLiveData() {
        assertNull(mPickerViewModel.getCloudMediaProviderAuthorityLiveData().getValue());

        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, /* cmpAccountName= */ null);
        mBannerManager.maybeInitialiseAndSetBannersForCurrentUser();

        assertEquals(CMP_AUTHORITY,
                mPickerViewModel.getCloudMediaProviderAuthorityLiveData().getValue());
    }

    @Test
    public void testGetChooseCloudMediaAccountActivityIntent() {
        assertNull(mPickerViewModel.getChooseCloudMediaAccountActivityIntent());

        final Intent testIntent = new Intent();
        mBannerController.setChooseCloudMediaAccountActivityIntent(testIntent);
        mBannerManager.maybeInitialiseAndSetBannersForCurrentUser();

        assertEquals(testIntent,
                mPickerViewModel.getChooseCloudMediaAccountActivityIntent());
    }

    @Test
    public void testMainGridInitRequest() {
        ItemsProvider mockItemsProvider = spy(ItemsProvider.class);
        mPickerViewModel.setItemsProvider(mockItemsProvider);

        // Parse values from intent
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        mPickerViewModel.parseValuesFromIntent(intent);

        // Send an init request
        mPickerViewModel.maybeInitPhotoPickerData();

        // Check that the request was sent
        DataLoaderThread.waitForIdle();
        verify(mockItemsProvider, times(1)).initPhotoPickerData(any(), any(), eq(false), any());

        // Send an init request again
        mPickerViewModel.maybeInitPhotoPickerData();

        // Check that init request was NOT sent again
        DataLoaderThread.waitForIdle();
        verify(mockItemsProvider, times(1)).initPhotoPickerData(any(), any(), eq(false), any());
    }

    @SdkSuppress(minSdkVersion = 34, codeName = "UpsideDownCake")
    @Test
    public void testMainGridPickerChoiceInitRequest() {
        ItemsProvider mockItemsProvider = spy(ItemsProvider.class);
        mPickerViewModel.setItemsProvider(mockItemsProvider);

        // Parse values from intent
        Intent intent =  PhotoPickerBaseTest.getUserSelectImagesForAppIntent();
        mPickerViewModel.parseValuesFromIntent(intent);

        // Send an init request
        mPickerViewModel.maybeInitPhotoPickerData();

        // Check that a local-only init request was sent
        DataLoaderThread.waitForIdle();
        verify(mockItemsProvider, times(1)).initPhotoPickerData(any(), any(), eq(true), any());

        // Send an init request again
        mPickerViewModel.maybeInitPhotoPickerData();

        // Check that init request was NOT sent again
        DataLoaderThread.waitForIdle();
        verify(mockItemsProvider, times(1)).initPhotoPickerData(any(), any(), eq(true), any());
    }

    @Test
    public void testMainGridInitOnResetRequest() {
        ItemsProvider mockItemsProvider = spy(ItemsProvider.class);
        mPickerViewModel.setItemsProvider(mockItemsProvider);

        // Parse values from intent
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        mPickerViewModel.parseValuesFromIntent(intent);

        // Send an init request
        mPickerViewModel.maybeInitPhotoPickerData();

        // Check that a local-only init request was sent
        DataLoaderThread.waitForIdle();
        verify(mockItemsProvider, times(1)).initPhotoPickerData(any(), any(), eq(false), any());

        // Send an init request again
        mPickerViewModel.resetAllContentInCurrentProfile(true);

        // Check that init request was sent again
        DataLoaderThread.waitForIdle();
        verify(mockItemsProvider, times(2)).initPhotoPickerData(any(), any(), eq(false), any());
    }
}
