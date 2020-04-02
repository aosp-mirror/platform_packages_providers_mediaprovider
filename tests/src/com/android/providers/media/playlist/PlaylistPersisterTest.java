/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.providers.media.playlist;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Straightforward test that exercises all rewriters by verifying that written
 * playlists can be successfully read.
 */
@RunWith(Parameterized.class)
public class PlaylistPersisterTest {
    /**
     * List of paths to be used for verifying playlist rewriters. Note that
     * first and last items are intentionally identical to verify they're
     * rewritten without being dropped.
     */
    private final List<Path> expected = Arrays.asList(
            new File("test.mp3").toPath(),
            new File("../parent/../test.mp3").toPath(),
            new File("subdir/test.mp3").toPath(),
            new File("/root/test.mp3").toPath(),
            new File("從不喜歡孤單一個 - 蘇永康／吳雨霏.mp3").toPath(),
            new File("test.mp3").toPath());

    @Parameter(0)
    public PlaylistPersister mPersister;

    @Parameters
    public static Iterable<? extends Object> data() {
        return Arrays.asList(
                new M3uPlaylistPersister(),
                new PlsPlaylistPersister(),
                new WplPlaylistPersister(),
                new XspfPlaylistPersister());
    }

    @Test
    public void testRewrite() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mPersister.write(out, expected);

        final ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        final List<Path> actual = new ArrayList<>();
        mPersister.read(in, actual);

        assertEquals(expected.toString(), actual.toString());
    }
}
