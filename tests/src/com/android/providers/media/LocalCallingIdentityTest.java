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

package com.android.providers.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * We already have solid coverage of this logic in {@code CtsProviderTestCases},
 * but the coverage system currently doesn't measure that, so we add the bare
 * minimum local testing here to convince the tooling that it's covered.
 */
@RunWith(AndroidJUnit4.class)
public class LocalCallingIdentityTest {
    @BeforeClass
    public static void setUp() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.UPDATE_APP_OPS_STATS);
    }

    @AfterClass
    public static void tearDown() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testFromSelf() throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        final PackageManager pm = context.getPackageManager();

        final LocalCallingIdentity ident = LocalCallingIdentity.fromSelf(context);

        assertEquals(context.getPackageName(),
                ident.getPackageName());
        assertEquals(Arrays.asList(pm.getPackagesForUid(android.os.Process.myUid())),
                Arrays.asList(ident.getSharedPackageNames()));

        assertTrue(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_SELF));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_SHELL));
        assertTrue(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_MANAGER));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_DELEGATOR));

        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_REDACTION_NEEDED));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_LEGACY_GRANTED));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_LEGACY_READ));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_LEGACY_WRITE));

        assertTrue(ident.hasPermission(LocalCallingIdentity.PERMISSION_READ_AUDIO));
        assertTrue(ident.hasPermission(LocalCallingIdentity.PERMISSION_READ_VIDEO));
        assertTrue(ident.hasPermission(LocalCallingIdentity.PERMISSION_READ_IMAGES));
        assertTrue(ident.hasPermission(LocalCallingIdentity.PERMISSION_WRITE_AUDIO));
        assertTrue(ident.hasPermission(LocalCallingIdentity.PERMISSION_WRITE_VIDEO));
        assertTrue(ident.hasPermission(LocalCallingIdentity.PERMISSION_WRITE_IMAGES));
    }

    @Test
    public void testFromExternal() throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        final PackageManager pm = context.getPackageManager();

        final LocalCallingIdentity ident = LocalCallingIdentity.fromExternal(context,
                pm.getPackageUid(MediaProviderTest.PERMISSIONLESS_APP, 0));

        assertEquals(MediaProviderTest.PERMISSIONLESS_APP,
                ident.getPackageName());
        assertEquals(Arrays.asList(MediaProviderTest.PERMISSIONLESS_APP),
                Arrays.asList(ident.getSharedPackageNames()));

        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_SELF));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_SHELL));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_MANAGER));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_DELEGATOR));

        assertTrue(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_REDACTION_NEEDED));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_LEGACY_GRANTED));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_LEGACY_READ));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_IS_LEGACY_WRITE));

        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_READ_AUDIO));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_READ_VIDEO));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_READ_IMAGES));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_WRITE_AUDIO));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_WRITE_VIDEO));
        assertFalse(ident.hasPermission(LocalCallingIdentity.PERMISSION_WRITE_IMAGES));
    }
}
