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
import static com.android.providers.media.photopicker.DataLoaderThread.TOKEN;
import static com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_CLEAR_AND_UPDATE_LIST;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_CLEAR_GRID;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_DEFAULT;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_LOAD_NEXT_PAGE;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_REFRESH_ITEMS;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_VIEW_CREATED;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED;
import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.MainThread;
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
import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.MediaApplication;
import com.android.providers.media.photopicker.DataLoaderThread;
import com.android.providers.media.photopicker.NotificationContentObserver;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.MuteStatus;
import com.android.providers.media.photopicker.data.PaginationParameters;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.metrics.NonUiEventLogger;
import com.android.providers.media.photopicker.metrics.PhotoPickerUiEventLogger;
import com.android.providers.media.photopicker.ui.ItemsAction;
import com.android.providers.media.photopicker.util.CategoryOrganiserUtils;
import com.android.providers.media.photopicker.util.MimeFilterUtils;
import com.android.providers.media.photopicker.util.ThreadUtils;
import com.android.providers.media.util.MimeUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PickerViewModel to store and handle data for PhotoPickerActivity.
 */
public class PickerViewModel extends AndroidViewModel {
    public static final String TAG = "PhotoPicker";

    private static final int RECENT_MINIMUM_COUNT = 12;

    private static final int INSTANCE_ID_MAX = 1 << 15;
    private static final int DELAY_MILLIS = 0;

    // Token for the tasks to load the category items in the data loader thread's queue
    private final Object mLoadCategoryItemsThreadToken = new Object();

    @NonNull
    @SuppressLint("StaticFieldLeak")
    private final Context mAppContext;

    private final Selection mSelection;

    private int mPackageUid = -1;

    private final MuteStatus mMuteStatus;
    public boolean mEmptyPageDisplayed = false;

    // TODO(b/193857982): We keep these four data sets now, we may need to find a way to reduce the
    //  data set to reduce memories.
    // The list of Items with all photos and videos
    private MutableLiveData<PaginatedItemsResult> mItemsResult;
    private int mItemsPageSize = -1;

    // The list of Items with all photos and videos in category
    private MutableLiveData<PaginatedItemsResult> mCategoryItemsResult;

    private int mCategoryItemsPageSize = -1;

    // The list of categories.
    private MutableLiveData<List<Category>> mCategoryList;

