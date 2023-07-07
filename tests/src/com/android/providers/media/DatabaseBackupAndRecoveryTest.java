/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.providers.media.DatabaseHelper.INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY;
import static com.android.providers.media.DatabaseHelper.INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX;
import static com.android.providers.media.DatabaseHelper.INTERNAL_DB_SESSION_ID_XATTR_KEY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class DatabaseBackupAndRecoveryTest {

    @Test
    public void testXattrOperations() {
        final Context context = ApplicationProvider.getApplicationContext();
        final String path = context.getFilesDir().getPath();
        final Integer value = 1000000;
        final String sessionId = UUID.randomUUID().toString();
        DatabaseBackupAndRecovery.setXattr(path, INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY,
                String.valueOf(value));
        DatabaseBackupAndRecovery.setXattr(path, INTERNAL_DB_SESSION_ID_XATTR_KEY, sessionId);

        assertTrue(DatabaseBackupAndRecovery.listXattr(path).containsAll(Arrays.asList(
                INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY, INTERNAL_DB_SESSION_ID_XATTR_KEY)));
        Optional<Integer> actualIntegerValue = DatabaseBackupAndRecovery.getXattrOfIntegerValue(
                path,
                INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY);
        assertTrue(actualIntegerValue.isPresent());
        assertThat(actualIntegerValue.get()).isEqualTo(value);
        Optional<String> actualStringValue = DatabaseBackupAndRecovery.getXattr(path,
                INTERNAL_DB_SESSION_ID_XATTR_KEY);
        assertTrue(actualStringValue.isPresent());

        DatabaseBackupAndRecovery.removeXattr(path, INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY);
        DatabaseBackupAndRecovery.removeXattr(path, INTERNAL_DB_SESSION_ID_XATTR_KEY);
    }

    @Test
    public void testGetInvalidUsersList() {
        List<String> xattrData = Arrays.asList(
                INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX + "0",
                INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX + "10",
                INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX + "11",
                INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX + "12",
                INTERNAL_DB_NEXT_ROW_ID_XATTR_KEY_PREFIX + "13");

        assertThat(DatabaseBackupAndRecovery.getInvalidUsersList(xattrData, /* validUserIds */
                Arrays.asList("0", "13"))).containsExactly("10", "11", "12");
    }
}
