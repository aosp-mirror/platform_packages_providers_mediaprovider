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

import static android.provider.MediaStore.Files.EXTERNAL_CONTENT_URI;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.FileUtils;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.util.IsoInterface;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@RunWith(AndroidJUnit4.class)
public class PhotoPickerSingleSelectTest {
    private static final String TAG = "PhotoPickerSingleSelectTest";
    private static Context sContext;
    private static ContentResolver sContentResolver;
    private static List<Uri> sUris = new ArrayList<>();

    @BeforeClass
    public static void setup() throws Exception {
        prepareDevice();
        sContext = InstrumentationRegistry.getTargetContext();
        sContentResolver = sContext.getContentResolver();
    }

    @AfterClass
    public static void teardown() throws IOException {
        for (Uri uri : sUris) {
            deleteMedia(uri, sContext.getUserId());
        }
    }

    @Test
    public void testSimple_image() throws Exception {
        createImages(1);

        try (ActivityScenario<PhotoPickerActivity> scenario =
                     ActivityScenario.launch(PhotoPickerActivity.class)) {
            // Verify activity has toolbar and photo fragment.
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()));
            onView(withId(R.id.fragment_container)).check(matches(isDisplayed()));

            // This should launch Preview Fragment for single select
            onView(withId(R.id.photo_list))
                    .perform(RecyclerViewActions.actionOnItemAtPosition(1, click()));

            // Verify the preview buttons are displayed
            onView(withId(R.id.preview_viewPager)).check(matches(isDisplayed()));
            onView(withId(R.id.preview_add_button)).check(matches(isDisplayed()));

            onView(withId(R.id.preview_add_button)).perform(click());

