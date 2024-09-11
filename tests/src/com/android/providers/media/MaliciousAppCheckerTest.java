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

package com.android.providers.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.flags.Flags;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
public class MaliciousAppCheckerTest {
    private static Context sIsolatedContext;
    private static ContentResolver sIsolatedResolver;
    private static final int FILE_CREATION_THRESHOLD_LIMIT = 5;
    private static final int FREQUENCY_OF_MALICIOUS_INSERTION_CHECK = 1;
    private static MaliciousAppDetector sMaliciousAppDetector;
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @BeforeClass
    public static void setUpBeforeClass() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.READ_DEVICE_CONFIG,
                        // Adding this to use getUserHandles() api of UserManagerService which
                        // requires either MANAGE_USERS or CREATE_USERS. Since shell does not have
                        // MANAGER_USERS permissions, using CREATE_USERS in test. This works with
                        // MANAGE_USERS permission for MediaProvider module.
                        Manifest.permission.CREATE_USERS,
                        Manifest.permission.INTERACT_ACROSS_USERS);
    }

    @Before
    public void setUp() {
        resetIsolatedContext();
        sMaliciousAppDetector = new MaliciousAppDetector(sIsolatedContext,
                FILE_CREATION_THRESHOLD_LIMIT, FREQUENCY_OF_MALICIOUS_INSERTION_CHECK);
    }

    @AfterClass
    public static void tearDown() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MALICIOUS_APP_DETECTOR)
    public void testCannotCreateFileLimitExceeded() throws Exception {
        resetIsolatedContext();
        sMaliciousAppDetector.clearSharedPref();

        createTempFilesInDownloadFolder(FILE_CREATION_THRESHOLD_LIMIT);
        // add sleep to wait for the background process
        SystemClock.sleep(1000);
        int uid = android.os.Process.myUid();
        boolean isAllowedToCreateFile = sMaliciousAppDetector.isAppAllowedToCreateFiles(uid);

        assertFalse("File should not be allowed to create after limit exceeded",
                isAllowedToCreateFile);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MALICIOUS_APP_DETECTOR)
    public void testCreateFileWithinLimit() throws Exception {
        resetIsolatedContext();
        sMaliciousAppDetector.clearSharedPref();

        // create files less than the threshold limit
        createTempFilesInDownloadFolder(FILE_CREATION_THRESHOLD_LIMIT - 1);
        // add sleep to wait for the background process
        SystemClock.sleep(1000);
        int uid = android.os.Process.myUid();
        boolean isAllowedToCreateFile = sMaliciousAppDetector.isAppAllowedToCreateFiles(uid);

        assertTrue("File should be allowed to create within limit", isAllowedToCreateFile);
    }

    private void createTempFilesInDownloadFolder(int numberOfFilesToCreate) {
        final Uri downloadUri = MediaStore.Downloads
                .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        for (int index = 0; index < numberOfFilesToCreate; index++) {
            final ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "test_" + index + ".txt");
            values.put(MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME,
                    sIsolatedContext.getPackageName());
            sIsolatedResolver.insert(downloadUri, values);
        }
    }

    private static void resetIsolatedContext() {
        if (sIsolatedResolver != null) {
            // This is necessary, we wait for all unfinished tasks to finish before we create a
            // new IsolatedContext.
            MediaStore.waitForIdle(sIsolatedResolver);
        }

        Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false,
                sMaliciousAppDetector);
        sIsolatedResolver = sIsolatedContext.getContentResolver();
    }
}
