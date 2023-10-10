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

package com.android.providers.media.photopicker.viewmodel;

import static android.os.Process.myUserHandle;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BannerControllerTest {
    private BannerController mBannerController;
    private static final String CMP_AUTHORITY = "authority";
    private static final String CMP_ACCOUNT_NAME = "account_name";

    @Before
    public void setUp() {
        final Context context = getInstrumentation().getTargetContext();

        mBannerController = new BannerController(context, myUserHandle()) {
            @Override
            void updateCloudProviderDataFile() {
                // No-op
            }

            @Override
            boolean areCloudProviderOptionsAvailable() {
                return true;
            }
        };

        assertNull(mBannerController.getCloudMediaProviderAuthority());
        assertNull(mBannerController.getCloudMediaProviderLabel());
        assertNull(mBannerController.getCloudMediaProviderAccountName());

        assertFalse(mBannerController.shouldShowCloudMediaAvailableBanner());
        assertFalse(mBannerController.shouldShowAccountUpdatedBanner());
        assertFalse(mBannerController.shouldShowChooseAccountBanner());
        assertFalse(mBannerController.shouldShowChooseAppBanner());
    }

    @Test
    public void testCloudMediaAvailable() {
        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, CMP_ACCOUNT_NAME);
        assertEquals(CMP_AUTHORITY, mBannerController.getCloudMediaProviderAuthority());
        assertEquals(CMP_ACCOUNT_NAME, mBannerController.getCloudMediaProviderAccountName());

        assertTrue(mBannerController.shouldShowCloudMediaAvailableBanner());
        assertFalse(mBannerController.shouldShowAccountUpdatedBanner());
        assertFalse(mBannerController.shouldShowChooseAccountBanner());
        assertFalse(mBannerController.shouldShowChooseAppBanner());

        mBannerController.onUserDismissedCloudMediaAvailableBanner();
        assertFalse(mBannerController.shouldShowCloudMediaAvailableBanner());
    }

    @Test
    public void testChooseAccount() {
        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, /* cmpAccountName */ null);
        assertEquals(CMP_AUTHORITY, mBannerController.getCloudMediaProviderAuthority());
        assertNull(mBannerController.getCloudMediaProviderAccountName());

        assertTrue(mBannerController.shouldShowChooseAccountBanner());
        assertFalse(mBannerController.shouldShowAccountUpdatedBanner());
        assertFalse(mBannerController.shouldShowCloudMediaAvailableBanner());
        assertFalse(mBannerController.shouldShowChooseAppBanner());

        mBannerController.onUserDismissedChooseAccountBanner();
        assertFalse(mBannerController.shouldShowChooseAccountBanner());
    }

    @Test
    public void testAccountUpdated() {
        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, /* cmpAccountName */ null);
        assertEquals(CMP_AUTHORITY, mBannerController.getCloudMediaProviderAuthority());
        assertNull(mBannerController.getCloudMediaProviderAccountName());

        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, CMP_ACCOUNT_NAME);
        assertEquals(CMP_AUTHORITY, mBannerController.getCloudMediaProviderAuthority());
        assertEquals(CMP_ACCOUNT_NAME, mBannerController.getCloudMediaProviderAccountName());

        assertTrue(mBannerController.shouldShowAccountUpdatedBanner());
        assertFalse(mBannerController.shouldShowCloudMediaAvailableBanner());
        assertFalse(mBannerController.shouldShowChooseAccountBanner());
        assertFalse(mBannerController.shouldShowChooseAppBanner());

        mBannerController.onUserDismissedAccountUpdatedBanner();
        assertFalse(mBannerController.shouldShowAccountUpdatedBanner());
    }

    @Test
    public void testChooseApp() {
        mBannerController.onChangeCloudMediaInfo(CMP_AUTHORITY, /* cmpAccountName */ null);
        assertEquals(CMP_AUTHORITY, mBannerController.getCloudMediaProviderAuthority());
        assertNull(mBannerController.getCloudMediaProviderAccountName());

        mBannerController.onChangeCloudMediaInfo(
                /* cmpAuthority */ null, /* cmpAccountName */ null);
        assertNull(mBannerController.getCloudMediaProviderAuthority());
        assertNull(mBannerController.getCloudMediaProviderAccountName());

        assertTrue(mBannerController.shouldShowChooseAppBanner());
        assertFalse(mBannerController.shouldShowCloudMediaAvailableBanner());
        assertFalse(mBannerController.shouldShowChooseAccountBanner());
        assertFalse(mBannerController.shouldShowAccountUpdatedBanner());

        mBannerController.onUserDismissedChooseAppBanner();
        assertFalse(mBannerController.shouldShowChooseAppBanner());
    }

    @Test
    public void testNoChange() {
        mBannerController.onChangeCloudMediaInfo(
                /* cmpAuthority */ null, /* cmpAccountName */ null);
        assertNull(mBannerController.getCloudMediaProviderAuthority());
        assertNull(mBannerController.getCloudMediaProviderAccountName());

        assertFalse(mBannerController.shouldShowCloudMediaAvailableBanner());
        assertFalse(mBannerController.shouldShowAccountUpdatedBanner());
        assertFalse(mBannerController.shouldShowChooseAccountBanner());
        assertFalse(mBannerController.shouldShowChooseAppBanner());
    }
}
