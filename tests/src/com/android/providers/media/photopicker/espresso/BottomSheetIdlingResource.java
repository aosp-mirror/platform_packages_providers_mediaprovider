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

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING;
import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_SETTLING;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.IdlingResource;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.providers.media.R;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

public class BottomSheetIdlingResource implements IdlingResource {
    private static final int NO_EXPECTED_STATE = -1;
    private final BottomSheetBehavior<View> mBottomSheetBehavior;
    private ResourceCallback mResourceCallback;
    private int mExpectedState = NO_EXPECTED_STATE;

    public BottomSheetIdlingResource(View bottomSheetView) {
        mBottomSheetBehavior = BottomSheetBehavior.from(bottomSheetView);
        mBottomSheetBehavior.addBottomSheetCallback(new IdleListener());
    }

    @Override
    public String getName() {
        return BottomSheetIdlingResource.class.getName();
    }

    @Override
    public boolean isIdleNow() {
        int state = mBottomSheetBehavior.getState();

        if (isIdleState(state)) {
            if (mResourceCallback != null) {
                mResourceCallback.onTransitionToIdle();
            }
            return true;
        }
        return false;
    }

    @Override
    public void registerIdleTransitionCallback(ResourceCallback resourceCallback) {
        mResourceCallback = resourceCallback;
    }

    private boolean isExpectedState(int state) {
        // Checks if expected state is not set or if the current state is as expected.
        return mExpectedState == NO_EXPECTED_STATE || state == mExpectedState;
    }

    private boolean isIdleState(int state) {
        return state != STATE_DRAGGING && state != STATE_SETTLING;
    }

    /**
     * Set expected state for BottomSheet to stabilise BottomSheet tests. This waits for
     * BottomSheet to come to the expected state.
     */
    public void setExpectedState(int state) {
        mExpectedState = state;
    }

    private final class IdleListener extends BottomSheetBehavior.BottomSheetCallback {
        @Override
        public void onStateChanged(@NonNull View bottomSheet, int newState) {
            if (mResourceCallback != null && isIdleNow() && isExpectedState(newState)) {
                mResourceCallback.onTransitionToIdle();
            }
        }

        @Override
        public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
    }

    /**
     * @return {@link BottomSheetIdlingResource} that is registered to the activity related to the
     *     given {@link ActivityScenarioRule}.
     * @param scenario
     */
    public static BottomSheetIdlingResource register(ActivityScenario scenario) {
        final BottomSheetIdlingResource[] idlingResources = new BottomSheetIdlingResource[1];
        scenario.onActivity(
                (activity -> {
                    idlingResources[0] =
                            new BottomSheetIdlingResource(activity.findViewById(R.id.bottom_sheet));
                }));
        IdlingRegistry.getInstance().register(idlingResources[0]);
        return idlingResources[0];
    }
}
