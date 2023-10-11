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

package com.android.providers.media.photopicker.espresso;

import static androidx.test.InstrumentationRegistry.getTargetContext;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;

import androidx.core.util.Supplier;
import androidx.test.InstrumentationRegistry;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.UserId;

import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PhotoPickerBaseTest {
    protected static final int PICKER_TAB_RECYCLERVIEW_ID = R.id.picker_tab_recyclerview;
    protected static final int TAB_LAYOUT_ID = R.id.tab_layout;
    protected static final int PICKER_PHOTOS_STRING_ID = R.string.picker_photos;
    protected static final int PICKER_ALBUMS_STRING_ID = R.string.picker_albums;
    protected static final int PREVIEW_VIEW_PAGER_ID = R.id.preview_viewPager;
    protected static final int ICON_CHECK_ID = R.id.icon_check;
    protected static final int ICON_THUMBNAIL_ID = R.id.icon_thumbnail;
    protected static final int VIEW_SELECTED_BUTTON_ID = R.id.button_view_selected;
    protected static final int PREVIEW_IMAGE_VIEW_ID = R.id.preview_imageView;
    protected static final int DRAG_BAR_ID = R.id.drag_bar;
    protected static final int PREVIEW_GIF_ID = R.id.preview_gif;
    protected static final int PREVIEW_MOTION_PHOTO_ID = R.id.preview_motion_photo;
    protected static final int PREVIEW_ADD_OR_SELECT_BUTTON_ID = R.id.preview_add_or_select_button;
    protected static final int PRIVACY_TEXT_ID = R.id.privacy_text;

    protected static final int DIMEN_PREVIEW_ADD_OR_SELECT_WIDTH
            = R.dimen.preview_add_or_select_width;

    /**
     * The position of the first image item in the grid on the Photos tab
     */
    protected static final int IMAGE_1_POSITION = 1;

    /**
     * The position of the second item in the grid on the Photos tab
     */
    protected static final int IMAGE_2_POSITION = 2;

    /**
     * The position of the video item in the grid on the Photos tab
     */
    protected static final int VIDEO_POSITION = 3;

    /**
     * The default position of a banner in the Photos & Albums tab recycler view adapters
     */
    static final int DEFAULT_BANNER_POSITION = 0;


    private static final Intent sSingleSelectIntent;
    static {
        sSingleSelectIntent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        sSingleSelectIntent.addCategory(Intent.CATEGORY_FRAMEWORK_INSTRUMENTATION_TEST);
    }

    private static final Intent sMultiSelectionIntent;
    static {
        sMultiSelectionIntent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        Bundle extras = new Bundle();
        extras.putInt(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        sMultiSelectionIntent.addCategory(Intent.CATEGORY_FRAMEWORK_INSTRUMENTATION_TEST);
        sMultiSelectionIntent.putExtras(extras);
    }

    private static final Intent sUserSelectImagesForAppIntent;
    static {
        sUserSelectImagesForAppIntent = new Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP);
        sUserSelectImagesForAppIntent.addCategory(Intent.CATEGORY_FRAMEWORK_INSTRUMENTATION_TEST);
        Bundle extras = new Bundle();
        extras.putInt(Intent.EXTRA_UID, 1234);
        sUserSelectImagesForAppIntent.putExtras(extras);
    }

    private static final File IMAGE_1_FILE = new File(Environment.getExternalStorageDirectory(),
            Environment.DIRECTORY_DCIM + "/Camera"
                    + "/image_" + System.currentTimeMillis() + ".jpeg");
    private static final File IMAGE_2_FILE = new File(Environment.getExternalStorageDirectory(),
            Environment.DIRECTORY_DOWNLOADS + "/image_" + System.currentTimeMillis() + ".jpeg");
    private static final File VIDEO_FILE = new File(Environment.getExternalStorageDirectory(),
            Environment.DIRECTORY_MOVIES + "/video_" + System.currentTimeMillis() + ".mp4");

    private static final long POLLING_TIMEOUT_MILLIS_LONG = TimeUnit.SECONDS.toMillis(2);
    private static final long POLLING_SLEEP_MILLIS = 200;

    private static IsolatedContext sIsolatedContext;
    private static UserIdManager sUserIdManager;

    public static Intent getSingleSelectMimeTypeFilterIntent(String mimeTypeFilter) {
        final Intent intent = new Intent(sSingleSelectIntent);
        intent.setType(mimeTypeFilter);
        return intent;
    }

    public static Intent getSingleSelectionIntent() {
        return sSingleSelectIntent;
    }

    public static Intent getMultiSelectionIntent() {
        return sMultiSelectionIntent;
    }

    public static Intent getUserSelectImagesForAppIntent() {
        return sUserSelectImagesForAppIntent;
    }

    public static Intent getMultiSelectionIntent(int max) {
        final Intent intent = new Intent(sMultiSelectionIntent);
        Bundle extras = new Bundle();
        extras.putInt(MediaStore.EXTRA_PICK_IMAGES_MAX, max);
        intent.putExtras(extras);
        return intent;
    }

    public static IsolatedContext getIsolatedContext() {
        return sIsolatedContext;
    }

    public static UserIdManager getMockUserIdManager() {
        return sUserIdManager;
    }

    @BeforeClass
    public static void setupClass() throws Exception {
        MediaStore.waitForIdle(getTargetContext().getContentResolver());
        pollForCondition(() -> isExternalStorageStateMounted(), "Timed out while"
                + " waiting for ExternalStorageState to be MEDIA_MOUNTED");

        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        Manifest.permission.READ_DEVICE_CONFIG);

        sIsolatedContext = new IsolatedContext(getTargetContext(), "modern",
                /* asFuseThread */ false);

        sUserIdManager = mock(UserIdManager.class);
        when(sUserIdManager.getCurrentUserProfileId()).thenReturn(UserId.CURRENT_USER);

        createFiles();
    }

    @AfterClass
    public static void destroyClass() {
        deleteFiles(/* invalidateMediaStore */ false);

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    protected static void deleteFiles(boolean invalidateMediaStore) {
        deleteFile(IMAGE_1_FILE, invalidateMediaStore);
        deleteFile(IMAGE_2_FILE, invalidateMediaStore);
        deleteFile(VIDEO_FILE, invalidateMediaStore);
    }

    private static void deleteFile(File file, boolean invalidateMediaStore) {
        file.delete();
        if (invalidateMediaStore) {
            final Uri uri = MediaStore.scanFile(getIsolatedContext().getContentResolver(), file);
            assertThat(uri).isNull();
            // Force picker db sync for that db operation
            MediaStore.waitForIdle(getIsolatedContext().getContentResolver());
        }
    }

    private static void createFiles() throws Exception {
        long timeNow = System.currentTimeMillis();
        // Create files and change dateModified so that we can predict the recyclerView item
        // position. Set modified date ahead of time, so that even if other files are created,
        // the below files always have positions 1, 2 and 3.
        createFile(IMAGE_1_FILE, timeNow + 30000);
        createFile(IMAGE_2_FILE, timeNow + 20000);
        createFile(VIDEO_FILE, timeNow + 10000);
    }

    private static void pollForCondition(Supplier<Boolean> condition, String errorMessage)
            throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS_LONG / POLLING_SLEEP_MILLIS; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        throw new TimeoutException(errorMessage);
    }

    private static boolean isExternalStorageStateMounted() {
        final File target = Environment.getExternalStorageDirectory();
        try {
            return (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(target))
                    && Os.statvfs(target.getAbsolutePath()).f_blocks > 0);
        } catch (ErrnoException ignored) {
        }
        return false;
    }

    private static void createFile(File file, long dateModified) throws IOException {
        File parentFile = file.getParentFile();
        parentFile.mkdirs();

        assertThat(parentFile.exists()).isTrue();
        assertThat(file.createNewFile()).isTrue();
        // Write 1 byte because 0byte files are not valid in the picker db
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(1);
        }

        // Change dateModified so that we can predict the recyclerView item position
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(dateModified));

        final Uri uri = MediaStore.scanFile(getIsolatedContext().getContentResolver(), file);
        MediaStore.waitForIdle(getIsolatedContext().getContentResolver());
        assertThat(uri).isNotNull();
    }

    /**
     * Mock UserIdManager class such that the profile button is active and the user is in personal
     * profile.
     */
    static void setUpActiveProfileButton() {
        when(sUserIdManager.isMultiUserProfiles()).thenReturn(true);
        when(sUserIdManager.isBlockedByAdmin()).thenReturn(false);
        when(sUserIdManager.isWorkProfileOff()).thenReturn(false);
        when(sUserIdManager.isCrossProfileAllowed()).thenReturn(true);
        when(sUserIdManager.isManagedUserSelected()).thenReturn(false);

        // setPersonalAsCurrentUserProfile() is called onClick of Active Profile Button to change
        // profiles
        doAnswer(invocation -> {
            updateIsManagedUserSelected(/* isManagedUserSelected */ false);
            return null;
        }).when(sUserIdManager).setPersonalAsCurrentUserProfile();

        // setManagedAsCurrentUserProfile() is called onClick of Active Profile Button to change
        // profiles
        doAnswer(invocation -> {
            updateIsManagedUserSelected(/* isManagedUserSelected */ true);
            return null;
        }).when(sUserIdManager).setManagedAsCurrentUserProfile();
    }

    /**
     * Mock UserIdManager class such that the user is in personal profile and work apps are
     * turned off
     */
    static void setUpWorkAppsOffProfileButton() {
        when(sUserIdManager.isMultiUserProfiles()).thenReturn(true);
        when(sUserIdManager.isBlockedByAdmin()).thenReturn(false);
        when(sUserIdManager.isWorkProfileOff()).thenReturn(true);
        when(sUserIdManager.isCrossProfileAllowed()).thenReturn(false);
        when(sUserIdManager.isManagedUserSelected()).thenReturn(false);
    }

    /**
     * Mock UserIdManager class such that the user is in work profile and accessing personal
     * profile content is blocked by admin
     */
    static void setUpBlockedByAdminProfileButton() {
        when(sUserIdManager.isMultiUserProfiles()).thenReturn(true);
        when(sUserIdManager.isBlockedByAdmin()).thenReturn(true);
        when(sUserIdManager.isWorkProfileOff()).thenReturn(false);
        when(sUserIdManager.isCrossProfileAllowed()).thenReturn(false);
        when(sUserIdManager.isManagedUserSelected()).thenReturn(true);
    }

    private static void updateIsManagedUserSelected(boolean isManagedUserSelected) {
        when(sUserIdManager.isManagedUserSelected()).thenReturn(isManagedUserSelected);
    }
}