    private MutableLiveData<Boolean> mIsAllPreGrantedMediaLoaded = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> mShouldRefreshUiLiveData = new MutableLiveData<>(false);
    private final ContentObserver mRefreshUiNotificationObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            mShouldRefreshUiLiveData.postValue(true);
        }
    };

    private MutableLiveData<Boolean> mIsSyncInProgress = new MutableLiveData<>(false);

    private ItemsProvider mItemsProvider;
    private UserIdManager mUserIdManager;
    private BannerManager mBannerManager;

    private InstanceId mInstanceId;
    private PhotoPickerUiEventLogger mLogger;
    private ConfigStore mConfigStore;

    private String[] mMimeTypeFilters = null;
    private int mBottomSheetState;

    private Category mCurrentCategory;

    // Content resolver for the currently selected user
    private ContentResolver mContentResolver;

    // Note - Must init banner manager on mIsUserSelectForApp / mIsLocalOnly updates
    private boolean mIsUserSelectForApp;

    private boolean mIsManagedSelectionEnabled;
    private boolean mIsLocalOnly;
    private boolean mIsAllCategoryItemsLoaded = false;
    private boolean mIsNotificationForUpdateReceived = false;
    private CancellationSignal mCancellationSignal = new CancellationSignal();

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
        mIsManagedSelectionEnabled = false;
        mIsLocalOnly = false;

        initConfigStore();

        // When the user opens the PhotoPickerSettingsActivity and changes the cloud provider, it's
        // possible that system kills PhotoPickerActivity and PickerViewModel while it's in the
        // background. In these scenarios, content observer will be unregistered and PickerViewModel
        // will not be able to receive CMP change notifications.
        initPhotoPickerData();
        registerRefreshUiNotificationObserver();
        // Add notification content observer for any notifications received for changes in media.
        NotificationContentObserver contentObserver = new NotificationContentObserver(null);
        contentObserver.registerKeysToObserverCallback(
                Arrays.asList(NotificationContentObserver.MEDIA),
                (dateTakenMs, albumId) -> {
                    onNotificationReceived();
                });
        contentObserver.register(mAppContext.getContentResolver());
    }

    @Override
    protected void onCleared() {
        unregisterRefreshUiNotificationObserver();

        // Signal ContentProvider to cancel currently running task.
        mCancellationSignal.cancel();

        clearQueuedTasksInDataLoaderThread();
    }

    private void onNotificationReceived() {
        Log.d(TAG, "Notification for media update has been received");
        mIsNotificationForUpdateReceived = true;
        if (mEmptyPageDisplayed && mConfigStore.isCloudMediaInPhotoPickerEnabled()) {
            (new Handler(Looper.getMainLooper())).post(() -> {
                Log.d(TAG, "Refreshing UI to display new items.");
                mEmptyPageDisplayed = false;
                getPaginatedItemsForAction(ACTION_REFRESH_ITEMS,
                        new PaginationParameters(mItemsPageSize, -1, -1));
            });
        }
    }

    @VisibleForTesting
    protected void initConfigStore() {
        mConfigStore = MediaApplication.getConfigStore();
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

    @VisibleForTesting
    public void setNotificationForUpdateReceived(boolean notificationForUpdateReceived) {
        mIsNotificationForUpdateReceived = notificationForUpdateReceived;
    }

    @VisibleForTesting
    public void setLogger(@NonNull PhotoPickerUiEventLogger logger) {
        mLogger = logger;
    }

    @VisibleForTesting
    public void setConfigStore(@NonNull ConfigStore configStore) {
        mConfigStore = configStore;
    }

    public void setEmptyPageDisplayed(boolean emptyPageDisplayed) {
        mEmptyPageDisplayed = emptyPageDisplayed;
    }

    /**
     * @return the {@link ConfigStore} for this context.
     */
    public ConfigStore getConfigStore() {
        return mConfigStore;
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
     * for the {@link MediaStore#ACTION_USER_SELECT_IMAGES_FOR_APP} action.
     */
    public boolean isUserSelectForApp() {
        return mIsUserSelectForApp;
    }

    /**
     * @return {@code mIsManagedSelectionEnabled} if the picker is currently being used
     * for the {@link MediaStore#ACTION_USER_SELECT_IMAGES_FOR_APP} action and flag
     * pickerChoiceManagedSelection is enabled..
     */
    public boolean isManagedSelectionEnabled() {
        return mIsManagedSelectionEnabled;
    }

    /**
     * @return a {@link LiveData} that holds the value (once it's fetched) of the
     * {@link android.content.ContentProvider#mAuthority authority} of the current
     * {@link android.provider.CloudMediaProvider}.
     */
    @NonNull
    public LiveData<String> getCloudMediaProviderAuthorityLiveData() {
        return mBannerManager.getCloudMediaProviderAuthorityLiveData();
    }

    /**
     * @return a {@link LiveData} that holds the value (once it's fetched) of the label
     * of the current {@link android.provider.CloudMediaProvider}.
     */
    @NonNull
    public LiveData<String> getCloudMediaProviderAppTitleLiveData() {
        return mBannerManager.getCloudMediaProviderAppTitleLiveData();
    }

    /**
     * @return a {@link LiveData} that holds the value (once it's fetched) of the account name
     * of the current {@link android.provider.CloudMediaProvider}.
     */
    @NonNull
    public LiveData<String> getCloudMediaAccountNameLiveData() {
        return mBannerManager.getCloudMediaAccountNameLiveData();
    }

    /**
     * @return the account selection activity {@link Intent} of the current
     *         {@link android.provider.CloudMediaProvider}.
     */
    @Nullable
    public Intent getChooseCloudMediaAccountActivityIntent() {
        return mBannerManager.getChooseCloudMediaAccountActivityIntent();
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
        Log.d(TAG, "Reset all content in current profile");

        // Post 'should refresh UI live data' value as false to avoid unnecessary repetitive resets
        mShouldRefreshUiLiveData.postValue(false);

        clearQueuedTasksInDataLoaderThread();

        initPhotoPickerData();

        // Clear the existing content - selection, photos grid, albums grid, banners
        mSelection.clearSelectedItems();

        if (mItemsResult != null) {
            DataLoaderThread.getHandler().postDelayed(() ->
                    mItemsResult.postValue(new PaginatedItemsResult(List.of(Item.EMPTY_VIEW),
                            ACTION_CLEAR_GRID)), TOKEN, DELAY_MILLIS);
        }

        if (mCategoryList != null) {
            DataLoaderThread.getHandler().postDelayed(() ->
                    mCategoryList.postValue(List.of(Category.EMPTY_VIEW)), TOKEN, DELAY_MILLIS);
        }

        mBannerManager.hideAllBanners();

        // Update items, categories & banners
        getPaginatedItemsForAction(ACTION_CLEAR_AND_UPDATE_LIST, null);
        updateCategories();
        mBannerManager.reset();
    }

    /**
     * Loads list of pre granted items for the current package and userID.
     */
    public void initialisePreGrantsIfNecessary(Selection selection, Bundle intentExtras,
            String[] mimeTypeFilters) {
        if (isManagedSelectionEnabled() && selection.getPreGrantedItems() == null) {
            DataLoaderThread.getHandler().postDelayed(() -> {
                Set<String> preGrantedItems = mItemsProvider.fetchReadGrantedItemsUrisForPackage(
                                intentExtras.getInt(Intent.EXTRA_UID), mimeTypeFilters)
                        .stream().map((Uri uri) -> String.valueOf(ContentUris.parseId(uri)))
                        .collect(Collectors.toSet());
                selection.setPreGrantedItemSet(preGrantedItems);
                logPickerChoiceInitGrantsCount(preGrantedItems.size(), intentExtras);
            }, TOKEN, DELAY_MILLIS);
        }
    }

    /**
     * Performs required modification to the item list and returns the live data for it.
     */
    public LiveData<PaginatedItemsResult> getPaginatedItemsForAction(
            @NonNull @ItemsAction.Type int action,
            @Nullable PaginationParameters paginationParameters) {
        Objects.requireNonNull(action);
        switch (action) {
            case ACTION_VIEW_CREATED: {
                // Use this when a fresh view is created. If the current list is empty, it will
                // load the first page and return the list, else it will return previously
                // existing values.
                mItemsPageSize = paginationParameters.getPageSize();
                if (mItemsResult == null) {
                    updatePaginatedItems(paginationParameters, true, action);
                }
                break;
            }
            case ACTION_LOAD_NEXT_PAGE: {
                // Loads next page of the list, using the previously loaded list.
                // If the current list is empty then it will not perform any actions.
                if (mItemsResult != null && mItemsResult.getValue() != null) {
                    List<Item> currentItemList = mItemsResult.getValue().getItems();
                    // If the list is already empty that would mean that the first page was not
                    // loaded since there were no items to be loaded.
                    if (currentItemList != null && !currentItemList.isEmpty()) {
                        // get the last item of the existing list.
                        Item item = currentItemList.get(currentItemList.size() - 1);
                        updatePaginatedItems(
                                new PaginationParameters(mItemsPageSize, item.getDateTaken(),
                                        item.getRowId()), false, action);
                    }
                }
                break;
            }
            case ACTION_CLEAR_AND_UPDATE_LIST: {
                // Clears the existing list and loads the list with for mItemsPageSize
                // number of items. This will be equal to page size for pagination if cloud
                // picker feature flag is enabled, else it will be -1 implying that the complete
                // list should be loaded.
                updatePaginatedItems(new PaginationParameters(mItemsPageSize,
                        /*dateBeforeMs*/ Long.MIN_VALUE, /*rowId*/ -1), /* isReset */ true, action);
                break;
            }
            case ACTION_REFRESH_ITEMS: {
                if (mIsNotificationForUpdateReceived
                        && mItemsResult != null
                        && mItemsResult.getValue() != null) {
                    updatePaginatedItems(paginationParameters, true, action);
                    mIsNotificationForUpdateReceived = false;
                }
                break;
            }
            default:
                Log.w(TAG, "Invalid action passed to fetch items");
        }
        return mItemsResult;
    }

    /**
     * Update the item List {@link #mItemsResult}. Loads the page requested represented by the
     * pagination parameters and replaces/appends it to the existing list of items based on the
     * reset value.
     */
    private void updatePaginatedItems(PaginationParameters pagingParameters, boolean isReset,
            @ItemsAction.Type int action) {
        if (mItemsResult == null) {
            mItemsResult = new MutableLiveData<>();
        }
        loadItemsAsync(pagingParameters, /* isReset */ isReset, action);
    }

    /**
     * Loads required items and sets it to the {@link PickerViewModel#mItemsResult} while
     * considering the isReset value.
     *
     * @param pagingParameters parameters representing the items that needs to be loaded next.
     * @param isReset          If this is true, clear the pre-existing list and add the newly loaded
     *                         items.
     * @param action           This is used while posting the result of the operation.
     */
    private void loadItemsAsync(@NonNull PaginationParameters pagingParameters, boolean isReset,
            @ItemsAction.Type int action) {
        final UserId userId = mUserIdManager.getCurrentUserProfileId();

        DataLoaderThread.getHandler().postDelayed(() -> {
            // Load the items as per the pagination parameters passed as params to this method.
            List<Item> newPageItemList = loadItems(Category.DEFAULT, userId, pagingParameters);

            // Based on if it is a reset case or not, create an updated list.
            // If it is a reset case, assign an empty list else use the contents of the pre-existing
            // list. Then add the newly loaded items.
            List<Item> updatedList =
                    mItemsResult.getValue() == null || isReset ? new ArrayList<>()
                            : mItemsResult.getValue().getItems();
            updatedList.addAll(newPageItemList);
            Log.d(TAG, "Next page for photos items have been loaded.");
            if (newPageItemList.isEmpty()) {
                Log.d(TAG, "All photos items have been loaded.");
            }

            // post the result with the action.
            mItemsResult.postValue(new PaginatedItemsResult(updatedList, action));
            mIsSyncInProgress.postValue(false);
        }, TOKEN, DELAY_MILLIS);
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

            Set<String> preGrantedItems = new HashSet<>(0);
            Set<String> deSelectedPreGrantedItems = new HashSet<>(0);
            if (isManagedSelectionEnabled() && mSelection.getPreGrantedItems() != null) {
                preGrantedItems = mSelection.getPreGrantedItems();
                deSelectedPreGrantedItems = new HashSet<>(
                        mSelection.getPreGrantedItemIdsToBeRevoked());
            }
            while (cursor.moveToNext()) {
                // TODO(b/188394433): Return userId in the cursor so that we do not need to pass it
                //  here again.
                final Item item = Item.fromCursor(cursor, userId);
                if (preGrantedItems.contains(item.getId())) {
                    item.setPreGranted();
                    if (!deSelectedPreGrantedItems.contains(item.getId())) {
                        mSelection.addSelectedItem(item);
                    }
                }
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

    /**
     * @return true when all pre-granted items data has been loaded for this session.
     */
    @NonNull
    public MutableLiveData<Boolean> getIsAllPreGrantedMediaLoaded() {
        return mIsAllPreGrantedMediaLoaded;
    }

    /**
     * Gets item data for Uris which have not yet been loaded to the UI. This is important when the
     * preview fragment is created and hence should be called only before creation.
     *
     * <p>This is used during pagination. All the items are not loaded at once and hence the
     * preGranted item which is on a page that is yet to be loaded will would not be part of the
     * mSelected list and hence will not show up in the preview fragment. This method fixes this
     * issue by selectively loading those items and adding them to the selection list.</p>
     */
    public void getRemainingPreGrantedItems() {
        if (!isManagedSelectionEnabled() || mSelection.getPreGrantedItems() == null) return;

        List<String> idsForItemsToBeFetched =
                new ArrayList<>(mSelection.getPreGrantedItems());
        idsForItemsToBeFetched.removeAll(mSelection.getSelectedItemsIds());
        idsForItemsToBeFetched.removeAll(mSelection.getPreGrantedItemIdsToBeRevoked());

        if (!idsForItemsToBeFetched.isEmpty()) {
            final UserId userId = mUserIdManager.getCurrentUserProfileId();
            DataLoaderThread.getHandler().postDelayed(() -> {
                loadItemsWithLocalIdSelection(Category.DEFAULT, userId,
                        idsForItemsToBeFetched.stream().map(Integer::valueOf).collect(
                                Collectors.toList()));
                // If new data has loaded then post value representing a successful operation.
                mIsAllPreGrantedMediaLoaded.postValue(true);
                Log.d(TAG, "Fetched " + idsForItemsToBeFetched.size()
                        + " items for required preGranted ids");
            }, TOKEN, 0);
        }
    }

    private void loadItemsWithLocalIdSelection(Category category, UserId userId,
            List<Integer> selectionArg) {
        try (Cursor cursor = mItemsProvider.getLocalItemsForSelection(category, selectionArg,
                mMimeTypeFilters, userId, mCancellationSignal)) {
            if (cursor == null || cursor.getCount() == 0) {
                Log.d(TAG, "Didn't receive any items for pre granted URIs" + category
                        + ", either cursor is null or cursor count is zero");
                return;
            }

            Set<String> selectedIdSet = new HashSet<>(mSelection.getSelectedItemsIds());
            // Add all loaded items to selection after marking them as pre granted.
            while (cursor.moveToNext()) {
                final Item item = Item.fromCursor(cursor, userId);
                item.setPreGranted();
                if (!selectedIdSet.contains(item.getId())) {
                    mSelection.addSelectedItem(item);
                }
            }
            Log.d(TAG, "Pre granted items have been loaded.");
        }
    }

    private Cursor fetchItems(Category category, UserId userId,
            PaginationParameters pagingParameters) {
        try {
            if (shouldShowOnlyLocalFeatures()) {
                return mItemsProvider.getLocalItems(category, pagingParameters,
                        mMimeTypeFilters, userId, mCancellationSignal);
            } else {
                return mItemsProvider.getAllItems(category, pagingParameters,
                        mMimeTypeFilters, userId, mCancellationSignal);
            }
        } catch (RuntimeException ignored) {
            // Catch OperationCanceledException.
            Log.e(TAG, "Failed to fetch items due to a runtime exception", ignored);
            return null;
        }
    }

    /**
     * Modifies and returns the live data for category items.
     */
    public LiveData<PaginatedItemsResult> getPaginatedCategoryItemsForAction(
            @NonNull Category category,
            @ItemsAction.Type int action, @Nullable PaginationParameters paginationParameters) {
        switch (action) {
            case ACTION_VIEW_CREATED: {
                // This call is made only for loading the first page of album media,
                // so the existing data loader thread tasks for updating the category items should
                // be cleared and the category and category item list should be refreshed each time.
                DataLoaderThread.getHandler().removeCallbacksAndMessages(
                        mLoadCategoryItemsThreadToken);
                mCategoryItemsResult = new MutableLiveData<>();
                mCurrentCategory = category;
                assert paginationParameters != null;
                mCategoryItemsPageSize = paginationParameters.getPageSize();
                updateCategoryItems(paginationParameters, action);
                break;
            }
            case ACTION_LOAD_NEXT_PAGE: {
                // Loads next page of the list, using the previously loaded list.
                // If the current list is empty then it will not perform any actions.
                if (mCategoryItemsResult == null || mCategoryItemsResult.getValue() == null
                        || !TextUtils.equals(mCurrentCategory.getId(),
                        category.getId())) {
                    break;
                }
                List<Item> currentItemList = mCategoryItemsResult.getValue().getItems();
                // If the categoryItemList does not contain any items, it would mean that the first
                // page was empty.
                if (currentItemList != null && !currentItemList.isEmpty()) {
                    Item item = currentItemList.get(currentItemList.size() - 1);
                    PaginationParameters pagingParams = new PaginationParameters(
                            mCategoryItemsPageSize,
                            item.getDateTaken(),
                            item.getRowId());
                    updateCategoryItems(pagingParams, action);
                }
                break;
            }
            default:
                Log.w(TAG, "Invalid action passed to fetch category items");
        }
        return mCategoryItemsResult;
    }

    /**
     * Update the item List with the {@link #mCurrentCategory} {@link #mCategoryItemsResult}
     *
     * @throws IllegalStateException category and category items is not initiated before calling
     *                               this method
     */
    @VisibleForTesting
    public void updateCategoryItems(PaginationParameters pagingParameters,
            @ItemsAction.Type int action) {
        if (mCategoryItemsResult == null || mCurrentCategory == null) {
            throw new IllegalStateException("mCurrentCategory and mCategoryItemsResult are not"
                    + " initiated. Please call getCategoryItems before calling this method");
        }
        loadCategoryItemsAsync(pagingParameters, action != ACTION_LOAD_NEXT_PAGE, action);
    }

    /**
     * Loads required category items and sets it to the {@link PickerViewModel#mCategoryItemsResult}
     * while considering the isReset value.
     *
     * @param pagingParameters parameters representing the items that needs to be loaded next.
     * @param isReset          If this is true, clear the pre-existing list and add the newly loaded
     *                         items.
     * @param action           This is used while posting the result of the operation.
     */
    private void loadCategoryItemsAsync(PaginationParameters pagingParameters, boolean isReset,
            @ItemsAction.Type int action) {
        final UserId userId = mUserIdManager.getCurrentUserProfileId();
        final Category category = mCurrentCategory;

        DataLoaderThread.getHandler().postDelayed(() -> {
            if (action == ACTION_LOAD_NEXT_PAGE && mIsAllCategoryItemsLoaded) {
                return;
            }
            // Load the items as per the pagination parameters passed as params to this method.
            List<Item> newPageItemList = loadItems(category, userId, pagingParameters);

            // Based on if it is a reset case or not, create an updated list.
            // If it is a reset case, assign an empty list else use the contents of the pre-existing
            // list. Then add the newly loaded items.
            List<Item> updatedList = mCategoryItemsResult.getValue() == null || isReset
                    ? new ArrayList<>() : mCategoryItemsResult.getValue().getItems();
            updatedList.addAll(newPageItemList);

            if (isReset) {
                mIsAllCategoryItemsLoaded = false;
            }
            Log.d(TAG, "Next page for category items have been loaded. Category: "
                    + category + " " + updatedList.size());
            if (newPageItemList.isEmpty()) {
                mIsAllCategoryItemsLoaded = true;
                Log.d(TAG, "All items have been loaded for category: " + mCurrentCategory);
            }
            if (Objects.equals(category, mCurrentCategory)) {
                mCategoryItemsResult.postValue(new PaginatedItemsResult(updatedList, action));
            }
        }, mLoadCategoryItemsThreadToken, DELAY_MILLIS);
    }

    /**
     * Used only for testing, clears out any data in item list and category item list.
     */
    @VisibleForTesting
    public void clearItemsAndCategoryItemsList() {
        mItemsResult = null;
        mCategoryItemsResult = null;
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
            CategoryOrganiserUtils.getReorganisedCategoryList(categoryList);
            return categoryList;
        } finally {
            mLogger.logLoadedAlbums(cloudProviderAuthority, mInstanceId, categoryList.size());
        }
    }

    private Cursor fetchCategories(UserId userId) {
        try {
            if (shouldShowOnlyLocalFeatures()) {
                return mItemsProvider
                        .getLocalCategories(mMimeTypeFilters, userId, mCancellationSignal);
            } else {
                return mItemsProvider
                        .getAllCategories(mMimeTypeFilters, userId, mCancellationSignal);
            }
        } catch (RuntimeException ignored) {
            // Catch OperationCanceledException.
            Log.e(TAG, "Failed to fetch categories due to a runtime exception", ignored);
            return null;
        }
    }

    private void loadCategoriesAsync() {
        final UserId userId = mUserIdManager.getCurrentUserProfileId();
        DataLoaderThread.getHandler().postDelayed(() -> {
            mCategoryList.postValue(loadCategories(userId));
        }, TOKEN, DELAY_MILLIS);
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
        mIsManagedSelectionEnabled = mIsUserSelectForApp
                && getConfigStore().isPickerChoiceManagedSelectionEnabled();
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

        if (mIsUserSelectForApp) {
            mPackageUid = intent.getExtras().getInt(Intent.EXTRA_UID);
        }
        // Must init banner manager on mIsUserSelectForApp / mIsLocalOnly updates
        if (mBannerManager == null) {
            initBannerManager();
        }
    }

    private void initBannerManager() {
        mBannerManager = shouldShowOnlyLocalFeatures()
                ? new BannerManager(mAppContext, mUserIdManager, mConfigStore)
                : new BannerManager.CloudBannerManager(mAppContext, mUserIdManager, mConfigStore);
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
                    BackgroundThread.getExecutor().execute(() ->
                            logPickerOpenedWithCloudProvider(providerAuthority));
                }
                // We only need to get the value once.
                cloudMediaProviderAuthorityLiveData.removeObserver(this);
            }
        });
    }

    private void logPickerOpenedWithCloudProvider(@NonNull String providerAuthority) {
        String cloudProviderPackage = providerAuthority;
        int cloudProviderUid = -1;

        try {
            final PackageManager packageManager =
                    UserId.CURRENT_USER.getPackageManager(mAppContext);
            final ProviderInfo providerInfo = packageManager.resolveContentProvider(
                    providerAuthority, /* flags= */ 0);

            cloudProviderPackage = providerInfo.applicationInfo.packageName;
            cloudProviderUid = providerInfo.applicationInfo.uid;
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Logging the ui event 'picker open with an active cloud provider' with its "
                    + "authority in place of the package name and a default uid.", e);
        }

        mLogger.logPickerOpenWithActiveCloudProvider(
                mInstanceId, cloudProviderUid, cloudProviderPackage);
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
     *
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
     * Log metrics to notify that the user has cancelled the current session by swiping down
     */
    public void logSwipeDownExit() {
        mLogger.logSwipeDownExit(mInstanceId);
    }

    /**
     * Log metrics to notify that the user has made a back gesture
     * @param backStackEntryCount the number of fragment entries currently in the back stack
     */
    public void logBackGestureWithStackCount(int backStackEntryCount) {
        mLogger.logBackGestureWithStackCount(mInstanceId, backStackEntryCount);
    }

    /**
     * Log metrics to notify that the user has clicked the action bar home button
     * @param backStackEntryCount the number of fragment entries currently in the back stack
     */
    public void logActionBarHomeButtonClick(int backStackEntryCount) {
        mLogger.logActionBarHomeButtonClick(mInstanceId, backStackEntryCount);
    }

    /**
     * Log metrics to notify that the user has expanded from half screen to full
     */
    public void logExpandToFullScreen() {
        mLogger.logExpandToFullScreen(mInstanceId);
    }

    /**
     * Log metrics to notify that the user has opened the photo picker menu
     */
    public void logMenuOpened() {
        mLogger.logMenuOpened(mInstanceId);
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
     *
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
     *
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
     *
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

    /**
     * Log metrics to notify create surface controller triggered
     * @param authority  the authority of the provider
     */
    public void logCreateSurfaceControllerStart(String authority) {
        mLogger.logPickerCreateSurfaceControllerStart(mInstanceId, authority);
    }

    /**
     * Log metrics to notify create surface controller ended
     * @param authority  the authority of the provider
     */
    public void logCreateSurfaceControllerEnd(String authority) {
        mLogger.logPickerCreateSurfaceControllerEnd(mInstanceId, authority);
    }

    /**
     * Log metrics to notify that the selected media preloading started
     * @param count the number of items to preload
     */
    public void logPreloadingStarted(int count) {
        mLogger.logPreloadingStarted(mInstanceId, count);
    }

    /**
     * Log metrics to notify that the selected media preloading finished
     */
    public void logPreloadingFinished() {
        mLogger.logPreloadingFinished(mInstanceId);
    }

    /**
     * Log metrics to notify that the user cancelled the selected media preloading
     * @param count the number of items pending to preload
     */
    public void logPreloadingCancelled(int count) {
        mLogger.logPreloadingCancelled(mInstanceId, count);
    }

    /**
     * Log metrics to notify that the selected media preloading failed for some items
     * @param count the number of items pending / failed to preload
     */
    public void logPreloadingFailed(int count) {
        mLogger.logPreloadingFailed(mInstanceId, count);
    }

    /**
     * Logs metrics for count of grants initialised for a package.
     */
    public void logPickerChoiceInitGrantsCount(int numberOfGrants, Bundle intentExtras) {
        NonUiEventLogger.logPickerChoiceInitGrantsCount(mInstanceId, android.os.Process.myUid(),
                getPackageNameForUid(intentExtras), numberOfGrants);

    }

    /**
     * Logs metrics for count of grants added for a package.
     */
    public void logPickerChoiceAddedGrantsCount(int numberOfGrants, Bundle intentExtras) {
        NonUiEventLogger.logPickerChoiceGrantsAdditionCount(mInstanceId, android.os.Process.myUid(),
                getPackageNameForUid(intentExtras), numberOfGrants);
    }

    /**
     * Logs metrics for count of grants removed for a package.
     */
    public void logPickerChoiceRevokedGrantsCount(int numberOfGrants, Bundle intentExtras) {
        NonUiEventLogger.logPickerChoiceGrantsRemovedCount(mInstanceId, android.os.Process.myUid(),
                getPackageNameForUid(intentExtras), numberOfGrants);
    }

    /**
     * Log metrics to notify that the banner is added to display in the recycler view grids
     * @param bannerName the name of the banner added,
     *                   refer {@link com.android.providers.media.photopicker.ui.TabAdapter.Banner}
     */
    public void logBannerAdded(@NonNull String bannerName) {
        mLogger.logBannerAdded(mInstanceId, bannerName);
    }

    /**
     * Log metrics to notify that the banner is dismissed by the user
     */
    public void logBannerDismissed() {
        mLogger.logBannerDismissed(mInstanceId);
    }

    /**
     * Log metrics to notify that the user clicked the banner action button
     */
    public void logBannerActionButtonClicked() {
        mLogger.logBannerActionButtonClicked(mInstanceId);
    }

    /**
     * Log metrics to notify that the user clicked on the remaining part of the banner
     */
    public void logBannerClicked() {
        mLogger.logBannerClicked(mInstanceId);
    }

    @NonNull
    private String getPackageNameForUid(Bundle extras) {
        final int uid = extras.getInt(Intent.EXTRA_UID);
        final PackageManager pm = mAppContext.getPackageManager();
        String[] packageNames = pm.getPackagesForUid(uid);
        if (packageNames.length != 0) {
            return packageNames[0];
        }
        return new String();
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
     * action for the permission flow.
     * 2. Photo Picker is launched with the {@link Intent#EXTRA_LOCAL_ONLY} as {@code true} in the
     * {@link Intent#ACTION_GET_CONTENT} or {@link MediaStore#ACTION_PICK_IMAGES} action.
     * 3. Cloud Media in Photo picker is disabled, i.e.,
     * {@link ConfigStore#isCloudMediaInPhotoPickerEnabled()} is {@code false}.
     *
     * @return {@code true} iff either {@link #isUserSelectForApp()} or {@link #isLocalOnly()} is
     * {@code true}, OR if {@link ConfigStore#isCloudMediaInPhotoPickerEnabled()} is {@code false}.
     */
    public boolean shouldShowOnlyLocalFeatures() {
        return isUserSelectForApp() || isLocalOnly()
                || !mConfigStore.isCloudMediaInPhotoPickerEnabled();
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
    @MainThread
    public void onUserDismissedChooseAppBanner() {
        ThreadUtils.assertMainThread();
        mBannerManager.onUserDismissedChooseAppBanner();
    }

    /**
     * Dismiss (hide) the 'Cloud Media Available' banner for the current user.
     */
    @MainThread
    public void onUserDismissedCloudMediaAvailableBanner() {
        ThreadUtils.assertMainThread();
        mBannerManager.onUserDismissedCloudMediaAvailableBanner();
    }

    /**
     * Dismiss (hide) the 'Account Updated' banner for the current user.
     */
    @MainThread
    public void onUserDismissedAccountUpdatedBanner() {
        ThreadUtils.assertMainThread();
        mBannerManager.onUserDismissedAccountUpdatedBanner();
    }

    /**
     * Dismiss (hide) the 'Choose Account' banner for the current user.
     */
    @MainThread
    public void onUserDismissedChooseAccountBanner() {
        ThreadUtils.assertMainThread();
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

    public LiveData<Boolean> isSyncInProgress() {
        return mIsSyncInProgress;
    }

    /**
     * Class used to store the result of the item modification operations.
     */
    public class PaginatedItemsResult {
        private List<Item> mItems = new ArrayList<>();

        private int mAction = ACTION_DEFAULT;

        public PaginatedItemsResult(@NonNull List<Item> itemList,
                @ItemsAction.Type int action) {
            mItems = itemList;
            mAction = action;
        }

        public List<Item> getItems() {
            return mItems;
        }

        @ItemsAction.Type
        public int getAction() {
            return mAction;
        }
    }

    /**
     * This will inform the media Provider process that the UI is preparing to load data for the
     * main photos grid.
     */
    public void initPhotoPickerData() {
        initPhotoPickerData(Category.DEFAULT);
    }

    /**
     * This will inform the media Provider process that the UI is preparing to load data for main
     * photos grid or album contents grid.
     */
    public void initPhotoPickerData(@NonNull Category category) {
        if (mConfigStore.isCloudMediaInPhotoPickerEnabled()) {
            UserId userId = mUserIdManager.getCurrentUserProfileId();
            DataLoaderThread.getHandler().postDelayed(() -> {
                if (category == Category.DEFAULT) {
                    mIsSyncInProgress.postValue(true);
                }
                mItemsProvider.initPhotoPickerData(category.getId(),
                        category.getAuthority(),
                        shouldShowOnlyLocalFeatures(),
                        userId);
            }, TOKEN, DELAY_MILLIS);
        }
    }

    private void clearQueuedTasksInDataLoaderThread() {
        DataLoaderThread.getHandler().removeCallbacksAndMessages(TOKEN);
        DataLoaderThread.getHandler().removeCallbacksAndMessages(mLoadCategoryItemsThreadToken);
    }
}
