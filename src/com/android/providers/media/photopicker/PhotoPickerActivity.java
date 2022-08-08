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

import static android.content.Intent.ACTION_GET_CONTENT;

import static com.android.providers.media.photopicker.data.PickerResult.getPickerResponseIntent;
import static com.android.providers.media.photopicker.util.LayoutModeUtils.MODE_PHOTOS_TAB;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.ui.TabContainerFragment;
import com.android.providers.media.photopicker.util.LayoutModeUtils;
import com.android.providers.media.photopicker.util.MimeFilterUtils;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback;
import com.google.android.material.tabs.TabLayout;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * Photo Picker allows users to choose one or more photos and/or videos to share with an app. The
 * app does not get access to all photos/videos.
 */
public class PhotoPickerActivity extends AppCompatActivity {
    private static final String TAG =  "PhotoPickerActivity";
    private static final float BOTTOM_SHEET_PEEK_HEIGHT_PERCENTAGE = 0.60f;
    private static final float HIDE_PROFILE_BUTTON_THRESHOLD = -0.5f;
    private static final String LOGGER_INSTANCE_ID_ARG = "loggerInstanceIdArg";

    private PickerViewModel mPickerViewModel;
    private Selection mSelection;
    private BottomSheetBehavior mBottomSheetBehavior;
    private View mBottomBar;
    private View mBottomSheetView;
    private View mFragmentContainerView;
    private View mDragBar;
    private View mPrivacyText;
    private View mProfileButton;
    private TabLayout mTabLayout;
    private Toolbar mToolbar;
    private CrossProfileListeners mCrossProfileListeners;

    @ColorInt
    private int mDefaultBackgroundColor;

    @ColorInt
    private int mToolBarIconColor;

    private int mToolbarHeight = 0;
    private boolean mIsAccessibilityEnabled;
    private boolean mShouldLogCancelledResult = true;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // This is required as GET_CONTENT with type "*/*" is also received by PhotoPicker due
        // to higher priority than DocumentsUi. "*/*" mime type filter is caught as it is a superset
        // of "image/*" and "video/*".
        rerouteGetContentRequestIfRequired();

