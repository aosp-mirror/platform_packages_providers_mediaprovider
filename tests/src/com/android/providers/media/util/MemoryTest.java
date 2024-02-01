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

package com.android.providers.media.util;

import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
public class MemoryTest {
    private final byte[] buf = new byte[4];

    @Test
    public void testBigEndian() {
        final int expected = 42;
        Memory.pokeInt(buf, 0, expected, ByteOrder.BIG_ENDIAN);
        final int actual = Memory.peekInt(buf, 0, ByteOrder.BIG_ENDIAN);
        assertEquals(expected, actual);
    }

    @Test
    public void testLittleEndian() {
        final int expected = 42;
        Memory.pokeInt(buf, 0, expected, ByteOrder.LITTLE_ENDIAN);
        final int actual = Memory.peekInt(buf, 0, ByteOrder.LITTLE_ENDIAN);
        assertEquals(expected, actual);
    }
}
