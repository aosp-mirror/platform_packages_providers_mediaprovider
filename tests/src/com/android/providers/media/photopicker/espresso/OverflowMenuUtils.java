/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.providers.media.photopicker.espresso;

import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;

import static org.junit.Assert.fail;

public class OverflowMenuUtils {

    public static void assertOverflowMenuNotShown() {
        try {
            openActionBarOverflowOrOptionsMenu(PhotoPickerBaseTest.getIsolatedContext());
            fail("Overflow menu is only visible with GET_CONTENT intent");
        } catch (Exception e) {
            // This is normal as we don't have overflow menu.
        }
    }
}