            final ActivityResult result = scenario.getResult();
            assertThat(result.getResultCode()).isEqualTo(Activity.RESULT_OK);
            final Uri uri = result.getResultData().getData();
            assertOnlyRedactedReadAccess(uri, /* isImage */ true);
        }
    }

    @Test
    public void testSimple_video() throws Exception {
        createVideos(1);

        try (ActivityScenario<PhotoPickerActivity> scenario =
                     ActivityScenario.launch(PhotoPickerActivity.class)) {
            // Verify activity has toolbar and photo fragment.
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()));
            onView(withId(R.id.fragment_container)).check(matches(isDisplayed()));

            // This should launch Preview Fragment for single select
            // TODO(b/168785304): Add more check video specific checks like badge and playback.
            onView(withId(R.id.photo_list))
                    .perform(RecyclerViewActions.actionOnItemAtPosition(1, click()));

            // Verify the preview buttons are displayed
            onView(withId(R.id.preview_viewPager)).check(matches(isDisplayed()));
            onView(withId(R.id.preview_add_button)).check(matches(isDisplayed()));

            onView(withId(R.id.preview_add_button)).perform(click());

            final ActivityResult result = scenario.getResult();
            assertThat(result.getResultCode()).isEqualTo(Activity.RESULT_OK);
            final Uri uri = result.getResultData().getData();
            assertOnlyRedactedReadAccess(uri, /* isImage */ false);
        }
    }

    @Test
    public void testSimple_gif() throws Exception {
        createGifs(1);

        try (ActivityScenario<PhotoPickerActivity> scenario =
                     ActivityScenario.launch(PhotoPickerActivity.class)) {
            // Verify activity has toolbar and photo fragment.
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()));
            onView(withId(R.id.fragment_container)).check(matches(isDisplayed()));

            // This should launch Preview Fragment for single select
            // TODO(b/168785304): Add more check GIF specific checks like badge and playback.
            onView(withId(R.id.photo_list))
                    .perform(RecyclerViewActions.actionOnItemAtPosition(1, click()));

            // Verify the preview buttons are displayed
            onView(withId(R.id.preview_viewPager)).check(matches(isDisplayed()));
            onView(withId(R.id.preview_add_button)).check(matches(isDisplayed()));

            onView(withId(R.id.preview_add_button)).perform(click());

            final ActivityResult result = scenario.getResult();
            assertThat(result.getResultCode()).isEqualTo(Activity.RESULT_OK);
            final Uri uri = result.getResultData().getData();
            assertOnlyRedactedReadAccess(uri, /* isImage */ true);
        }
    }

    private static void prepareDevice() {
        // Unlock screen.
        runShellCommand("input keyevent KEYCODE_WAKEUP");
        // Dismiss keyguard, in case it's set as "Swipe to unlock".
        runShellCommand("wm dismiss-keyguard");
    }

    private void createImages(int num) throws Exception {
        createMedia(PhotoPickerSingleSelectTest::createImage, num);
    }

    private void createVideos(int num) throws Exception {
        createMedia(PhotoPickerSingleSelectTest::createVideo, num);
    }

    private void createGifs(int num) throws Exception {
        createMedia(PhotoPickerSingleSelectTest::createGif, num);
    }

    private void createMedia(Callable<Uri> create, int num) throws Exception {
        for (int i = 0 ; i < num ; i++) {
            final Uri uri = create.call();
            sUris.add(uri);
            clearMediaOwner(uri, sContext.getUserId());
        }
    }

    private static Uri createVideo() throws Exception {
        final long now = System.nanoTime();
        final String displayName = TAG + now + ".mp4";
        final ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        final Uri uri = sContentResolver.insert(EXTERNAL_CONTENT_URI, cv);
        assertThat(uri).isNotNull();
        try (InputStream in = sContext.getResources().openRawResource(R.raw.test_video_gps);
             OutputStream out = sContentResolver.openOutputStream(uri)) {
            FileUtils.copy(in, out);
        }
        return uri;
    }

    private static Uri createGif() throws Exception {
        final long now = System.nanoTime();
        final String displayName = TAG + now + ".gif";
        final ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/gif");
        final Uri uri = sContentResolver.insert(EXTERNAL_CONTENT_URI, cv);
        assertThat(uri).isNotNull();
        try (InputStream in = sContext.getResources().openRawResource(R.raw.test_gif);
             OutputStream out = sContentResolver.openOutputStream(uri)) {
            FileUtils.copy(in, out);
        }
        return uri;
    }

    private static Uri createImage() throws Exception {
        final long now = System.nanoTime();
        final String displayName = TAG + now + ".png";
        final ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        final Uri uri = sContentResolver.insert(EXTERNAL_CONTENT_URI, cv);
        assertThat(uri).isNotNull();
        try (InputStream in = sContext.getResources().openRawResource(R.raw.test_image);
             OutputStream out = sContentResolver.openOutputStream(uri)) {
            FileUtils.copy(in, out);
        }
        return uri;
    }

    private void assertOnlyRedactedReadAccess(Uri uri, boolean isImage) throws Exception {
        // assert redacted read access via file descriptor
        final FileDescriptor fd = sContentResolver
                .openFileDescriptor(uri, "r").getFileDescriptor();
        assertThat(fd).isNotNull();

        // Check redaction ranges from fd
        if (isImage) {
            ExifInterface exifInterface = new ExifInterface(fd);
            assertThat(exifInterface.getGpsDateTime()).isEqualTo(-1);
        } else {
            IsoInterface isoInterface = IsoInterface.fromFileDescriptor(fd);
            assertThat(isoInterface.getBoxBytes(IsoInterface.BOX_GPS)).isNull();
        }

        // assert redacted Read access via filepath
        final Cursor c = sContentResolver.query(uri, new String[]{MediaStore.MediaColumns.DATA},
                null, null);
        assertThat(c).isNotNull();
        assertThat(c.moveToFirst()).isTrue();
        final String path = c.getString(0);

        // Check redaction ranges from file path
        if (isImage) {
            final ExifInterface exifInterface = new ExifInterface(path);
            assertThat(exifInterface.getGpsDateTime()).isEqualTo(-1);
        } else {
            final IsoInterface isoInterface = IsoInterface.fromFile(new File(path));
            assertThat(isoInterface.getBoxBytes(IsoInterface.BOX_GPS)).isNull();
        }

        // assert no write access
        try {
            sContentResolver.openFileDescriptor(uri, "w").getFileDescriptor();
            fail("Photo Picker does not grant write access to Uris");
        } catch (Exception expected) {

        }
    }

    private static void clearMediaOwner(Uri uri, int userId) {
        final String cmd = String.format(
                "content update --uri %s --user %d --bind owner_package_name:n:", uri, userId);
        runShellCommand(cmd);
    }

    private static void deleteMedia(Uri uri, int userId) {
        final String cmd = String.format("content delete --uri %s --user %d", uri, userId);
        runShellCommand(cmd);
    }
}