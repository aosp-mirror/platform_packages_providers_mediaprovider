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

import static android.content.Intent.ACTION_GET_CONTENT;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.MuteStatus;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.metrics.PhotoPickerUiEventLogger;
import com.android.providers.media.photopicker.util.DateTimeUtils;
import com.android.providers.media.photopicker.util.MimeFilterUtils;
import com.android.providers.media.util.ForegroundThread;

import java.util.ArrayList;
import java.util.List;

/**
 * PickerViewModel to store and handle data for PhotoPickerActivity.
 */
public class PickerViewModel extends AndroidViewModel {
    public static final String TAG = "PhotoPicker";

    private static final int RECENT_MINIMUM_COUNT = 12;

    private static final int INSTANCE_ID_MAX = 1 << 15;

    private final Selection mSelection;
    private final MuteStatus mMuteStatus;

    // TODO(b/193857982): We keep these four data sets now, we may need to find a way to reduce the
    // data set to reduce memories.
    // The list of Items with all photos and videos
    private MutableLiveData<List<Item>> mItemList;
    // The list of Items with all photos and videos in category
    private MutableLiveData<List<Item>> mCategoryItemList;
    // The list of categories.
    private MutableLiveData<List<Category>> mCategoryList;

    private ItemsProvider mItemsProvider;
    private UserIdManager mUserIdManager;

    private InstanceId mInstanceId;
    private PhotoPickerUiEventLogger mLogger;

    private String[] mMimeTypeFilters = null;
    private int mBottomSheetState;

    private Category mCurrentCategory;

    public PickerViewModel(@NonNull Application application) {
        super(application);
        final Context context = application.getApplicationContext();
        mItemsProvider = new ItemsProvider(context);
        mSelection = new Selection();
        mUserIdManager = UserIdManager.create(context);
        mMuteStatus = new MuteStatus();
        mInstanceId = new InstanceIdSequence(INSTANCE_ID_MAX).newInstanceId();
        mLogger = new PhotoPickerUiEventLogger();
    }

    @VisibleForTesting
    public void setItemsProvider(@NonNull ItemsProvider itemsProvider) {
        mItemsProvider = itemsProvider;
    }

    @VisibleForTesting
    public void setUserIdManager(@NonNull UserIdManager userIdManager) {
        mUserIdManager = userIdManager;
    }

    /**
     * @return {@link UserIdManager} for this context.
     */
    public UserIdManager getUserIdManager() {
        return mUserIdManager;
    }

    /**
     * @return {@code mSelection} that manages the selection
     */
    public Selection getSelection() {
        return mSelection;
    }


    /**
     * @return {@code mMuteStatus} that tracks the volume mute status of the video preview
     */
    public MuteStatus getMuteStatus() {
        return mMuteStatus;
    }

    /**
     * Reset to personal profile mode.
     */
    public void resetToPersonalProfile() {
        // 1. Clear Selected items
        mSelection.clearSelectedItems();
        // 2. Change profile to personal user
        mUserIdManager.setPersonalAsCurrentUserProfile();
        // 3. Update Item and Category lists
        updateItems();
        updateCategories();
    }

    /**
     * @return the list of Items with all photos and videos {@link #mItemList} on the device.
     */
    public LiveData<List<Item>> getItems() {
        if (mItemList == null) {
            updateItems();
        }
        return mItemList;
    }

    private List<Item> loadItems(Category category, UserId userId) {
        final List<Item> items = new ArrayList<>();

        try (Cursor cursor = mItemsProvider.getItems(category, /* offset */ 0,
                /* limit */ -1, mMimeTypeFilters, userId)) {
            if (cursor == null || cursor.getCount() == 0) {
                Log.d(TAG, "Didn't receive any items for " + category
                        + ", either cursor is null or cursor count is zero");
                return items;
            }

            // We only add the RECENT header on the PhotosTabFragment with CATEGORY_DEFAULT. In this
            // case, we call this method {loadItems} with null category. When the category is not
            // empty, we don't show the RECENT header.
            final boolean showRecent = category.isDefault();

            int recentSize = 0;
            long currentDateTaken = 0;

            if (showRecent) {
                // add Recent date header
                items.add(Item.createDateItem(0));
            }
            while (cursor.moveToNext()) {
                // TODO(b/188394433): Return userId in the cursor so that we do not need to pass it
                // here again.
                final Item item = Item.fromCursor(cursor, userId);
                final long dateTaken = item.getDateTaken();
                // the minimum count of items in recent is not reached
                if (showRecent && recentSize < RECENT_MINIMUM_COUNT) {
                    recentSize++;
                    currentDateTaken = dateTaken;
                }

                // The date taken of these two images are not on the
                // same day, add the new date header.
                if (!DateTimeUtils.isSameDate(currentDateTaken, dateTaken)) {
                    items.add(Item.createDateItem(dateTaken));
                    currentDateTaken = dateTaken;
                }
                items.add(item);
            }
        }

        Log.d(TAG, "Loaded " + items.size() + " items in " + category + " for user "
                + userId.toString());
        return items;
    }

    private void loadItemsAsync() {
        final UserId userId = mUserIdManager.getCurrentUserProfileId();
        ForegroundThread.getExecutor().execute(() -> {
                    mItemList.postValue(loadItems(Category.DEFAULT, userId));
        });
    }

    /**
     * Update the item List {@link #mItemList}
     */
    public void updateItems() {
        if (mItemList == null) {
            mItemList = new MutableLiveData<>();
        }
        loadItemsAsync();
    }

