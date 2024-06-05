/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.net.Uri;

import org.junit.Test;

/**
 * This class exists mostly for code coverage purposes
 */
public class TranscodeHelperNoOpTest {

    private static final String TEST_STRING = "foo";
    private final TranscodeHelperNoOp mTranscodeHelper = new TranscodeHelperNoOp();

    @Test
    public void testBasicFunctionality() {
        mTranscodeHelper.freeCache(1L);
        mTranscodeHelper.onAnrDelayStarted(TEST_STRING, 0, 0, 0);
        assertThat(mTranscodeHelper.transcode(TEST_STRING, TEST_STRING, 0, 0)).isFalse();
        assertThat(mTranscodeHelper.prepareIoPath(TEST_STRING, 0)).isNull();
        assertThat(mTranscodeHelper.shouldTranscode(TEST_STRING, 0, null)).isEqualTo(0);
        mTranscodeHelper.onUriPublished(Uri.EMPTY);
        assertThat(mTranscodeHelper.supportsTranscode(TEST_STRING)).isFalse();
        mTranscodeHelper.onUriPublished(Uri.EMPTY);
        mTranscodeHelper.onFileOpen(TEST_STRING, TEST_STRING, 0, 0);
        assertThat(mTranscodeHelper.isTranscodeFileCached(TEST_STRING, TEST_STRING)).isFalse();
        assertThat(mTranscodeHelper.deleteCachedTranscodeFile(1L)).isFalse();
        mTranscodeHelper.dump(null);
        assertThat(mTranscodeHelper.getSupportedRelativePaths()).isEmpty();
    }
}
