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

import static com.android.providers.media.util.PermissionUtils.checkPermissionBackup;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadAudio;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadImages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadStorage;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadVideo;
import static com.android.providers.media.util.PermissionUtils.checkPermissionSystem;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteAudio;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteImages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteStorage;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteVideo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PermissionUtilsTest {
    /**
     * The best we can do here is assert that we're granted the permissions that
     * we expect to be holding.
     */
    @Test
    public void testSelfPermissions() throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        final int pid = android.os.Process.myPid();
        final int uid = android.os.Process.myUid();
        final String packageName = context.getPackageName();

        assertTrue(checkPermissionSystem(context, pid, uid, packageName));
        assertFalse(checkPermissionBackup(context, pid, uid));

        assertTrue(checkPermissionReadStorage(context, pid, uid, packageName));
        assertTrue(checkPermissionWriteStorage(context, pid, uid, packageName));

        assertTrue(checkPermissionReadAudio(context, pid, uid, packageName));
        assertFalse(checkPermissionWriteAudio(context, pid, uid, packageName));
        assertTrue(checkPermissionReadVideo(context, pid, uid, packageName));
        assertFalse(checkPermissionWriteVideo(context, pid, uid, packageName));
        assertTrue(checkPermissionReadImages(context, pid, uid, packageName));
        assertFalse(checkPermissionWriteImages(context, pid, uid, packageName));
    }
}
