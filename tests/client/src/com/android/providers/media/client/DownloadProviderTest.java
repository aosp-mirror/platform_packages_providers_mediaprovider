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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.io.ByteStreams;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Verify DownloadProvider's access to app's private files on primary and public volumes.
 * DownloadProvider and Media Provider client tests use the same shared UID
 * `android:sharedUserId="android.media"` which helps us test DownloadProvider's behavior here
 */
@RunWith(AndroidJUnit4.class)
public class DownloadProviderTest {

    private static final String TAG = "DownloadProviderTest";
    private static final  String OTHER_PKG_NAME = "com.example.foo";
    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long POLLING_SLEEP_MILLIS = 100;

    @BeforeClass
    public static void setUp() throws Exception {
        createNewPublicVolume();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        deletePublicVolumes();
    }

    private static void deletePublicVolumes() throws Exception {
        executeShellCommand("sm set-virtual-disk false");
    }

    @Test
    public void testCanReadWriteOtherAppPrivateFiles() throws Exception {
        List<File> otherPackageDirs = createOtherPackageExternalFilesDir();
        List<File> otherPackagePrivateFiles = createOtherPackagePrivateFile(otherPackageDirs);
        for (File privateFile: otherPackagePrivateFiles) {
            assertTrue(canOpenForWrite(privateFile));
        }
        deleteOtherPackageExternalFiles(otherPackageDirs);
    }

    @Test
    public void testCanOpenOtherAppPrivateDir() throws Exception {
        List<File> otherPackageDirs = createOtherPackageExternalFilesDir();
        for (File privateDir: otherPackageDirs) {
            String[] dirContents = privateDir.list();
            assertThat(dirContents).asList().containsExactly("files");
        }
        deleteOtherPackageExternalFiles(otherPackageDirs);
    }

    private List<File> createOtherPackageExternalFilesDir() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        Set<String> volNames = MediaStore.getExternalVolumeNames(context);
        List<File> otherPackageDirs = new ArrayList();

        for (String volName : volNames) {
            File volPath = getVolumePath(context, volName);
            // List of private external package files for other package on the same volume
            List<String> otherPackageDirsOnSameVolume = new ArrayList();
            final String otherPackageDataDir = volPath.getAbsolutePath() + "/Android/data/"
                    + OTHER_PKG_NAME;
            otherPackageDirsOnSameVolume.add(otherPackageDataDir);
            final String otherPackageObbDir = volPath.getAbsolutePath() + "/Android/obb/"
                    + OTHER_PKG_NAME;
            otherPackageDirsOnSameVolume.add(otherPackageObbDir);

            for (String dir: otherPackageDirsOnSameVolume) {
                otherPackageDirs.add(new File(dir));
                final String otherPackageExternalFilesDir = dir + "/files";
                executeShellCommand("mkdir -p " + otherPackageExternalFilesDir + " -m 2770");
                // Need to wait for the directory to be created, as the rest of the test depends on
                // the dir to be created. A race condition can cause the test to be flaky.
                pollForDirectoryToBeCreated(new File(otherPackageExternalFilesDir));
            }
        }
        return otherPackageDirs;
    }

    private List<File> createOtherPackagePrivateFile(List<File> otherPackageDirs) throws Exception {
        List<File> otherPackagePrivateFiles = new ArrayList();
        for (File otherPackageDir : otherPackageDirs) {
            final String otherPackagePrivateFile = otherPackageDir + "/files/test.txt";
            otherPackagePrivateFiles.add(new File(otherPackagePrivateFile));
            executeShellCommand("touch " + otherPackagePrivateFile);
        }
        return otherPackagePrivateFiles;
    }

    private void deleteOtherPackageExternalFiles(List<File> otherPackageDirs) throws Exception {
        for (File dir: otherPackageDirs) {
            executeShellCommand("rm -r " + dir.getAbsolutePath());
            // Need to wait for the directory to be deleted, as it is flaky sometimes due to the
            // race condition in rm and the next mkdir for the next test.
            pollForDirectoryToBeDeleted(dir);
        }
    }

    /**
     * Returns whether we can open the file.
     */
    private static boolean canOpenForWrite(File file) {
        try (FileOutputStream fis = new FileOutputStream(file)) {
            return true;
        } catch (IOException expected) {
            return false;
        }
    }

    private static File getVolumePath(Context context, String volumeName) {
        return context.getSystemService(StorageManager.class)
            .getStorageVolume(MediaStore.Files.getContentUri(volumeName)).getDirectory();
    }

    private static String executeShellCommand(String cmd) throws IOException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        ParcelFileDescriptor pfd = uiAutomation.executeShellCommand(cmd);
        try (FileInputStream output = new FileInputStream(pfd.getFileDescriptor())) {
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

    /**
     * Polls for directory to be created
     */
    private static void pollForDirectoryToBeCreated(File dir) throws Exception {
        pollForCondition(
            () -> dir.exists(),
            "Timed out while waiting for dir " + dir + " to be created");
    }

    /**
     * Polls for directory to be deleted
     */
    private static void pollForDirectoryToBeDeleted(File dir) throws Exception {
        pollForCondition(
            () -> !dir.exists(),
            "Timed out while waiting for dir " + dir + " to be deleted");
    }

    /**
     * Creates a new virtual public volume and returns the volume's name.
     */
    private static void createNewPublicVolume() throws Exception {
        executeShellCommand("sm set-force-adoptable on");
        executeShellCommand("sm set-virtual-disk true");
        pollForCondition(() -> partitionDisk(), "Timed out while waiting for"
                + " disk partitioning");
        pollForCondition(() -> isPublicVolumeMounted(), "Timed out while waiting for"
                + " the public volume to mount");
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
}
