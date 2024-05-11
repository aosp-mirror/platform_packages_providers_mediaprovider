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

package com.android.providers.media.leveldb;


import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class LevelDBManagerTest {

    private static final String SUCCESS_CODE = "0";

    @Test
    public void testLevelDbOperations() {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        String levelDBFile = "test-leveldb";
        final String levelDBPath = context.getFilesDir().getPath() + "/" + levelDBFile;
        LevelDBInstance levelDBInstance = LevelDBManager.getInstance(levelDBPath);
        LevelDBResult levelDBResult;
        try {

            List<String> fileNames = Arrays.stream(context.getFilesDir().listFiles()).map(
                    File::getName).collect(Collectors.toList());
            assertThat(fileNames).contains(levelDBFile);

            levelDBResult = levelDBInstance.insert(new LevelDBEntry("a", "1"));
            verifySuccessResult(levelDBResult);

            levelDBResult = levelDBInstance.query("a");
            verifySuccessResult(levelDBResult);
            assertThat(levelDBResult.getValue()).isEqualTo("1");

            levelDBResult = levelDBInstance.delete("b");
            verifySuccessResult(levelDBResult);

            levelDBResult = levelDBInstance.bulkInsert(
                    Arrays.asList(new LevelDBEntry("c", "3"), new LevelDBEntry("d", "4")));
            verifySuccessResult(levelDBResult);

            assertThat(levelDBInstance.query("c").getValue()).isEqualTo("3");
            assertThat(levelDBInstance.query("d").getValue()).isEqualTo("4");
            assertThat(levelDBInstance.query("b").getValue()).isEqualTo("");
            assertThat(levelDBInstance.query("a").getValue()).isEqualTo("1");

        } finally {
            // Deletes leveldb file
            levelDBInstance.deleteInstance();
        }
    }

    private void verifySuccessResult(LevelDBResult levelDBResult) {
        assertThat(levelDBResult).isNotNull();
        assertThat(levelDBResult.getCode()).isNotNull();
        assertThat(levelDBResult.getCode()).isEqualTo(SUCCESS_CODE);
    }
}
