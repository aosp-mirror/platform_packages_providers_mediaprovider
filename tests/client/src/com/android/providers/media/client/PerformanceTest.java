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

package com.android.providers.media.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.tests.utils.Timer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Since we're right in the critical path between camera and gallery apps, we
 * need to meet some pretty strict performance deadlines.
 */
@RunWith(AndroidJUnit4.class)
public class PerformanceTest {
    private static final String TAG = "PerformanceTest";

    /**
     * Number of times we should repeat each operation to get an average.
     */
    private static final int COUNT_REPEAT = 5;

    /**
     * Number of items to use for bulk operation tests.
     */
    private static final int COUNT_BULK = 100;

    /**
     * Verify performance of "single" standalone operations.
     */
    @Test
    public void testSingle() throws Exception {
        final Timers timers = new Timers();
        for (int i = 0; i < COUNT_REPEAT; i++) {
            doSingle(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, timers);
        }

        timers.dumpResults();

        // Verify that core actions finished within 30ms deadline
        final long actionDeadline = 30;
        assertTrue(timers.actionInsert.getAverageDurationMillis() < actionDeadline);
        assertTrue(timers.actionUpdate.getAverageDurationMillis() < actionDeadline);
        assertTrue(timers.actionDelete.getAverageDurationMillis() < actionDeadline);

        // Verify that external notifications finished within 30ms deadline
        final long notifyDeadline = 30;
        assertTrue(timers.notifyInsert.getAverageDurationMillis() < notifyDeadline);
        assertTrue(timers.notifyUpdate.getAverageDurationMillis() < notifyDeadline);
        assertTrue(timers.notifyDelete.getAverageDurationMillis() < notifyDeadline);
    }

    private void doSingle(Uri collection, Timers timers) throws Exception {
        final ContentResolver resolver = InstrumentationRegistry.getContext().getContentResolver();

        Uri res;
        MediaStore.waitForIdle(resolver);
        {
            final ContentValues values = new ContentValues();
            values.put(MediaColumns.DISPLAY_NAME, System.nanoTime() + ".jpg");
            values.put(MediaColumns.MIME_TYPE, "image/jpeg");

            final CountingContentObserver obs = CountingContentObserver.create(
                    collection, 1, ContentResolver.NOTIFY_INSERT);

            timers.actionInsert.start();
            res = resolver.insert(collection, values);
            timers.actionInsert.stop();

            timers.notifyInsert.start();
            obs.waitForChange();
            timers.notifyInsert.stop();
        }
        MediaStore.waitForIdle(resolver);
        {
            final ContentValues values = new ContentValues();
            values.put(MediaColumns.IS_FAVORITE, 1);

            final CountingContentObserver obs = CountingContentObserver.create(
                    collection, 1, ContentResolver.NOTIFY_UPDATE);

            timers.actionUpdate.start();
            resolver.update(res, values, null);
            timers.actionUpdate.stop();

            timers.notifyUpdate.start();
            obs.waitForChange();
            timers.notifyUpdate.stop();
        }
        MediaStore.waitForIdle(resolver);
        {
            final CountingContentObserver obs = CountingContentObserver.create(
                    collection, 1, ContentResolver.NOTIFY_DELETE);

            timers.actionDelete.start();
            resolver.delete(res, null);
            timers.actionDelete.stop();

            timers.notifyDelete.start();
            obs.waitForChange();
            timers.notifyDelete.stop();
        }
        MediaStore.waitForIdle(resolver);
    }

    /**
     * Verify performance of "bulk" operations, typically encountered when the
     * user is taking burst-mode photos or deleting many images.
     */
    @Test
    public void testBulk() throws Exception {
        final Timers timers = new Timers();
        for (int i = 0; i < COUNT_REPEAT; i++) {
            doBulk(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, timers);
        }

        timers.dumpResults();

        // Verify that core actions finished within 30ms deadline
        final long actionDeadline = 30 * COUNT_BULK;
        assertTrue(timers.actionInsert.getAverageDurationMillis() < actionDeadline);
        assertTrue(timers.actionUpdate.getAverageDurationMillis() < actionDeadline);
        assertTrue(timers.actionDelete.getAverageDurationMillis() < actionDeadline);

        // Verify that external notifications finished within 100ms deadline
        final long notifyDeadline = 100;
        assertTrue(timers.notifyInsert.getAverageDurationMillis() < notifyDeadline);
        assertTrue(timers.notifyUpdate.getAverageDurationMillis() < notifyDeadline);
        assertTrue(timers.notifyDelete.getAverageDurationMillis() < notifyDeadline);
    }