    /**
     * Get the list of all photos and videos with the specific {@code category} on the device.
     *
     * In our use case, we only keep the list of current category {@link #mCurrentCategory} in
     * {@link #mCategoryItemList}. If the {@code category} and {@link #mCurrentCategory} are
     * different, we will create the new LiveData to {@link #mCategoryItemList}.
     *
     * @param category the category we want to be queried
     * @return the list of all photos and videos with the specific {@code category}
     *         {@link #mCategoryItemList}
     */
    public LiveData<List<Item>> getCategoryItems(@NonNull Category category) {
        if (mCategoryItemList == null || !TextUtils.equals(mCurrentCategory.getId(),
                category.getId())) {
            mCategoryItemList = new MutableLiveData<>();
            mCurrentCategory = category;
        }
        updateCategoryItems();
        return mCategoryItemList;
    }

    private void loadCategoryItemsAsync() {
        final UserId userId = mUserIdManager.getCurrentUserProfileId();
        ForegroundThread.getExecutor().execute(() -> {
            mCategoryItemList.postValue(loadItems(mCurrentCategory, userId));
        });
    }

    /**
     * Update the item List with the {@link #mCurrentCategory} {@link #mCategoryItemList}
     *
     * @throws IllegalStateException category and category items is not initiated before calling
     *     this method
     */
    @VisibleForTesting
    public void updateCategoryItems() {
        if (mCategoryItemList == null || mCurrentCategory == null) {
            throw new IllegalStateException("mCurrentCategory and mCategoryItemList are not"
                    + " initiated. Please call getCategoryItems before calling this method");
        }
        loadCategoryItemsAsync();
    }

    /**
     * @return the list of Categories {@link #mCategoryList}
     */
    public LiveData<List<Category>> getCategories() {
        if (mCategoryList == null) {
            updateCategories();
        }
        return mCategoryList;
    }

    private List<Category> loadCategories(UserId userId) {
        final List<Category> categoryList = new ArrayList<>();
        try (final Cursor cursor = mItemsProvider.getCategories(mMimeTypeFilters, userId)) {
            if (cursor == null || cursor.getCount() == 0) {
                Log.d(TAG, "Didn't receive any categories, either cursor is null or"
                        + " cursor count is zero");
                return categoryList;
            }

            while (cursor.moveToNext()) {
                final Category category = Category.fromCursor(cursor, userId);
                categoryList.add(category);
            }

            Log.d(TAG,
                    "Loaded " + categoryList.size() + " categories for user " + userId.toString());
        }
        return categoryList;
    }

    private void loadCategoriesAsync() {
        final UserId userId = mUserIdManager.getCurrentUserProfileId();
        ForegroundThread.getExecutor().execute(() -> {
            mCategoryList.postValue(loadCategories(userId));
        });
    }

    /**
     * Update the category List {@link #mCategoryList}
     */
    public void updateCategories() {
        if (mCategoryList == null) {
            mCategoryList = new MutableLiveData<>();
        }
        loadCategoriesAsync();
    }

    /**
     * Return whether the {@link #mMimeTypeFilters} is {@code null} or not
     */
    public boolean hasMimeTypeFilters() {
        return mMimeTypeFilters != null && mMimeTypeFilters.length > 0;
    }

    /**
     * Parse values from {@code intent} and set corresponding fields
     */
    public void parseValuesFromIntent(Intent intent) throws IllegalArgumentException {
        mUserIdManager.setIntentAndCheckRestrictions(intent);

        mMimeTypeFilters = MimeFilterUtils.getMimeTypeFilters(intent);

        mSelection.parseSelectionValuesFromIntent(intent);
    }

    /**
     * Set BottomSheet state
     */
    public void setBottomSheetState(int state) {
        mBottomSheetState = state;
    }

    /**
     * @return BottomSheet state
     */
    public int getBottomSheetState() {
        return mBottomSheetState;
    }

    public void logPickerOpened(int callingUid, String callingPackage, String intentAction) {
        if (getUserIdManager().isManagedUserSelected()) {
            mLogger.logPickerOpenWork(mInstanceId, callingUid, callingPackage);
        } else {
            mLogger.logPickerOpenPersonal(mInstanceId, callingUid, callingPackage);
        }

        // TODO(b/235326735): Optimise logging multiple times on picker opened
        // TODO(b/235326736): Check if we should add a metric for PICK_IMAGES intent to simplify
        // metrics reading
        if (ACTION_GET_CONTENT.equals(intentAction)) {
            mLogger.logPickerOpenViaGetContent(mInstanceId, callingUid, callingPackage);
        }
    }

    /**
     * Log metrics to notify that the user has clicked Browse to open DocumentsUi
     */
    public void logBrowseToDocumentsUi(int callingUid, String callingPackage) {
        mLogger.logBrowseToDocumentsUi(mInstanceId, callingUid, callingPackage);
    }

    /**
     * Log metrics to notify that the user has confirmed selection
     */
    public void logPickerConfirm(int callingUid, String callingPackage, int countOfItemsConfirmed) {
        if (getUserIdManager().isManagedUserSelected()) {
            mLogger.logPickerConfirmWork(mInstanceId, callingUid, callingPackage,
                    countOfItemsConfirmed);
        } else {
            mLogger.logPickerConfirmPersonal(mInstanceId, callingUid, callingPackage,
                    countOfItemsConfirmed);
        }
    }

    /**
     * Log metrics to notify that the user has exited Picker without any selection
     */
    public void logPickerCancel(int callingUid, String callingPackage) {
        if (getUserIdManager().isManagedUserSelected()) {
            mLogger.logPickerCancelWork(mInstanceId, callingUid, callingPackage);
        } else {
            mLogger.logPickerCancelPersonal(mInstanceId, callingUid, callingPackage);
        }
    }

    public InstanceId getInstanceId() {
        return mInstanceId;
    }

    public void setInstanceId(InstanceId parcelable) {
        mInstanceId = parcelable;
    }
}
