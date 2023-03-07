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
public class BackupIdRowTest {

    @Test
    public void testSimple() throws Exception {
        BackupIdRow row = BackupIdRow.newBuilder(5)
                .build();

        String s = BackupIdRow.serialize(row);

        assertThat(BackupIdRow.deserialize(s)).isEqualTo(row);
    }

    @Test
    public void testAllFields() throws Exception {
        BackupIdRow row = BackupIdRow.newBuilder(5)
                .setIsFavorite(1)
                .setIsPending(1)
                .setIsTrashed(0)
                .setOwnerPackagedId(1)
                .setUserId(1)
                .setDateExpires("10")
                .setIsDirty(true)
                .build();
        String s = BackupIdRow.serialize(row);

        assertThat(BackupIdRow.deserialize(s)).isEqualTo(row);

        BackupIdRow row2 = BackupIdRow.newBuilder(5)
                .setIsFavorite(1)
                .setIsPending(1)
                .setIsTrashed(0)
                .setOwnerPackagedId(1)
                .setUserId(1)
                .setDateExpires("10")
                .setIsDirty(false)
                .build();

        assertThat(BackupIdRow.deserialize(s)).isNotEqualTo(row2);
    }
}
