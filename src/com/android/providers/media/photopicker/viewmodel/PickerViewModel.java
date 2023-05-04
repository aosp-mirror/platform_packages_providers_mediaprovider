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
import static android.content.Intent.EXTRA_LOCAL_ONLY;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED;
import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;
import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.MediaApplication;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.MuteStatus;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.metrics.PhotoPickerUiEventLogger;
import com.android.providers.media.photopicker.util.MimeFilterUtils;
import com.android.providers.media.util.ForegroundThread;
import com.android.providers.media.util.MimeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * PickerViewModel to store and handle data for PhotoPickerActivity.
 */
public class PickerViewModel extends AndroidViewModel {
    public static final String TAG = "PhotoPicker";

    private static final int RECENT_MINIMUM_COUNT = 12;

    private static final int INSTANCE_ID_MAX = 1 << 15;

    @NonNull
    @SuppressLint("StaticFieldLeak")
    private final Context mAppContext;

    private final Selection mSelection;
    private final MuteStatus mMuteStatus;

    // TODO(b/193857982): We keep these four data sets now, we may need to find a way to reduce the
    //  data set to reduce memories.
    // The list of Items with all photos and videos
    private MutableLiveData<List<Item>> mItemList;
    // The list of Items with all photos and videos in category
    private MutableLiveData<List<Item>> mCategoryItemList;
    // The list of categories.
    private MutableLiveData<List<Category>> mCategoryList;

    private ItemsProvider mItemsProvider;
    private UserIdManager mUserIdManager;
    private BannerManager mBannerManager;

    private InstanceId mInstanceId;
    private PhotoPickerUiEventLogger mLogger;

    private String[] mMimeTypeFilters = null;
    private int mBottomSheetState;

    private Category mCurrentCategory;

    // Note - Must init banner manager on mIsUserSelectForApp / mIsLocalOnly updates
    private boolean mIsUserSelectForApp;
    private boolean mIsLocalOnly;

