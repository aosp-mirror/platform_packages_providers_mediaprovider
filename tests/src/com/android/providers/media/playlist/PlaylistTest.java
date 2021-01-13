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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Path;

@RunWith(AndroidJUnit4.class)
public class PlaylistTest {
    private final Playlist playlist = new Playlist();

    private final Path RED = new File("red").toPath();
    private final Path GREEN = new File("../green").toPath();
    private final Path BLUE = new File("path/to/blue").toPath();
    private final Path YELLOW = new File("/root/yellow").toPath();

    @Before
    public void setUp() {
        playlist.clear();
    }

    @Test
    public void testAdd() throws Exception {
        assertPlaylistEquals();

        playlist.add(0, RED);
        assertPlaylistEquals(RED);

        playlist.add(0, GREEN);
        assertPlaylistEquals(GREEN, RED);

        playlist.add(100, BLUE);
        assertPlaylistEquals(GREEN, RED, BLUE);
    }

    @Test
    public void testMove_Two() throws Exception {
        playlist.add(0, RED);
        playlist.add(1, GREEN);
        assertPlaylistEquals(RED, GREEN);

        playlist.move(0, 1);
        assertPlaylistEquals(GREEN, RED);
        playlist.move(0, 1);
        assertPlaylistEquals(RED, GREEN);

        playlist.move(1, 0);
        assertPlaylistEquals(GREEN, RED);
        playlist.move(1, 0);
        assertPlaylistEquals(RED, GREEN);

        playlist.move(0, 0);
        assertPlaylistEquals(RED, GREEN);
    }

    @Test
    public void testMove_Three() throws Exception {
        playlist.add(0, RED);
        playlist.add(1, GREEN);
        playlist.add(2, BLUE);
        assertPlaylistEquals(RED, GREEN, BLUE);

        playlist.move(0, 1);
        assertPlaylistEquals(GREEN, RED, BLUE);
        playlist.move(1, 0);
        assertPlaylistEquals(RED, GREEN, BLUE);

        playlist.move(1, 100);
        assertPlaylistEquals(RED, BLUE, GREEN);
        playlist.move(100, 0);
        assertPlaylistEquals(GREEN, RED, BLUE);

        playlist.move(1, 1);
        assertPlaylistEquals(GREEN, RED, BLUE);
    }

    @Test
    public void testExtreme() throws Exception {
        playlist.add(-100, RED);
        assertPlaylistEquals(RED);
        playlist.add(100, GREEN);
        assertPlaylistEquals(RED, GREEN);

        playlist.move(-100, 100);
        assertPlaylistEquals(GREEN, RED);
        playlist.move(100, -100);
        assertPlaylistEquals(RED, GREEN);

        playlist.remove(-100);
        assertPlaylistEquals(GREEN);
        playlist.remove(100);
        assertPlaylistEquals();
    }

    @Test
    public void testRemove() throws Exception {
        playlist.add(0, RED);
        playlist.add(1, GREEN);
        playlist.add(2, BLUE);
        assertPlaylistEquals(RED, GREEN, BLUE);

        playlist.remove(100);
        assertPlaylistEquals(RED, GREEN);

        playlist.remove(0);
        assertPlaylistEquals(GREEN);
    }

    @Test
    public void testRemoveMultiple() throws Exception {
        playlist.add(0, RED);
        playlist.add(1, GREEN);
        playlist.add(2, BLUE);
        assertPlaylistEquals(RED, GREEN, BLUE);

        // Unlike #remove, #removeMultiple ignores out of bounds indexes
        assertEquals(0, playlist.removeMultiple(100));
        assertPlaylistEquals(RED, GREEN, BLUE);

        assertEquals(2, playlist.removeMultiple(0, 2));
        assertPlaylistEquals(GREEN);

        assertEquals(1, playlist.removeMultiple(0));
        assertPlaylistEmpty();
    }



    private void assertPlaylistEquals(Path... items) {
        assertEquals(items, playlist.asList().toArray());
    }

    private void assertPlaylistEmpty() {
        assertThat(playlist.asList()).isEmpty();
    }
}