    private void doBulk(Uri collection, Timers timers) throws Exception {
        final ContentResolver resolver = InstrumentationRegistry.getContext().getContentResolver();

        ContentProviderResult[] res;
        MediaStore.waitForIdle(resolver);
        {
            final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            for (int i = 0; i < COUNT_BULK; i++) {
                ops.add(ContentProviderOperation.newInsert(collection)
                        .withValue(MediaColumns.DISPLAY_NAME, System.nanoTime() + ".jpg")
                        .withValue(MediaColumns.MIME_TYPE, "image/jpeg")
                        .build());
            }

            final CountingContentObserver obs = CountingContentObserver.create(
                    collection, COUNT_BULK, ContentResolver.NOTIFY_INSERT);

            timers.actionInsert.start();
            res = resolver.applyBatch(collection.getAuthority(), ops);
            timers.actionInsert.stop();

            timers.notifyInsert.start();
            obs.waitForChange();
            timers.notifyInsert.stop();
        }
        MediaStore.waitForIdle(resolver);
        {
            final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            for (int i = 0; i < COUNT_BULK; i++) {
                ops.add(ContentProviderOperation.newUpdate(res[i].uri)
                        .withValue(MediaColumns.IS_FAVORITE, 1)
                        .build());
            }

            final CountingContentObserver obs = CountingContentObserver.create(
                    collection, COUNT_BULK, ContentResolver.NOTIFY_UPDATE);

            timers.actionUpdate.start();
            resolver.applyBatch(collection.getAuthority(), ops);
            timers.actionUpdate.stop();

            timers.notifyUpdate.start();
            obs.waitForChange();
            timers.notifyUpdate.stop();
        }
        MediaStore.waitForIdle(resolver);
        {
            final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            for (int i = 0; i < COUNT_BULK; i++) {
                ops.add(ContentProviderOperation.newDelete(res[i].uri)
                        .build());
            }

            final CountingContentObserver obs = CountingContentObserver.create(
                    collection, COUNT_BULK, ContentResolver.NOTIFY_DELETE);

            timers.actionDelete.start();
            resolver.applyBatch(collection.getAuthority(), ops);
            timers.actionDelete.stop();

            timers.notifyDelete.start();
            obs.waitForChange();
            timers.notifyDelete.stop();
        }
        MediaStore.waitForIdle(resolver);
    }

    @Test
    public void testDirOperations_10() throws Exception {
        testDirOperations_size(10);
    }

    @Test
    public void testDirOperations_100() throws Exception {
        testDirOperations_size(100);
    }

    @Test
    public void testDirOperations_500() throws Exception {
        testDirOperations_size(500);
    }

    @LargeTest
    @Test
    public void testDirOperations_1000() throws Exception {
        testDirOperations_size(1000);
    }

    private void testDirOperations_size(int size) throws Exception {
        Timer createTimer = new Timer("mkdir");
        Timer readTimer = new Timer("readdir");
        // We have different timers for rename dir only and rename files as we want to track the
        // performance for both of the following:
        // 1. Renaming a directory is significantly faster (for file managers) as we do not update
        // DB entries for all the files within it. (it takes ~10ms for a dir of 1000 files)
        // 2. Renaming files is faster as well (for file managers), as we do not do DB operations
        // on each rename.
        Timer renameDirTimer = new Timer("renamedir");
        Timer renameFilesTimer = new Timer("renamefiles");
        Timer deleteTimer = new Timer("rmdir");
        for (int i = 0; i < COUNT_REPEAT; i++ ) {
            doDirOperations(size, createTimer, readTimer, renameDirTimer, renameFilesTimer,
                    deleteTimer);
        }
        createTimer.dumpResults();
        readTimer.dumpResults();
        renameDirTimer.dumpResults();
        renameFilesTimer.dumpResults();
        deleteTimer.dumpResults();
    }