    public PickerViewModel(@NonNull Application application) {
        super(application);
        mAppContext = application.getApplicationContext();
        mItemsProvider = new ItemsProvider(mAppContext);
        mSelection = new Selection();
        mUserIdManager = UserIdManager.create(mAppContext);
        mMuteStatus = new MuteStatus();
        mInstanceId = new InstanceIdSequence(INSTANCE_ID_MAX).newInstanceId();
        mLogger = new PhotoPickerUiEventLogger();
        mIsUserSelectForApp = false;
        mIsLocalOnly = false;
        // Must init banner manager on mIsUserSelectForApp / mIsLocalOnly updates
        initBannerManager();
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
     * @return {@code mIsUserSelectForApp} if the picker is currently being used
     *         for the {@link MediaStore#ACTION_USER_SELECT_IMAGES_FOR_APP} action.
     */
    public boolean isUserSelectForApp() {
        return mIsUserSelectForApp;
    }

    /**
     * @return a {@link LiveData} that holds the value (once it's fetched) of the
     *         {@link android.content.ContentProvider#mAuthority authority} of the current
     *         {@link android.provider.CloudMediaProvider}.
     */
    @NonNull
    public LiveData<String> getCloudMediaProviderAuthorityLiveData() {
        return mBannerManager.getCloudMediaProviderAuthorityLiveData();
    }

    /**
     * @return a {@link LiveData} that holds the value (once it's fetched) of the label
     *         of the current {@link android.provider.CloudMediaProvider}.
     */
    @NonNull
    public LiveData<String> getCloudMediaProviderAppTitleLiveData() {
        return mBannerManager.getCloudMediaProviderAppTitleLiveData();
    }

    /**
     * @return a {@link LiveData} that holds the value (once it's fetched) of the account name
     *         of the current {@link android.provider.CloudMediaProvider}.
     */
    @NonNull
    public LiveData<String> getCloudMediaAccountNameLiveData() {
        return mBannerManager.getCloudMediaAccountNameLiveData();
    }

    /**
     * Reset PickerViewModel.
     * @param switchToPersonalProfile is true then set personal profile as current profile.
     */
    @UiThread
    public void reset(boolean switchToPersonalProfile) {
        // 1. Clear Selected items
        mSelection.clearSelectedItems();
        // 2. Change profile to personal user
        if (switchToPersonalProfile) {
            mUserIdManager.setPersonalAsCurrentUserProfile();
        }
        // 3. Update Item and Category lists
        updateItems();
        updateCategories();
        // 4. Update Banners
        // Note - Banners should always be updated after the items & categories to ensure a
        // consistent UI.
        mBannerManager.maybeResetAllBannerData();
        mBannerManager.maybeUpdateBannerLiveDatas();
    }

    /**
     * Update items, categories & banners on profile switched by the user.
     */
    @UiThread
    public void onUserSwitchedProfile() {
        updateItems();
        updateCategories();
        // Note - Banners should always be updated after the items & categories to ensure a
        // consistent UI.
        mBannerManager.maybeUpdateBannerLiveDatas();
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

        try (Cursor cursor = fetchItems(category, userId)) {
            if (cursor == null || cursor.getCount() == 0) {
                Log.d(TAG, "Didn't receive any items for " + category
                        + ", either cursor is null or cursor count is zero");
                return items;
            }

            while (cursor.moveToNext()) {
                // TODO(b/188394433): Return userId in the cursor so that we do not need to pass it
                //  here again.
                items.add(Item.fromCursor(cursor, userId));
            }
        }

        Log.d(TAG, "Loaded " + items.size() + " items in " + category + " for user "
                + userId.toString());
        return items;
    }

    private Cursor fetchItems(Category category, UserId userId) {
        if (shouldShowOnlyLocalFeatures()) {
            return mItemsProvider.getLocalItems(category, /* limit */ -1, mMimeTypeFilters, userId);
        } else {
            return mItemsProvider.getAllItems(category, /* limit */ -1, mMimeTypeFilters, userId);
        }
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
        try (Cursor cursor = fetchCategories(userId)) {
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

    private Cursor fetchCategories(UserId userId) {
        if (shouldShowOnlyLocalFeatures()) {
            return mItemsProvider.getLocalCategories(mMimeTypeFilters, userId);
        } else {
            return mItemsProvider.getAllCategories(mMimeTypeFilters, userId);
        }
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

    private boolean isAllImagesFilter() {
        return mMimeTypeFilters != null && mMimeTypeFilters.length == 1
                && MimeUtils.isAllImagesMimeType(mMimeTypeFilters[0]);
    }

    private boolean isAllVideosFilter() {
        return mMimeTypeFilters != null && mMimeTypeFilters.length == 1
                && MimeUtils.isAllVideosMimeType(mMimeTypeFilters[0]);
    }

    /**
     * Parse values from {@code intent} and set corresponding fields
     */
    public void parseValuesFromIntent(Intent intent) throws IllegalArgumentException {
        mUserIdManager.setIntentAndCheckRestrictions(intent);

        mMimeTypeFilters = MimeFilterUtils.getMimeTypeFilters(intent);

        mSelection.parseSelectionValuesFromIntent(intent);

        mIsLocalOnly = intent.getBooleanExtra(EXTRA_LOCAL_ONLY, false);

        mIsUserSelectForApp =
                MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.equals(intent.getAction());
        if (!SdkLevel.isAtLeastU() && mIsUserSelectForApp) {
            throw new IllegalArgumentException("ACTION_USER_SELECT_IMAGES_FOR_APP is not enabled "
                    + " for this OS version");
        }

        // Ensure that if Photopicker is being used for permissions the target app UID is present
        // in the extras.
        if (mIsUserSelectForApp
                && (intent.getExtras() == null
                        || !intent.getExtras()
                                .containsKey(Intent.EXTRA_UID))) {
            throw new IllegalArgumentException(
                    "EXTRA_UID is required for" + " ACTION_USER_SELECT_IMAGES_FOR_APP");
        }

        // Must init banner manager on mIsUserSelectForApp / mIsLocalOnly updates
        initBannerManager();
    }

    private void initBannerManager() {
        mBannerManager = shouldShowOnlyLocalFeatures()
                ? new BannerManager(mAppContext, mUserIdManager)
                : new BannerManager.CloudBannerManager(mAppContext, mUserIdManager);
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

    /**
     * Log picker opened metrics
     */
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

        if (mBottomSheetState == STATE_COLLAPSED) {
            mLogger.logPickerOpenInHalfScreen(mInstanceId, callingUid, callingPackage);
        } else if (mBottomSheetState == STATE_EXPANDED) {
            mLogger.logPickerOpenInFullScreen(mInstanceId, callingUid, callingPackage);
        }

        if (mSelection != null && mSelection.canSelectMultiple()) {
            mLogger.logPickerOpenInMultiSelect(mInstanceId, callingUid, callingPackage);
        } else {
            mLogger.logPickerOpenInSingleSelect(mInstanceId, callingUid, callingPackage);
        }

        if (isAllImagesFilter()) {
            mLogger.logPickerOpenWithFilterAllImages(mInstanceId, callingUid, callingPackage);
        } else if (isAllVideosFilter()) {
            mLogger.logPickerOpenWithFilterAllVideos(mInstanceId, callingUid, callingPackage);
        } else if (hasMimeTypeFilters()) {
            mLogger.logPickerOpenWithAnyOtherFilter(mInstanceId, callingUid, callingPackage);
        }

        maybeLogPickerOpenedWithCloudProvider();
    }

    // TODO(b/245745412): Fix log params (uid & package name)
    // TODO(b/245745424): Solve for active cloud provider without a logged in account
    private void maybeLogPickerOpenedWithCloudProvider() {
        if (shouldShowOnlyLocalFeatures()) {
            return;
        }

        final LiveData<String> cloudMediaProviderAuthorityLiveData =
                getCloudMediaProviderAuthorityLiveData();
        cloudMediaProviderAuthorityLiveData.observeForever(new Observer<String>() {
            @Override
            public void onChanged(@Nullable String providerAuthority) {
                Log.d(TAG, "logPickerOpenedWithCloudProvider() provider=" + providerAuthority
                        + ", log=" + (providerAuthority != null));

                if (providerAuthority != null) {
                    mLogger.logPickerOpenWithActiveCloudProvider(
                            mInstanceId, /* cloudProviderUid */ -1, providerAuthority);
                }
                // We only need to get the value once.
                cloudMediaProviderAuthorityLiveData.removeObserver(this);
            }
        });
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

    // Return whether hotopicker's launch intent has extra {@link EXTRA_LOCAL_ONLY} set to true
    // or not.
    @VisibleForTesting
    boolean isLocalOnly() {
        return mIsLocalOnly;
    }

    /**
     * Return whether only the local features should be shown (the cloud features should be hidden).
     *
     * Show only the local features in the following cases -
     * 1. Photo Picker is launched by the {@link MediaStore#ACTION_USER_SELECT_IMAGES_FOR_APP}
     *    action for the permission flow.
     * 2. Photo Picker is launched with the {@link Intent#EXTRA_LOCAL_ONLY} as {@code true} in the
     *    {@link Intent#ACTION_GET_CONTENT} or {@link MediaStore#ACTION_PICK_IMAGES} action.
     * 3. Cloud Media in Photo picker is disabled, i.e.,
     *    {@link ConfigStore#isCloudMediaInPhotoPickerEnabled()} is {@code false}.
     *
     * @return {@code true} iff either {@link #isUserSelectForApp()} or {@link #isLocalOnly()} is
     * {@code true}, OR if {@link ConfigStore#isCloudMediaInPhotoPickerEnabled()} is {@code false}.
     */
    public boolean shouldShowOnlyLocalFeatures() {
        return isUserSelectForApp() || isLocalOnly()
                || !getConfigStore().isCloudMediaInPhotoPickerEnabled();
    }

    @VisibleForTesting
    protected ConfigStore getConfigStore() {
        return MediaApplication.getConfigStore();
    }

    /**
     * @return the {@link LiveData} of the 'Choose App' banner visibility.
     */
    @NonNull
    public LiveData<Boolean> shouldShowChooseAppBannerLiveData() {
        return mBannerManager.shouldShowChooseAppBannerLiveData();
    }

    /**
     * @return the {@link LiveData} of the 'Cloud Media Available' banner visibility.
     */
    @NonNull
    public LiveData<Boolean> shouldShowCloudMediaAvailableBannerLiveData() {
        return mBannerManager.shouldShowCloudMediaAvailableBannerLiveData();
    }

    /**
     * @return the {@link LiveData} of the 'Account Updated' banner visibility.
     */
    @NonNull
    public LiveData<Boolean> shouldShowAccountUpdatedBannerLiveData() {
        return mBannerManager.shouldShowAccountUpdatedBannerLiveData();
    }

    /**
     * @return the {@link LiveData} of the 'Choose Account' banner visibility.
     */
    @NonNull
    public LiveData<Boolean> shouldShowChooseAccountBannerLiveData() {
        return mBannerManager.shouldShowChooseAccountBannerLiveData();
    }

    /**
     * Dismiss (hide) the 'Choose App' banner for the current user.
     */
    @UiThread
    public void onUserDismissedChooseAppBanner() {
        mBannerManager.onUserDismissedChooseAppBanner();
    }

    /**
     * Dismiss (hide) the 'Cloud Media Available' banner for the current user.
     */
    @UiThread
    public void onUserDismissedCloudMediaAvailableBanner() {
        mBannerManager.onUserDismissedCloudMediaAvailableBanner();
    }

    /**
     * Dismiss (hide) the 'Account Updated' banner for the current user.
     */
    @UiThread
    public void onUserDismissedAccountUpdatedBanner() {
        mBannerManager.onUserDismissedAccountUpdatedBanner();
    }

    /**
     * Dismiss (hide) the 'Choose Account' banner for the current user.
     */
    @UiThread
    public void onUserDismissedChooseAccountBanner() {
        mBannerManager.onUserDismissedChooseAccountBanner();
    }
}
