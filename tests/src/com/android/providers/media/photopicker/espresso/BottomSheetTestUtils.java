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

package com.android.providers.media.photopicker.espresso;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED;
import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;
import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.action.ViewActions;

import com.android.providers.media.R;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

class BottomSheetTestUtils {
    public static void assertBottomSheetState(Activity activity, int state) {
        final BottomSheetBehavior<View> bottomSheetBehavior =
                BottomSheetBehavior.from(activity.findViewById(R.id.bottom_sheet));
        assertThat(bottomSheetBehavior.getState()).isEqualTo(state);
        if (state == STATE_COLLAPSED) {
            final int peekHeight =
                    getBottomSheetPeekHeight(PhotoPickerBaseTest.getIsolatedContext());
            assertThat(bottomSheetBehavior.getPeekHeight()).isEqualTo(peekHeight);
        }
    }

    public static void swipeUp(ActivityScenario<PhotoPickerTestActivity> scenario) {
        // Register bottom sheet idling resource so that we don't read bottom sheet state when
        // in between changing states
        final BottomSheetIdlingResource bottomSheetIdlingResource =
                BottomSheetIdlingResource.register(scenario);

        try {
            // Swipe up and check that the PhotoPicker is in full screen mode
            bottomSheetIdlingResource.setExpectedState(STATE_EXPANDED);
            onView(withId(R.id.privacy_text)).perform(ViewActions.swipeUp());
            scenario.onActivity(
                    activity -> {
                        assertBottomSheetState(activity, STATE_EXPANDED);
                    });
        } finally {
            IdlingRegistry.getInstance().unregister(bottomSheetIdlingResource);
        }
    }

    private static int getBottomSheetPeekHeight(Context context) {
        final WindowManager windowManager = context.getSystemService(WindowManager.class);
        final Rect displayBounds = windowManager.getCurrentWindowMetrics().getBounds();
        return (int) (displayBounds.height() * 0.60);
    }
}