    private void doDirOperations(int size, Timer createTimer, Timer readTimer,
            Timer renameDirTimer, Timer renameFilesTimer, Timer deleteTimer) throws Exception {
        createTimer.start();
        File testDir = new File(new File(Environment.getExternalStorageDirectory(),
                "Download"), "test_dir_" + System.nanoTime());
        testDir.mkdir();
        List<File> files = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            File file = new File(testDir, "file_" + System.nanoTime());
            assertTrue(file.createNewFile());
            files.add(file);
        }
        createTimer.stop();

        File renamedTestDir = new File(new File(Environment.getExternalStorageDirectory(),
                "Download"), "renamed_test_dir_" + System.nanoTime());
        try {
            readTimer.start();
            File[] result = testDir.listFiles();
            readTimer.stop();
            assertEquals(size, result.length);

            renameDirTimer.start();
            assertTrue(testDir.renameTo(renamedTestDir));
            renameDirTimer.stop();
            testDir = renamedTestDir;

            // renameTo for files will fail as the old files are not valid files as the dir name
            // is changed, update the files to be valid.
            files = Arrays.asList(renamedTestDir.listFiles());

            renameFilesTimer.start();
            List<File> renamedFiles = new ArrayList<>();
            for (File file : files) {
                File newFile = new File(testDir, "file_" + System.nanoTime());
                assertTrue(file.renameTo(newFile));
                renamedFiles.add(newFile);
            }
            renameFilesTimer.stop();
            // This is essential for the finally block to delete valid files.
            files = renamedFiles;

        } finally {
            deleteTimer.start();
            for (File file : files) {
                assertTrue(file.delete());
            }
            assertTrue(testDir.delete());
            deleteTimer.stop();
        }
    }

    private static Set<Uri> asSet(Collection<Uri> uris) {
        return new HashSet<>(uris);
    }

    private static class Timers {
        public final Timer actionInsert = new Timer("action_insert");
        public final Timer actionUpdate = new Timer("action_update");
        public final Timer actionDelete = new Timer("action_delete");
        public final Timer notifyInsert = new Timer("notify_insert");
        public final Timer notifyUpdate = new Timer("notify_update");
        public final Timer notifyDelete = new Timer("notify_delete");

        public void dumpResults() {
            actionInsert.dumpResults();
            actionUpdate.dumpResults();
            actionDelete.dumpResults();
            notifyInsert.dumpResults();
            notifyUpdate.dumpResults();
            notifyDelete.dumpResults();
        }
    }

    /**
     * Observer that will wait for a specific change event to be delivered.
     */
    public static class CountingContentObserver extends ContentObserver {
        private final int uriCount;
        private final int flags;
        private int accumulatedCount = 0;

        private final CountDownLatch latch = new CountDownLatch(1);

        private CountingContentObserver(int uriCount, int flags) {
            super(null);
            this.uriCount = uriCount;
            this.flags = flags;
        }

        @Override
        public void onChange(boolean selfChange, Collection<Uri> uris, int flags) {
            Log.v(TAG, String.format("onChange(%b, %s, %d)",
                    selfChange, asSet(uris).toString(), flags));

            if (this.uriCount == 1) {
                if (asSet(uris).size() == 1 && flags == this.flags) {
                    latch.countDown();
                }
            } else if (flags == this.flags) {
                // NotifyChange for bulk operations will be sent in batches.
                final int receivedCount = asSet(uris).size();

                if (receivedCount + accumulatedCount == this.uriCount) {
                    latch.countDown();
                } else {
                    accumulatedCount += receivedCount;
                }
            }
        }

        public static CountingContentObserver create(Uri uri, int uriCount, int flags) {
            final CountingContentObserver obs = new CountingContentObserver(uriCount, flags);
            InstrumentationRegistry.getContext().getContentResolver()
                    .registerContentObserver(uri, true, obs);
            return obs;
        }

        public void waitForChange() {
            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            InstrumentationRegistry.getContext().getContentResolver()
                    .unregisterContentObserver(this);
        }
    }
}
