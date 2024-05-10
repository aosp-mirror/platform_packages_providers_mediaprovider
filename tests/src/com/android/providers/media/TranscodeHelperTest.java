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

package com.android.providers.media;

import static com.google.common.truth.Truth.assertThat;

import android.media.ApplicationMediaCapabilities;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 31, codeName = "S")
public class TranscodeHelperTest {
    private static final String SOME_VALID_FILE_PATH =
            "/storage/emulated/0/" + Environment.DIRECTORY_DCIM + "/Camera/some_filename.mp4";

    private final ConfigStore mConfigStore = new TestConfigStore();
    private final MediaProvider mDefaultMediaProvider = new MediaProvider() {
        @Override
        protected ConfigStore provideConfigStore() {
            return mConfigStore;
        }
    };

    private final TranscodeHelperImpl mUnderTest = new TranscodeHelperImpl(
            InstrumentationRegistry.getTargetContext(), mDefaultMediaProvider, mConfigStore);

    @Test
    public void testSupportsValidTranscodePath() {
        List<String> filePaths = Arrays.asList(
                "/storage/emulated/0/" + Environment.DIRECTORY_DCIM + "/Camera/filename.mp4",
                "/storage/emulated/1/" + Environment.DIRECTORY_DCIM + "/Camera/filename.mp4",
                "/storage/emulated/0/dcim/camera/filename.mp4");

        for (String path : filePaths) {
            assertThat(mUnderTest.supportsTranscode(path)).isTrue();
        }
    }

    @Test
    public void testDoesNotSupportsInvalidTranscodePath() {
        List<String> filePaths = Arrays.asList(
                "/storage/emulated/ab/" + Environment.DIRECTORY_DCIM + "/Camera/filename.mp4",
                "/storage/emulated/0/" + Environment.DIRECTORY_DCIM + "/Camera/dir/filename.mp4",
                "/storage/emulate/" + Environment.DIRECTORY_DCIM + "/Camera/filename.mp4",
                "/storage/emulated/" + Environment.DIRECTORY_DCIM + "/Camera/filename.jpeg",
                "/storage/emulated/0/dcmi/Camera/dir/filename.mp4");

        for (String path : filePaths) {
            assertThat(mUnderTest.supportsTranscode(path)).isFalse();
        }
    }

    @Test
    public void testDoesNotTranscodeForMediaProvider() {
        int transcodeReason = mUnderTest.shouldTranscode(SOME_VALID_FILE_PATH,
                TranscodeHelperImpl.getMyUid(),
                null);
        assertThat(transcodeReason).isEqualTo(0);

        Random random = new Random(System.currentTimeMillis());
        Bundle bundle = new Bundle();
        bundle.putInt(MediaStore.EXTRA_MEDIA_CAPABILITIES_UID, TranscodeHelperImpl.getMyUid());
        int randomAppUid = random.nextInt(
                Process.LAST_APPLICATION_UID - Process.FIRST_APPLICATION_UID + 1)
                + Process.FIRST_APPLICATION_UID;
        transcodeReason = mUnderTest.shouldTranscode(SOME_VALID_FILE_PATH, randomAppUid, bundle);
        assertThat(transcodeReason).isEqualTo(0);
    }

    @Test
    public void testDoesNotTranscodeForSystemProcesses() {
        Random random = new Random(System.currentTimeMillis());
        int randomSystemProcessUid = random.nextInt(Process.FIRST_APPLICATION_UID);
        int transcodeReason = mUnderTest.shouldTranscode(SOME_VALID_FILE_PATH,
                randomSystemProcessUid, null);
        assertThat(transcodeReason).isEqualTo(0);
    }

    @Test
    public void testDoesNotTranscodeIfAppAcceptsOriginalFormat() {
        Random random = new Random(System.currentTimeMillis());
        Bundle bundle = new Bundle();
        bundle.putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true);
        int randomAppUid = random.nextInt(
                Process.LAST_APPLICATION_UID - Process.FIRST_APPLICATION_UID + 1)
                + Process.FIRST_APPLICATION_UID;
        int transcodeReason = mUnderTest.doesAppNeedTranscoding(randomAppUid, bundle,
                TranscodeHelperImpl.FLAG_HEVC, 0);
        assertThat(transcodeReason).isEqualTo(0);
    }

    @Test
    public void testDoesNotTranscodeIfAppExtraMediaCapabilitiesHevc_supported() {
        Random random = new Random(System.currentTimeMillis());
        ApplicationMediaCapabilities capabilities =
                new ApplicationMediaCapabilities.Builder().addSupportedVideoMimeType(
                        MediaFormat.MIMETYPE_VIDEO_HEVC).build();
        Bundle bundle = new Bundle();
        bundle.putParcelable(MediaStore.EXTRA_MEDIA_CAPABILITIES, capabilities);
        int randomAppUid = random.nextInt(
                Process.LAST_APPLICATION_UID - Process.FIRST_APPLICATION_UID + 1)
                + Process.FIRST_APPLICATION_UID;
        int transcodeReason = mUnderTest.doesAppNeedTranscoding(randomAppUid, bundle,
                TranscodeHelperImpl.FLAG_HEVC, 0);
        assertThat(transcodeReason).isEqualTo(0);
    }

    @Test
    public void testTranscodesIfAppExtraMediaCapabilitiesHevc_unsupported() {
        Random random = new Random(System.currentTimeMillis());
        ApplicationMediaCapabilities capabilities =
                new ApplicationMediaCapabilities.Builder().addUnsupportedVideoMimeType(
                        MediaFormat.MIMETYPE_VIDEO_HEVC).build();
        Bundle bundle = new Bundle();
        bundle.putParcelable(MediaStore.EXTRA_MEDIA_CAPABILITIES, capabilities);
        int randomAppUid = random.nextInt(
                Process.LAST_APPLICATION_UID - Process.FIRST_APPLICATION_UID + 1)
                + Process.FIRST_APPLICATION_UID;
        int transcodeReason = mUnderTest.doesAppNeedTranscoding(randomAppUid, bundle,
                TranscodeHelperImpl.FLAG_HEVC, 0);
        assertThat(transcodeReason).isEqualTo(
                MediaProviderStatsLog.TRANSCODING_DATA__ACCESS_REASON__APP_EXTRA);
    }
}
