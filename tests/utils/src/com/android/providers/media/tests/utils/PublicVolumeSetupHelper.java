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

package com.android.providers.media.tests.utils;

import android.app.UiAutomation;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Helper methods for public volume setup.
 */
public class PublicVolumeSetupHelper {
    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(2);
    private static final long POLLING_SLEEP_MILLIS = 100;
    private static final String TAG = "TestUtils";
    private static boolean usingExistingPublicVolume = false;

    /**
     * (Re-)partitions an already created pulic volume
     */
    public static void partitionPublicVolume() throws Exception {
        pollForCondition(() -> partitionDisk(), "Timed out while waiting for"
                + " disk partitioning");
        // Poll twice to avoid using previous mount status
        pollForCondition(() -> isPublicVolumeMounted(), "Timed out while waiting for"
                + " the public volume to mount");
    }

    /**
     * Polls for external storage to be mounted.
     */
    public static void pollForExternalStorageStateMounted() throws Exception {
        pollForCondition(() -> isExternalStorageStateMounted(), "Timed out while"
                + " waiting for ExternalStorageState to be MEDIA_MOUNTED");
    }

    /**
     * Creates a new virtual public volume and returns the volume's name.
     */
    public static void createNewPublicVolume() throws Exception {
        // Skip public volume setup if we can use already available public volume on the device.
        if (getCurrentPublicVolumeString() != null && isPublicVolumeMounted()) {
            usingExistingPublicVolume = true;
            return;
        }
        executeShellCommand("sm set-force-adoptable on");
        executeShellCommand("sm set-virtual-disk true");

        partitionPublicVolume();

        pollForExternalStorageStateMounted();
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

    /**
     * Gets the name of the public volume string from list-volumes,
     * waiting for a bit for it to be available.
     */
    private static String getPublicVolumeString() throws Exception {
        final String[] volName = new String[1];
        pollForCondition(() -> {
            volName[0] = getCurrentPublicVolumeString();
            return volName[0] != null;
        }, "Timed out while waiting for public volume to be ready");

        return volName[0];
    }

    /**
     * @return the currently mounted public volume string, if any.
     */
    static String getCurrentPublicVolumeString() {
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
                String res = publicVolumeDetails[0];
                if ("null".equals(res)) {
                    continue;
                }
                return res;
            }
        }
        return null;
    }

    public static void mountPublicVolume() throws Exception {
        executeShellCommand("sm mount " + getPublicVolumeString());
    }

    public static void unmountPublicVolume() throws Exception {
        executeShellCommand("sm unmount " + getPublicVolumeString());
    }

    public static void deletePublicVolumes() throws Exception {
        if (!usingExistingPublicVolume) {
            executeShellCommand("sm set-virtual-disk false");
            // Wait for the public volume to disappear.
            for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
                if (!isPublicVolumeMounted()) {
                    return;
                }
                Thread.sleep(POLLING_SLEEP_MILLIS);
            }
        }
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
                // Hmm, we had trouble executing the shell command; the best we
                // can do is try again a few more times
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

    public static void pollForCondition(Supplier<Boolean> condition, String errorMessage)
            throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        throw new TimeoutException(errorMessage);
    }
}