        // We use the device default theme as the base theme. Apply the material them for the
        // material components. We use force "false" here, only values that are not already defined
        // in the base theme will be copied.
        getTheme().applyStyle(R.style.PickerMaterialTheme, /* force */ false);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_photo_picker);

        mToolbar = findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        final int[] attrs = new int[]{R.attr.actionBarSize, R.attr.pickerTextColor};
        final TypedArray ta = obtainStyledAttributes(attrs);
        // Save toolbar height so that we can use it as padding for FragmentContainerView
        mToolbarHeight = ta.getDimensionPixelSize(/* index */ 0, /* defValue */ -1);
        mToolBarIconColor = ta.getColor(/* index */ 1,/* defValue */ -1);
        ta.recycle();

        mDefaultBackgroundColor = getColor(R.color.picker_background_color);
        mPickerViewModel = createViewModel();
        mSelection = mPickerViewModel.getSelection();

        final Intent intent = getIntent();
        try {
            mPickerViewModel.parseValuesFromIntent(intent);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Finish activity due to an exception while parsing extras", e);
            finishWithoutLoggingCancelledResult();
        }

        mDragBar = findViewById(R.id.drag_bar);
        mPrivacyText = findViewById(R.id.privacy_text);
        mBottomBar = findViewById(R.id.picker_bottom_bar);
        mProfileButton = findViewById(R.id.profile_button);

        mTabLayout = findViewById(R.id.tab_layout);

        AccessibilityManager accessibilityManager = getSystemService(AccessibilityManager.class);
        mIsAccessibilityEnabled = accessibilityManager.isEnabled();
        accessibilityManager.addAccessibilityStateChangeListener(
                enabled -> mIsAccessibilityEnabled = enabled);

        initBottomSheetBehavior();
        restoreState(savedInstanceState);

        String intentAction = intent != null ? intent.getAction() : null;
        // Call this after state is restored, to use the correct LOGGER_INSTANCE_ID_ARG
        mPickerViewModel.logPickerOpened(Binder.getCallingUid(), getCallingPackage(), intentAction);

        // Save the fragment container layout so that we can adjust the padding based on preview or
        // non-preview mode.
        mFragmentContainerView = findViewById(R.id.fragment_container);

        mCrossProfileListeners = new CrossProfileListeners();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // This is required to unregister any broadcast receivers.
        mCrossProfileListeners.onDestroy();
    }

    /**
     * Warning: This method is needed for tests, we are not customizing anything here.
     * Allowing ourselves to control ViewModel creation helps us mock the ViewModel for test.
     */
    @VisibleForTesting
    @NonNull
    protected PickerViewModel createViewModel() {
        return new ViewModelProvider(this).get(PickerViewModel.class);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event){
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (mBottomSheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED) {

                Rect outRect = new Rect();
                mBottomSheetView.getGlobalVisibleRect(outRect);

                if (!outRect.contains((int)event.getRawX(), (int)event.getRawY())) {
                    mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        getSupportActionBar().setTitle(title);
    }

    /**
     * Called when owning activity is saving state to be used to restore state during creation.
     *
     * @param state Bundle to save state
     */
    @Override
    public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        saveBottomSheetState();
        state.putParcelable(LOGGER_INSTANCE_ID_ARG, mPickerViewModel.getInstanceId());
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        if (ACTION_GET_CONTENT.equals(getIntent().getAction())) {
            getMenuInflater().inflate(R.menu.picker_overflow_menu, menu);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.browse) {
            mPickerViewModel.logBrowseToDocumentsUi(Binder.getCallingUid(), getCallingPackage());
            launchDocumentsUiAndFinishPicker();
        }
        return super.onOptionsItemSelected(item);
    }

    private void rerouteGetContentRequestIfRequired() {
        final Intent intent = getIntent();
        if (!ACTION_GET_CONTENT.equals(intent.getAction())) {
            return;
        }

        // TODO(b/232775643): Workaround to support PhotoPicker invoked from DocumentsUi.
        // GET_CONTENT for all (media and non-media) files opens DocumentsUi, but it still shows
        // "Photo Picker app option. When the user clicks on "Photo Picker", the same intent which
        // includes filters to show non-media files as well is forwarded to PhotoPicker.
        // Make sure Photo Picker is opened when the intent is explicitly forwarded by documentsUi
        if (isIntentReferredByDocumentsUi(getReferrer())) {
            Log.i(TAG, "Open PhotoPicker when a forwarded ACTION_GET_CONTENT intent is received");
            return;
        }

        if (MimeFilterUtils.requiresUnsupportedFilters(intent)) {
            launchDocumentsUiAndFinishPicker();
        }
    }

    private boolean isIntentReferredByDocumentsUi(Uri referrerAppUri) {
        ComponentName documentsUiComponentName = getDocumentsUiComponentName(this);
        String documentsUiPackageName = documentsUiComponentName != null
                ? documentsUiComponentName.getPackageName() : null;
        return referrerAppUri != null && referrerAppUri.getHost().equals(documentsUiPackageName);
    }

    private void launchDocumentsUiAndFinishPicker() {
        Log.i(TAG, "Launch DocumentsUI and finish picker");

        startActivityAsUser(getDocumentsUiForwardingIntent(this, getIntent()),
                UserId.CURRENT_USER.getUserHandle());
        // RESULT_CANCELLED is not returned to the calling app as the DocumentsUi result will be
        // returned. We don't have to log as this flow can be called in 2 cases:
        // 1. GET_CONTENT had non-media filters, so the user or the app should be unaffected as they
        // see that DocumentsUi was opened directly.
        // 2. User clicked on "Browse.." button, in that case we already log that event separately.
        finishWithoutLoggingCancelledResult();
    }

    @VisibleForTesting
    static Intent getDocumentsUiForwardingIntent(Context context, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
        intent.setComponent(getDocumentsUiComponentName(context));
        return intent;
    }

    private static ComponentName getDocumentsUiComponentName(Context context) {
        final PackageManager pm = context.getPackageManager();
        // DocumentsUI is the default handler for ACTION_OPEN_DOCUMENT
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        return intent.resolveActivity(pm);
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            restoreBottomSheetState();
            mPickerViewModel.setInstanceId(
                    savedInstanceState.getParcelable(LOGGER_INSTANCE_ID_ARG));
        } else {
            setupInitialLaunchState();
        }
    }

    /**
     * Sets up states for the initial launch. This includes updating common layouts, selecting
     * Photos tab item and saving the current bottom sheet state for later.
     */
    private void setupInitialLaunchState() {
        updateCommonLayouts(MODE_PHOTOS_TAB, /* title */ "");
        TabContainerFragment.show(getSupportFragmentManager());
        saveBottomSheetState();
    }

    private void initBottomSheetBehavior() {
        mBottomSheetView = findViewById(R.id.bottom_sheet);
        mBottomSheetBehavior = BottomSheetBehavior.from(mBottomSheetView);
        initStateForBottomSheet();

        mBottomSheetBehavior.addBottomSheetCallback(createBottomSheetCallBack());
        setRoundedCornersForBottomSheet();
    }

    private BottomSheetCallback createBottomSheetCallBack() {
        return new BottomSheetCallback() {
            private boolean mIsHiddenDueToBottomSheetClosing = false;
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    finish();
                }
                saveBottomSheetState();
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                // slideOffset = -1 is when bottomsheet is completely hidden
                // slideOffset = 0 is when bottomsheet is in collapsed mode
                // slideOffset = 1 is when bottomsheet is in expanded mode
                // We hide the Profile button if the bottomsheet is 50% in between collapsed state
                // and hidden state.
                if (slideOffset < HIDE_PROFILE_BUTTON_THRESHOLD &&
                        mProfileButton.getVisibility() == View.VISIBLE) {
                    mProfileButton.setVisibility(View.GONE);
                    mIsHiddenDueToBottomSheetClosing = true;
                    return;
                }

                // We need to handle this state if the user is swiping till the bottom of the
                // screen but then swipes up bottom sheet suddenly
                if (slideOffset > HIDE_PROFILE_BUTTON_THRESHOLD &&
                        mIsHiddenDueToBottomSheetClosing) {
                    mProfileButton.setVisibility(View.VISIBLE);
                    mIsHiddenDueToBottomSheetClosing = false;
                }
            }
        };
    }

    private void setRoundedCornersForBottomSheet() {
        final float cornerRadius =
                getResources().getDimensionPixelSize(R.dimen.picker_top_corner_radius);
        final ViewOutlineProvider viewOutlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(final View view, final Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(),
                        (int)(view.getHeight() + cornerRadius), cornerRadius);
            }
        };
        mBottomSheetView.setOutlineProvider(viewOutlineProvider);
    }

    private void initStateForBottomSheet() {
        if (!mIsAccessibilityEnabled && !mSelection.canSelectMultiple()
                && !isOrientationLandscape()) {
            final int peekHeight = getBottomSheetPeekHeight(this);
            mBottomSheetBehavior.setPeekHeight(peekHeight);
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            mBottomSheetBehavior.setSkipCollapsed(true);
        }
    }

    private static int getBottomSheetPeekHeight(Context context) {
        final WindowManager windowManager = context.getSystemService(WindowManager.class);
        final Rect displayBounds = windowManager.getCurrentWindowMetrics().getBounds();
        return (int) (displayBounds.height() * BOTTOM_SHEET_PEEK_HEIGHT_PERCENTAGE);
    }

    private void restoreBottomSheetState() {
        // BottomSheet is always EXPANDED for landscape
        if (isOrientationLandscape()) {
            return;
        }
        final int savedState = mPickerViewModel.getBottomSheetState();
        if (isValidBottomSheetState(savedState)) {
            mBottomSheetBehavior.setState(savedState);
        }
    }

    private void saveBottomSheetState() {
        // Do not save state for landscape or preview mode. This is because they are always in
        // STATE_EXPANDED state.
        if (isOrientationLandscape() || !mBottomSheetView.getClipToOutline()) {
            return;
        }
        mPickerViewModel.setBottomSheetState(mBottomSheetBehavior.getState());
    }

    private boolean isValidBottomSheetState(int state) {
        return state == BottomSheetBehavior.STATE_COLLAPSED ||
                state == BottomSheetBehavior.STATE_EXPANDED;
    }

    private boolean isOrientationLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    public void setResultAndFinishSelf() {
        setResult(Activity.RESULT_OK, getPickerResponseIntent(mSelection.canSelectMultiple(),
                mSelection.getSelectedItems()));

        logPickerSelectionConfirmed(mSelection.getSelectedItems().size());
        finishWithoutLoggingCancelledResult();
    }

    /**
     * This should be called if:
     * * We are finishing Picker explicitly before the user has seen PhotoPicker UI due to known
     *   checks/workflow.
     * * We are not returning {@link Activity#RESULT_CANCELED}
     */
    private void finishWithoutLoggingCancelledResult() {
        mShouldLogCancelledResult = false;
        finish();
    }

    @Override
    public void finish() {
        if (mShouldLogCancelledResult) {
            logPickerCancelled();
        }
        super.finish();
    }

    private void logPickerSelectionConfirmed(int countOfItemsConfirmed) {
        mPickerViewModel.logPickerConfirm(Binder.getCallingUid(), getCallingPackage(),
                countOfItemsConfirmed);
    }

    private void logPickerCancelled() {
        mPickerViewModel.logPickerCancel(Binder.getCallingUid(), getCallingPackage());
    }

    /**
     * Updates the common views such as Title, Toolbar, Navigation bar, status bar and bottom sheet
     * behavior
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     * @param title the title to set for the Activity
     */
    public void updateCommonLayouts(LayoutModeUtils.Mode mode, String title) {
        updateTitle(title);
        updateToolbar(mode);
        updateStatusBarAndNavigationBar(mode);
        updateBottomSheetBehavior(mode);
        updateFragmentContainerViewPadding(mode);
        updateDragBarVisibility(mode);
        updatePrivacyTextVisibility(mode);
        // The bottom bar and profile button are not shown on preview, hide them in preview. We
        // handle the visibility of them in TabFragment. We don't need to make them shown in
        // non-preview page here.
        if (mode.isPreview) {
            mBottomBar.setVisibility(View.GONE);
            mProfileButton.setVisibility(View.GONE);
        }
    }

    private void updateTitle(String title) {
        setTitle(title);
    }

    /**
     * Updates the icons and show/hide the tab layout with {@code mode}.
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateToolbar(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        final boolean shouldShowTabLayout = mode.isPhotosTabOrAlbumsTab;
        // 1. Set the tabLayout visibility
        mTabLayout.setVisibility(shouldShowTabLayout ? View.VISIBLE : View.GONE);

        // 2. Set the toolbar color
        final ColorDrawable toolbarColor;
        if (isPreview && !shouldShowTabLayout) {
            if (isOrientationLandscape()) {
                // Toolbar in Preview will have transparent color in Landscape mode.
                toolbarColor = new ColorDrawable(getColor(android.R.color.transparent));
            } else {
                // Toolbar in Preview will have a solid color with 90% opacity in Portrait mode.
                toolbarColor = new ColorDrawable(getColor(R.color.preview_scrim_solid_color));
            }
        } else {
            toolbarColor = new ColorDrawable(mDefaultBackgroundColor);
        }
        getSupportActionBar().setBackgroundDrawable(toolbarColor);

        // 3. Set the toolbar icon.
        final Drawable icon;
        if (shouldShowTabLayout) {
            icon = getDrawable(R.drawable.ic_close);
        } else {
            icon = getDrawable(R.drawable.ic_arrow_back);
            // Preview mode has dark background, hence icons will be WHITE in color
            icon.setTint(isPreview ? Color.WHITE : mToolBarIconColor);
        }
        getSupportActionBar().setHomeAsUpIndicator(icon);
        getSupportActionBar().setHomeActionContentDescription(
                shouldShowTabLayout ? android.R.string.cancel
                        : R.string.abc_action_bar_up_description);
        if (mToolbar.getOverflowIcon() != null) {
            mToolbar.getOverflowIcon().setTint(isPreview ? Color.WHITE : mToolBarIconColor);
        }
    }

    /**
     * Updates status bar and navigation bar
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateStatusBarAndNavigationBar(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        final int navigationBarColor = isPreview ? getColor(R.color.preview_background_color) :
                mDefaultBackgroundColor;
        getWindow().setNavigationBarColor(navigationBarColor);

        final int statusBarColor = isPreview ? getColor(R.color.preview_background_color) :
                getColor(android.R.color.transparent);
        getWindow().setStatusBarColor(statusBarColor);

        // Update the system bar appearance
        final int mask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        int appearance = 0;
        if (!isPreview) {
            final int uiModeNight =
                    getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

            if (uiModeNight == Configuration.UI_MODE_NIGHT_NO) {
                // If the system is not in Dark theme, set the system bars to light mode.
                appearance = mask;
            }
        }
        getWindow().getInsetsController().setSystemBarsAppearance(appearance, mask);
    }

    /**
     * Updates the bottom sheet behavior
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateBottomSheetBehavior(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        if (mBottomSheetView != null) {
            mBottomSheetView.setClipToOutline(!isPreview);
            // TODO(b/197241815): Add animation downward swipe for preview should go back to
            // the photo in photos grid
            mBottomSheetBehavior.setDraggable(!isPreview);
        }
        if (isPreview) {
            if (mBottomSheetBehavior.getState() != BottomSheetBehavior.STATE_EXPANDED) {
                // Sets bottom sheet behavior state to STATE_EXPANDED if it's not already expanded.
                // This is useful when user goes to Preview mode which is always Full screen.
                // TODO(b/197241815): Add animation preview to full screen and back transition to
                // partial screen. This is similar to long press animation.
                mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        } else {
            restoreBottomSheetState();
        }
    }

    /**
     * Updates the FragmentContainerView padding.
     * <p>
     * For Preview mode, toolbar overlaps the Fragment content, hence the padding will be set to 0.
     * For Non-Preview mode, toolbar doesn't overlap the contents of the fragment, hence we set the
     * padding as the height of the toolbar.
     */
    private void updateFragmentContainerViewPadding(@NonNull LayoutModeUtils.Mode mode) {
        if (mFragmentContainerView == null) return;

        final int topPadding;
        if (mode.isPreview) {
            topPadding = 0;
        } else {
            topPadding = mToolbarHeight;
        }

        mFragmentContainerView.setPadding(mFragmentContainerView.getPaddingLeft(),
                topPadding, mFragmentContainerView.getPaddingRight(),
                mFragmentContainerView.getPaddingBottom());
    }

    private void updateDragBarVisibility(@NonNull LayoutModeUtils.Mode mode) {
        final boolean shouldShowDragBar = !mode.isPreview;
        mDragBar.setVisibility(shouldShowDragBar ? View.VISIBLE : View.GONE);
    }

    private void updatePrivacyTextVisibility(@NonNull LayoutModeUtils.Mode mode) {
        // The privacy text is only shown on the Photos tab and Albums tab
        final boolean shouldShowPrivacyMessage = mode.isPhotosTabOrAlbumsTab;
        mPrivacyText.setVisibility(shouldShowPrivacyMessage ? View.VISIBLE : View.GONE);
    }

    private class CrossProfileListeners {

        private final List<String> MANAGED_PROFILE_FILTER_ACTIONS = Lists.newArrayList(
                Intent.ACTION_MANAGED_PROFILE_ADDED, // add profile button switch
                Intent.ACTION_MANAGED_PROFILE_REMOVED, // remove profile button switch
                Intent.ACTION_MANAGED_PROFILE_UNLOCKED, // activate profile button switch
                Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE // disable profile button switch
        );

        private final UserIdManager mUserIdManager;

        public CrossProfileListeners() {
            mUserIdManager = mPickerViewModel.getUserIdManager();

            registerBroadcastReceivers();
        }

        public void onDestroy() {
            unregisterReceiver(mReceiver);
        }

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                final UserHandle userHandle = intent.getParcelableExtra(Intent.EXTRA_USER);
                final UserId userId = UserId.of(userHandle);

                // We only need to refresh the layout when the received profile user is the
                // managed user corresponding to the current profile or a new work profile is added
                // for the current user.
                if (!userId.equals(mUserIdManager.getManagedUserId()) &&
                        !action.equals(Intent.ACTION_MANAGED_PROFILE_ADDED)) {
                    return;
                }

                switch (action) {
                    case Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE:
                        handleWorkProfileOff();
                        break;
                    case Intent.ACTION_MANAGED_PROFILE_REMOVED:
                        handleWorkProfileRemoved();
                        break;
                    case Intent.ACTION_MANAGED_PROFILE_UNLOCKED:
                        handleWorkProfileOn();
                        break;
                    case Intent.ACTION_MANAGED_PROFILE_ADDED:
                        handleWorkProfileAdded();
                        break;
                    default:
                        // do nothing
                }
            }
        };

        private void registerBroadcastReceivers() {
            final IntentFilter managedProfileFilter = new IntentFilter();
            for (String managedProfileAction : MANAGED_PROFILE_FILTER_ACTIONS) {
                managedProfileFilter.addAction(managedProfileAction);
            }
            registerReceiver(mReceiver, managedProfileFilter);
        }

        private void handleWorkProfileOff() {
            if (mUserIdManager.isManagedUserSelected()) {
                switchToPersonalProfileInitialLaunchState();
            }
            mUserIdManager.updateWorkProfileOffValue();
        }

        private void handleWorkProfileRemoved() {
            if (mUserIdManager.isManagedUserSelected()) {
                switchToPersonalProfileInitialLaunchState();
            }
            mUserIdManager.resetUserIds();
        }

        private void handleWorkProfileAdded() {
            mUserIdManager.resetUserIds();
        }

        private void handleWorkProfileOn() {
            // Update UI for switch to profile button
            // When the managed profile becomes available, the provider may not be available
            // immediately, we need to check if it is ready before we reload the content.
            mUserIdManager.waitForMediaProviderToBeAvailable();
        }

        private void switchToPersonalProfileInitialLaunchState() {
            final FragmentManager fragmentManager = getSupportFragmentManager();
            // Clear all back stacks in FragmentManager
            fragmentManager.popBackStackImmediate(/* name */ null,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE);

            // We reset the state of the PhotoPicker as we do not want to make any
            // assumptions on the state of the PhotoPicker when it was in Work Profile mode.
            resetToPersonalProfile();
        }

        /**
         * Reset to Photo Picker initial launch state (Photos grid tab) in personal profile mode.
         */
        private void resetToPersonalProfile() {
            mPickerViewModel.resetToPersonalProfile();
            setupInitialLaunchState();
        }
    }
}
