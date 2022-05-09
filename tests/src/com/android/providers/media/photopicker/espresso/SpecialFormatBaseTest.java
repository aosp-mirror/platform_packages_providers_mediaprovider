/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.providers.media.scan.MediaScannerTest.stage;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.test.espresso.Espresso;
import androidx.test.espresso.IdlingRegistry;

import com.android.providers.media.R;

import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.File;
import java.io.IOException;

public class SpecialFormatBaseTest extends PhotoPickerBaseTest {

    protected static final int ICON_GIF_ID = R.id.icon_gif;
    protected static final int ICON_MOTION_PHOTO_ID = R.id.icon_motion_photo;
    protected static final int VIDEO_CONTAINER_ID = R.id.video_container;
    protected static final int OVERLAY_GRADIENT_ID = R.id.overlay_gradient;
    protected static final int PREVIEW_GIF_ID = R.id.preview_gif;
    protected static final int PREVIEW_MOTION_PHOTO_ID = R.id.preview_motion_photo;

    protected static final File MOTION_PHOTO_FILE =
            new File(Environment.getExternalStorageDirectory(),
                    Environment.DIRECTORY_PICTURES
                            + "/motionphoto_" + System.currentTimeMillis() + ".jpeg");
    protected static final File GIF_FILE = new File(Environment.getExternalStorageDirectory(),
            Environment.DIRECTORY_DOWNLOADS + "/gif_" + System.currentTimeMillis() + ".gif");
    protected static final File ANIMATED_WEBP_FILE = new File(
            Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS
            + "/animatedWebp_" + System.currentTimeMillis() + ".webp");
    protected static final File NON_ANIMATED_WEBP_FILE = new File(
            Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS
            + "/nonAnimatedWebp_" + System.currentTimeMillis() + ".webp");

    /**
     * The position of the gif item in the grid on the Photos tab
     */
    protected static final int GIF_POSITION = 1;

    /**
     * The position of the animated webp item in the grid on the Photos tab
     */
    protected static final int ANIMATED_WEBP_POSITION = 2;

    /**
     * The position of the motion photo item in the grid on the Photos tab
     */
    protected static final int MOTION_PHOTO_POSITION = 3;

    /**
     * The position of the non-animated webp item in the grid on the Photos tab
     */
    protected static final int NON_ANIMATED_WEBP_POSITION = 4;

    @BeforeClass
    public static void setupClass() throws Exception {
        PhotoPickerBaseTest.setupClass();
        // Reduce the number of files for this test. This is to reduce the dependency on other
        // PhotoPicker components. We don't need to swipe up bottomsheet to view more files.
        deleteFiles(/* invalidateMediaStore */ true);
        createSpecialFormatFiles();
    }

    @AfterClass
    public static void destroyClass() {
        PhotoPickerBaseTest.destroyClass();
        NON_ANIMATED_WEBP_FILE.delete();
        ANIMATED_WEBP_FILE.delete();
        MOTION_PHOTO_FILE.delete();
        GIF_FILE.delete();
    }

    private static void createSpecialFormatFiles() throws Exception {
        createFile(NON_ANIMATED_WEBP_FILE, R.raw.test_non_animated_webp);
        createFile(ANIMATED_WEBP_FILE, R.raw.test_animated_webp);
        createFile(MOTION_PHOTO_FILE, R.raw.test_motion_photo);
        createFile(GIF_FILE, R.raw.test_gif);
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
}
