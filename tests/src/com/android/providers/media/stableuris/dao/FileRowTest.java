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

package com.android.providers.media.stableuris.dao;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FileRowTest {

    @Test
    public void testSimple() throws Exception {
        FileRow row = FileRow.newBuilder(5)
                .setGenerationModified(1234)
                .build();

        String s = FileRow.serialize(row);

        assertThat(FileRow.deserialize(s)).isEqualTo(row);
    }

    @Test
    public void testNullFieldsSerialization() throws Exception {
        FileRow row1 = FileRow.newBuilder(5)
                .setGenerationModified(1234)
                .setIsDrm(0)
                .build();

        FileRow row2 = FileRow.newBuilder(5)
                .setGenerationModified(1234)
                .build();

        String s1 = FileRow.serialize(row1);
        String s2 = FileRow.serialize(row2);

        assertThat(s1).isNotEqualTo(s2);
        assertThat(FileRow.deserialize(s1)).isNotEqualTo(FileRow.deserialize(s2));
    }
}
