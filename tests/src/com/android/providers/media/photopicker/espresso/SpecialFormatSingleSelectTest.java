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

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemDisplayed;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.assertItemNotDisplayed;
import static com.android.providers.media.photopicker.espresso.RecyclerViewTestUtils.longClickItem;
import static com.android.providers.media.scan.MediaScannerTest.stage;
import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.not;

import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.providers.media.R;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class SpecialFormatSingleSelectTest extends PhotoPickerBaseTest {
    private static final int ICON_GIF_ID = R.id.icon_gif;
    private static final int ICON_MOTION_PHOTO_ID = R.id.icon_motion_photo;
    private static final int VIDEO_CONTAINER_ID = R.id.video_container;
    private static final int OVERLAY_GRADIENT_ID = R.id.overlay_gradient;
    private static final int PREVIEW_GIF_ID = R.id.preview_gif;
    private static final int PREVIEW_MOTION_PHOTO_ID = R.id.preview_motion_photo;

    private static final File MOTION_PHOTO_FILE =
            new File(Environment.getExternalStorageDirectory(),
                    Environment.DIRECTORY_PICTURES
                            + "/motionphoto_" + System.currentTimeMillis() + ".jpeg");
    private static final File GIF_FILE = new File(Environment.getExternalStorageDirectory(),
            Environment.DIRECTORY_DOWNLOADS + "/gif_" + System.currentTimeMillis() + ".gif");

    /**
     * The position of the gif item in the grid on the Photos tab
     */
    private static final int GIF_POSITION = 4;

    /**
     * The position of the video item in the grid on the Photos tab
     */
    private static final int MOTION_PHOTO_POSITION = 5;

    @Rule
    public ActivityScenarioRule<PhotoPickerTestActivity> mRule
            = new ActivityScenarioRule<>(PhotoPickerBaseTest.getSingleSelectionIntent());

    @BeforeClass
    public static void setupClass() throws Exception {
        PhotoPickerBaseTest.setupClass();
        createSpecialFormatFiles();
    }

    @AfterClass
    public static void destroyClass() {
        PhotoPickerBaseTest.destroyClass();
        MOTION_PHOTO_FILE.delete();
        GIF_FILE.delete();
    }

    protected static void createSpecialFormatFiles() throws Exception {
        createFile(MOTION_PHOTO_FILE, R.raw.test_motion_photo);
        createFile(GIF_FILE, R.raw.test_gif);
    }

    @Test
    public void testPhotoGridLayout_motionPhoto() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Verify we have the thumbnail
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_THUMBNAIL_ID);
        // Verify motion photo icon is displayed
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, OVERLAY_GRADIENT_ID);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION,
                ICON_MOTION_PHOTO_ID);

        // Verify check icon, video icon and gif icon are not displayed
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_CHECK_ID);
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION,
                VIDEO_CONTAINER_ID);
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_GIF_ID);
    }

    @Test
    public void testPhotoGridLayout_gif() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Verify we have the thumbnail
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);
        // Verify gif icon is displayed
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, OVERLAY_GRADIENT_ID);
        assertItemDisplayed(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_GIF_ID);

        // Verify check icon, video icon and motion photo icon are not displayed
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_CHECK_ID);
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, VIDEO_CONTAINER_ID);
        assertItemNotDisplayed(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_MOTION_PHOTO_ID);
    }

    @Test
    public void testPreview_singleSelect_gif() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, GIF_POSITION, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Verify gif icon is displayed for gif preview
        assertSingleSelectCommonLayoutMatches();
        onView(withId(PREVIEW_GIF_ID)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(doesNotExist());
    }

    @Test
    public void testPreview_singleSelect_motionPhoto() throws Exception {
        onView(withId(PICKER_TAB_RECYCLERVIEW_ID)).check(matches(isDisplayed()));

        // Navigate to preview
        longClickItem(PICKER_TAB_RECYCLERVIEW_ID, MOTION_PHOTO_POSITION, ICON_THUMBNAIL_ID);

        registerIdlingResourceAndWaitForIdle();

        // Verify motion photo icon is displayed for motion photo preview
        assertSingleSelectCommonLayoutMatches();
        onView(withId(PREVIEW_MOTION_PHOTO_ID)).check(matches(isDisplayed()));
        onView(withId(R.id.preview_imageView)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_GIF_ID)).check(doesNotExist());
    }

    private static void createFile(File file, int resId) throws IOException {
        File parentFile = file.getParentFile();
        parentFile.mkdirs();

        assertThat(parentFile.exists()).isTrue();
        file = stage(resId, file);
        assertThat(file.exists()).isTrue();

        final Uri uri = MediaStore.scanFile(getIsolatedContext().getContentResolver(), file);
        MediaStore.waitForIdle(getIsolatedContext().getContentResolver());
        assertThat(uri).isNotNull();
    }

    private void registerIdlingResourceAndWaitForIdle() {
        mRule.getScenario().onActivity((activity -> IdlingRegistry.getInstance().register(
                new ViewPager2IdlingResource(activity.findViewById(R.id.preview_viewPager)))));
        Espresso.onIdle();
    }

    private void assertSingleSelectCommonLayoutMatches() {
        onView(withId(R.id.preview_viewPager)).check(matches(isDisplayed()));
        onView(withId(PREVIEW_ADD_OR_SELECT_BUTTON_ID)).check(matches(isDisplayed()));
        // Verify that the text in Add button
        onView(withId(PREVIEW_ADD_OR_SELECT_BUTTON_ID)).check(matches(withText(R.string.add)));

        onView(withId(R.id.preview_select_check_button)).check(matches(not(isDisplayed())));
        onView(withId(R.id.preview_add_button)).check(matches(not(isDisplayed())));
    }
}
