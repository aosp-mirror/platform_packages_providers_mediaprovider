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

package com.android.providers.media;

import static androidx.test.InstrumentationRegistry.getContext;
import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.content.Context;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import androidx.test.InstrumentationRegistry;

import com.android.providers.media.util.UserCache;
import com.google.common.io.ByteStreams;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class UnreliableVolumeTest {

    private static String mVolumePath;
    private static String mVolumeName;
    private static VolumeCache mVolumeCache;
    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(2);
    private static final long POLLING_SLEEP_MILLIS = 100;
    private static final String TAG = "UnreliableVolumeTest";

    @BeforeClass
    public static void setUp() throws Exception {
        createRemovableVolume();
        final Context context = getContext();
        UserCache mUserCache = new UserCache(context);
        mVolumeCache = new VolumeCache(context, mUserCache);
        mVolumeCache.update();

        mVolumeName = getCurrentPublicVolumeString();
        mVolumePath = "/mnt/media_rw/" + mVolumeName;
    }

    @AfterClass
    public static void tearDown() throws Exception {
        executeShellCommand("sm set-virtual-disk false");
        pollForCondition(() -> !isPublicVolumeMounted(), "Timed out while waiting for"
                + " the public volume to disappear");
    }

    @Test
    public void testUnreliableVolumeSimple() throws Exception {
        assertEquals(mVolumeName, mVolumeCache.getUnreliableVolumePath().get(0).getName());
        assertEquals(mVolumePath, mVolumeCache.getUnreliableVolumePath().get(0).getPath());
    }

    /**
     * Executes a shell command.
     */
    public static String executeShellCommand(String pattern, Object...args) throws IOException {
        String command = String.format(pattern, args);
        int attempt = 0;
        while (attempt++ < 5) {
            try {
                return executeShellCommandInternal(command);
            } catch (InterruptedIOException e) {
                Log.v(TAG, "Trouble executing " + command + "; trying again", e);
            }
        }
        throw new IOException("Failed to execute " + command);
    }

    private static String executeShellCommandInternal(String cmd) throws IOException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (FileInputStream output = new FileInputStream(
                uiAutomation.executeShellCommand(cmd).getFileDescriptor())) {
            return new String(ByteStreams.toByteArray(output));
        }
    }

    private static void pollForCondition(Supplier<Boolean> condition, String errorMessage)
            throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        throw new TimeoutException(errorMessage);
    }

    private static boolean isPublicVolumeMounted() {
        try {
            final String publicVolume = executeShellCommand("sm list-volumes public").trim();
            return publicVolume != null && publicVolume.contains("mounted");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean partitionDisk() {
        try {
            final String listDisks = executeShellCommand("sm list-disks").trim();
            if (listDisks.length() > 0) {
                executeShellCommand("sm partition " + listDisks + " public");
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getCurrentPublicVolumeString() {
        final String[] allPublicVolumeDetails;
        try {
            allPublicVolumeDetails = executeShellCommand("sm list-volumes public")
                    .trim().split("\n");
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute shell command", e);
            return null;
        }
        for (String volDetails : allPublicVolumeDetails) {
            if (volDetails.startsWith("public")) {
                final String[] publicVolumeDetails = volDetails.trim().split(" ");
                String res = publicVolumeDetails[publicVolumeDetails.length - 1];
                if ("null".equals(res)) {
                    continue;
                }
                return res;
            }
        }
        return null;
    }

    private static boolean isExternalStorageStateMounted() {
        final File target = Environment.getExternalStorageDirectory();
        try {
            return (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(target))
                    && Os.statvfs(target.getAbsolutePath()).f_blocks > 0);
        } catch (ErrnoException ignored) {
        }
        return false;
    }

    /**
     * Creates a new OTG-USB volume
     */
    public static void createRemovableVolume() throws Exception {
        executeShellCommand("sm set-force-adoptable off");
        executeShellCommand("sm set-virtual-disk true");
        pollForCondition(() -> partitionDisk(), "Timed out while waiting for"
                + " disk partitioning");
        // Poll twice to avoid using previous mount status
        pollForCondition(() -> isPublicVolumeMounted(), "Timed out while waiting for"
                + " the public volume to mount");
        pollForCondition(() -> isExternalStorageStateMounted(), "Timed out while"
                + " waiting for ExternalStorageState to be MEDIA_MOUNTED");
    }
}