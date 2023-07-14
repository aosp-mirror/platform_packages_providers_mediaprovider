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
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS;

import static com.android.providers.media.PickerUriResolver.REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI;
import static com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED;
import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
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
import com.android.providers.media.photopicker.data.PaginationParameters;
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

    private final MutableLiveData<Boolean> mShouldRefreshUiLiveData = new MutableLiveData<>(false);
    private final ContentObserver mRefreshUiNotificationObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            mShouldRefreshUiLiveData.postValue(true);
        }
    };

    private ItemsProvider mItemsProvider;
    private UserIdManager mUserIdManager;
    private BannerManager mBannerManager;

    private InstanceId mInstanceId;
    private PhotoPickerUiEventLogger mLogger;

    private String[] mMimeTypeFilters = null;
    private int mBottomSheetState;

    private Category mCurrentCategory;

    // Content resolver for the currently selected user
    private ContentResolver mContentResolver;

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
        registerRefreshUiNotificationObserver();
    }

    @Override
    protected void onCleared() {
        unregisterRefreshUiNotificationObserver();
    }

    @VisibleForTesting
    public void setItemsProvider(@NonNull ItemsProvider itemsProvider) {
        mItemsProvider = itemsProvider;
    }

    @VisibleForTesting
    public void setUserIdManager(@NonNull UserIdManager userIdManager) {
        mUserIdManager = userIdManager;
    }

    @VisibleForTesting
    public void setBannerManager(@NonNull BannerManager bannerManager) {
        mBannerManager = bannerManager;
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
     * Reset to personal profile mode.
     */
    @UiThread
    public void resetToPersonalProfile() {
        mUserIdManager.setPersonalAsCurrentUserProfile();
        onSwitchedProfile();
    }

    /**
     * Reset the content observer & all the content on profile switched.
     */
    @UiThread
    public void onSwitchedProfile() {
        resetRefreshUiNotificationObserver();
        resetAllContentInCurrentProfile();
    }

    /**
     * Reset all the content (items, categories & banners) in the current profile.
     */
    @UiThread
    public void resetAllContentInCurrentProfile() {
        // Post 'should refresh UI live data' value as false to avoid unnecessary repetitive resets
        mShouldRefreshUiLiveData.postValue(false);

        // Clear the existing content - selection, photos grid, albums grid, banners
        mSelection.clearSelectedItems();

        if (mItemList != null) {
            ForegroundThread.getExecutor().execute(() ->
                    mItemList.postValue(List.of(Item.EMPTY_VIEW)));
        }

        if (mCategoryList != null) {
            ForegroundThread.getExecutor().execute(() ->
                    mCategoryList.postValue(List.of(Category.EMPTY_VIEW)));
        }

        mBannerManager.hideAllBanners();

        // Update items, categories & banners
        updateItems();
        updateCategories();
        mBannerManager.reset();
    }

    /**
     * @return the list of Items with all photos and videos {@link #mItemList} on the device for a
     * page represented by the {@code pagingParameters}.
     *
     * <p>Pass an object of {@link PaginationParameters} created using the default constructor
     * to obtain the complete list of items present.</p>
     */
    public LiveData<List<Item>> getPaginatedItems(PaginationParameters pagingParameters) {
        if (mItemList == null) {
            updateItems(pagingParameters);
        }
        return mItemList;
    }

    private List<Item> loadItems(Category category, UserId userId,
            PaginationParameters pagingParameters) {
        final List<Item> items = new ArrayList<>();
        String cloudProviderAuthority = null; // NULL if fetched items have NO cloud only media item

        try (Cursor cursor = fetchItems(category, userId, pagingParameters)) {
            if (cursor == null || cursor.getCount() == 0) {
                Log.d(TAG, "Didn't receive any items for " + category
                        + ", either cursor is null or cursor count is zero");
                return items;
            }

            while (cursor.moveToNext()) {
                // TODO(b/188394433): Return userId in the cursor so that we do not need to pass it
                //  here again.
                final Item item = Item.fromCursor(cursor, userId);
                String authority = item.getContentUri().getAuthority();

                if (!LOCAL_PICKER_PROVIDER_AUTHORITY.equals(authority)) {
                    cloudProviderAuthority = authority;
                }
                items.add(item);
            }

            Log.d(TAG, "Loaded " + items.size() + " items in " + category + " for user "
                    + userId.toString());
            return items;
        } finally {
            int count = items.size();
            if (category.isDefault()) {
                mLogger.logLoadedMainGridMediaItems(cloudProviderAuthority, mInstanceId, count);
            } else {
                mLogger.logLoadedAlbumGridMediaItems(cloudProviderAuthority, mInstanceId, count);
            }
        }
    }

    private Cursor fetchItems(Category category, UserId userId,
            PaginationParameters pagingParameters) {
        if (shouldShowOnlyLocalFeatures()) {
            return mItemsProvider.getLocalItems(category, pagingParameters,
                    mMimeTypeFilters, userId);
        } else {
            return mItemsProvider.getAllItems(category, pagingParameters,
                    mMimeTypeFilters, userId);
        }
    }

    private void loadItemsAsync(@Nullable PaginationParameters pagingParameters) {
        final UserId userId = mUserIdManager.getCurrentUserProfileId();
        ForegroundThread.getExecutor().execute(() -> {
            mItemList.postValue(loadItems(Category.DEFAULT, userId, pagingParameters));
        });
    }

    /**
     * Update the item List {@link #mItemList} for a page represented by the
     * {@code pagingParameters}.
     *
     * <p>Use {@link PickerViewModel#updateItems()} to update the complete list.</p>
     */
    public void updateItems(PaginationParameters pagingParameters) {
        if (mItemList == null) {
            mItemList = new MutableLiveData<>();
        }
        loadItemsAsync(pagingParameters);
    }

    /**
     * Update the complete item List {@link #mItemList}.
     */
    public void updateItems() {
        if (mItemList == null) {
            mItemList = new MutableLiveData<>();
        }
        loadItemsAsync(new PaginationParameters());
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
    public LiveData<List<Item>> getPaginatedCategoryItems(@NonNull Category category,
            PaginationParameters pagingParameters) {
        if (mCategoryItemList == null || !TextUtils.equals(mCurrentCategory.getId(),
                category.getId())) {
            mCategoryItemList = new MutableLiveData<>();
            mCurrentCategory = category;
        }
        updateCategoryItems(pagingParameters);
        return mCategoryItemList;
    }

    private void loadCategoryItemsAsync(PaginationParameters pagingParameters) {
        final UserId userId = mUserIdManager.getCurrentUserProfileId();
        ForegroundThread.getExecutor().execute(() -> {
            mCategoryItemList.postValue(loadItems(mCurrentCategory, userId, pagingParameters));
        });
    }

    /**
     * Update the item List with the {@link #mCurrentCategory} {@link #mCategoryItemList}
     *
     * @throws IllegalStateException category and category items is not initiated before calling
     *     this method
     */
    @VisibleForTesting
    public void updateCategoryItems(PaginationParameters pagingParameters) {
        if (mCategoryItemList == null || mCurrentCategory == null) {
            throw new IllegalStateException("mCurrentCategory and mCategoryItemList are not"
                    + " initiated. Please call getCategoryItems before calling this method");
        }
        loadCategoryItemsAsync(pagingParameters);
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
        String cloudProviderAuthority = null; // NULL if fetched albums have NO cloud album
        try (Cursor cursor = fetchCategories(userId)) {
            if (cursor == null || cursor.getCount() == 0) {
                Log.d(TAG, "Didn't receive any categories, either cursor is null or"
                        + " cursor count is zero");
                return categoryList;
            }

            while (cursor.moveToNext()) {
                final Category category = Category.fromCursor(cursor, userId);
                String authority = category.getAuthority();

                if (!LOCAL_PICKER_PROVIDER_AUTHORITY.equals(authority)) {
                    cloudProviderAuthority = authority;
                }
                categoryList.add(category);
            }

            Log.d(TAG,
                    "Loaded " + categoryList.size() + " categories for user " + userId.toString());
            return categoryList;
        } finally {
            mLogger.logLoadedAlbums(cloudProviderAuthority, mInstanceId, categoryList.size());
        }
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

    /**
     * Log metrics to notify that the user has clicked the mute / unmute button in a video preview
     */
    public void logVideoPreviewMuteButtonClick() {
        mLogger.logVideoPreviewMuteButtonClick(mInstanceId);
    }

    /**
     * Log metrics to notify that the user has clicked the 'view selected' button
     * @param selectedItemCount the number of items selected for preview all
     */
    public void logPreviewAllSelected(int selectedItemCount) {
        mLogger.logPreviewAllSelected(mInstanceId, selectedItemCount);
    }

    /**
     * Log metrics to notify that the 'switch profile' button is visible & enabled
     */
    public void logProfileSwitchButtonEnabled() {
        mLogger.logProfileSwitchButtonEnabled(mInstanceId);
    }

    /**
     * Log metrics to notify that the 'switch profile' button is visible but disabled
     */
    public void logProfileSwitchButtonDisabled() {
        mLogger.logProfileSwitchButtonDisabled(mInstanceId);
    }

    /**
     * Log metrics to notify that the user has clicked the 'switch profile' button
     */
    public void logProfileSwitchButtonClick() {
        mLogger.logProfileSwitchButtonClick(mInstanceId);
    }

    /**
     * Log metrics to notify that the user has switched to the photos tab
     */
    public void logSwitchToPhotosTab() {
        mLogger.logSwitchToPhotosTab(mInstanceId);
    }

    /**
     * Log metrics to notify that the user has switched to the albums tab
     */
    public void logSwitchToAlbumsTab() {
        mLogger.logSwitchToAlbumsTab(mInstanceId);
    }

    /**
     * Log metrics to notify that the user has opened an album
     * @param category the opened album metadata
     * @param position the position of the album in the recycler view
     */
    public void logAlbumOpened(@NonNull Category category, int position) {
        final String albumId = category.getId();
        if (ALBUM_ID_FAVORITES.equals(albumId)) {
            mLogger.logFavoritesAlbumOpened(mInstanceId);
        } else if (ALBUM_ID_CAMERA.equals(albumId)) {
            mLogger.logCameraAlbumOpened(mInstanceId);
        } else if (ALBUM_ID_DOWNLOADS.equals(albumId)) {
            mLogger.logDownloadsAlbumOpened(mInstanceId);
        } else if (ALBUM_ID_SCREENSHOTS.equals(albumId)) {
            mLogger.logScreenshotsAlbumOpened(mInstanceId);
        } else if (ALBUM_ID_VIDEOS.equals(albumId)) {
            mLogger.logVideosAlbumOpened(mInstanceId);
        } else if (!category.isLocal()) {
            mLogger.logCloudAlbumOpened(mInstanceId, position);
        }
    }

    /**
     * Log metrics to notify that the user has selected a media item
     * @param item     the selected item metadata
     * @param category the category of the item selected, {@link Category#DEFAULT} for main grid
     * @param position the position of the album in the recycler view
     */
    public void logMediaItemSelected(@NonNull Item item, @NonNull Category category, int position) {
        if (category.isDefault()) {
            mLogger.logSelectedMainGridItem(mInstanceId, position);
        } else {
            mLogger.logSelectedAlbumItem(mInstanceId, position);
        }

        if (!item.isLocal()) {
            mLogger.logSelectedCloudOnlyItem(mInstanceId, position);
        }
    }

    /**
     * Log metrics to notify that the user has previewed a media item
     * @param item     the previewed item metadata
     * @param category the category of the item previewed, {@link Category#DEFAULT} for main grid
     * @param position the position of the album in the recycler view
     */
    public void logMediaItemPreviewed(
            @NonNull Item item, @NonNull Category category, int position) {
        if (category.isDefault()) {
            mLogger.logPreviewedMainGridItem(
                    item.getSpecialFormat(), item.getMimeType(), mInstanceId, position);
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

    /**
     * @return a {@link LiveData} that posts Should Refresh Picker UI as {@code true} when notified.
     */
    @NonNull
    public LiveData<Boolean> shouldRefreshUiLiveData() {
        return mShouldRefreshUiLiveData;
    }

    private void registerRefreshUiNotificationObserver() {
        mContentResolver = getContentResolverForSelectedUser();
        mContentResolver.registerContentObserver(REFRESH_UI_PICKER_INTERNAL_OBSERVABLE_URI,
                /* notifyForDescendants */ false, mRefreshUiNotificationObserver);
    }

    private void unregisterRefreshUiNotificationObserver() {
        if (mContentResolver != null) {
            mContentResolver.unregisterContentObserver(mRefreshUiNotificationObserver);
            mContentResolver = null;
        }
    }

    private void resetRefreshUiNotificationObserver() {
        unregisterRefreshUiNotificationObserver();
        registerRefreshUiNotificationObserver();
    }

    private ContentResolver getContentResolverForSelectedUser() {
        final UserId selectedUserId = mUserIdManager.getCurrentUserProfileId();
        if (selectedUserId == null) {
            Log.d(TAG, "Selected user id is NULL; returning the default content resolver.");
            return mAppContext.getContentResolver();
        }
        try {
            return selectedUserId.getContentResolver(mAppContext);
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Failed to get the content resolver for the selected user id "
                    + selectedUserId + "; returning the default content resolver.", e);
            return mAppContext.getContentResolver();
        }
    }
}
