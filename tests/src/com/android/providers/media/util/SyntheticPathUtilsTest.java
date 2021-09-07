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

package com.android.providers.media.util;

import static com.android.providers.media.util.SyntheticPathUtils.extractSyntheticRelativePathSegements;
import static com.android.providers.media.util.SyntheticPathUtils.getPickerRelativePath;
import static com.android.providers.media.util.SyntheticPathUtils.getRedactedRelativePath;
import static com.android.providers.media.util.SyntheticPathUtils.getSyntheticRelativePath;
import static com.android.providers.media.util.SyntheticPathUtils.isPickerPath;
import static com.android.providers.media.util.SyntheticPathUtils.isRedactedPath;
import static com.android.providers.media.util.SyntheticPathUtils.isSyntheticPath;
import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SyntheticPathUtilsTest {
    // Needs to match the redacted ids specification in SyntheticPathUtilsTest#REDACTED_URI_ID_SIZE
    private static final String REDACTED_ID = "ruidaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    public void testGetSyntheticRelativePath() throws Exception {
        assertThat(getSyntheticRelativePath()).isEqualTo(".transforms/synthetic");
    }

    @Test
    public void testGetRedactedRelativePath() throws Exception {
        assertThat(getRedactedRelativePath()).isEqualTo(".transforms/synthetic/redacted");
    }

    @Test
    public void testGetPickerRelativePath() throws Exception {
        assertThat(getPickerRelativePath()).isEqualTo(".transforms/synthetic/picker");
    }

    @Test
    public void testIsSyntheticPath() throws Exception {
        assertThat(isSyntheticPath("/storage/emulated/0/.transforms/synthetic", /* userId */ 0))
                .isTrue();
        assertThat(isSyntheticPath("/storage/emulated/10/.transforms/synthetic", /* userId */ 10))
                .isTrue();
        assertThat(isSyntheticPath("/storage/emulated/0/.transforms/SYNTHETIC/",/* userId */ 0))
                .isTrue();

        assertThat(isSyntheticPath("/storage/emulated/0/.transforms/synthetic", /* userId */ 10))
                .isFalse();
        assertThat(isSyntheticPath("/storage/emulated/10/.transforms/synthetic", /* userId */ 0))
                .isFalse();
        assertThat(isSyntheticPath("/storage/emulated/0/.transforms", /* userId */ 0)).isFalse();
        assertThat(isSyntheticPath("/storage/emulated/0/synthetic", /* userId */ 0)).isFalse();
    }

    @Test
    public void testIsRedactedPath() throws Exception {
        assertThat(isRedactedPath("/storage/emulated/0/.transforms/synthetic/redacted/"
                        + REDACTED_ID, /* userId */ 0)).isTrue();
        assertThat(isRedactedPath("/storage/emulated/10/.transforms/synthetic/redacted/"
                        + REDACTED_ID, /* userId */ 10)).isTrue();
        assertThat(isRedactedPath("/storage/emulated/0/.transforms/synthetic/REDACTED/"
                        + REDACTED_ID, /* userId */ 0)).isTrue();

        assertThat(isRedactedPath("/storage/emulated/0/.transforms/synthetic/redacted/"
                        + REDACTED_ID, /* userId */ 10)).isFalse();
        assertThat(isRedactedPath("/storage/emulated/10/.transforms/synthetic/redacted/"
                        + REDACTED_ID, /* userId */ 0)).isFalse();
        assertThat(isRedactedPath("/storage/emulated/0/.transforms/synthetic/picker/"
                        + REDACTED_ID, /* userId */ 0)).isFalse();
        assertThat(isRedactedPath("/storage/emulated/0/.transforms/redacted/" + REDACTED_ID,
                        /* userId */ 0)).isFalse();
        assertThat(isRedactedPath("/storage/emulated/0/synthetic/redacted/" + REDACTED_ID,
                        /* userId */ 0)).isFalse();
    }

    @Test
    public void testIsPickerPath() throws Exception {
        assertThat(isPickerPath("/storage/emulated/0/.transforms/synthetic/picker/foo",
                        /* userId */ 0)).isTrue();
        assertThat(isPickerPath("/storage/emulated/10/.transforms/synthetic/picker/foo",
                        /* userId */ 10)).isTrue();
        assertThat(isPickerPath("/storage/emulated/0/.transforms/synthetic/PICKER/bar/baz",
                        /* userId */ 0)).isTrue();

        assertThat(isPickerPath("/storage/emulated/0/.transforms/synthetic/picker/foo",
                        /* userId */ 10)).isFalse();
        assertThat(isPickerPath("/storage/emulated/10/.transforms/synthetic/picker/foo",
                        /* userId */ 0)).isFalse();
        assertThat(isPickerPath("/storage/emulated/0/.transforms/synthetic/redacted/foo",
                        /* userId */ 0)).isFalse();
        assertThat(isPickerPath("/storage/emulated/0/.transforms/picker/foo", /* userId */ 0))
                .isFalse();
        assertThat(isPickerPath("/storage/emulated/0/synthetic/picker/foo", /* userId */ 0))
                .isFalse();
    }

    @Test
    public void testExtractSyntheticRelativePathSegments() throws Exception {
        assertThat(extractSyntheticRelativePathSegements(
                        "/storage/emulated/10/.transforms/synthetic/picker",
                        /* userId */ 0)).isEmpty();
        assertThat(extractSyntheticRelativePathSegements(
                        "/storage/emulated/0/.transforms/synthetic",
                        /* userId */ 0)).isEmpty();

        assertThat(extractSyntheticRelativePathSegements(
                        "/storage/emulated/0/.transforms/synthetic/picker",
                        /* userId */ 0)).containsExactly("picker").inOrder();
        assertThat(extractSyntheticRelativePathSegements(
                        "/storage/emulated/0/.transforms/synthetic/picker/",
                        /* userId */ 0)).containsExactly("picker").inOrder();

        assertThat(extractSyntheticRelativePathSegements(
                        "/storage/emulated/0/.transforms/synthetic/picker/foo",
                        /* userId */ 0)).containsExactly("picker", "foo").inOrder();
        assertThat(extractSyntheticRelativePathSegements(
                        "/storage/emulated/0/.transforms/synthetic/picker//foo/",
                        /* userId */ 0)).containsExactly("picker", "foo").inOrder();

        assertThat(extractSyntheticRelativePathSegements(
                        "/storage/emulated/0/.transforms/synthetic/picker/foo/com.bar",
                        /* userId */ 0)).containsExactly("picker", "foo", "com.bar").inOrder();
    }
}
