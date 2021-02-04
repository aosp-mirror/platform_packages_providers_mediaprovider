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

package com.android.providers.media.metrics;

import android.app.StatsManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.util.StatsEvent;

import java.util.List;

/**
 * A "static" class providing the boilerplate for handling the pulling of metrics by statsd.
 * All individuals using this should accumulate their metrics per their policies.
 */
public class StatsdPuller {
    private static final String TAG = "StatsdPuller";

    private static final StatsPullCallbackHandler STATS_PULL_CALLBACK_HANDLER =
            new StatsPullCallbackHandler();

    private static boolean isInitialized = false;

    public static void initialize(Context context) {
        if (isInitialized) {
            return;
        }

        final StatsManager statsManager = context.getSystemService(StatsManager.class);
        if (statsManager == null) {
            Log.e(TAG, "Error retrieving StatsManager. Cannot initialize StatsdPuller.");
        } else {
            // use the same callback handler for registering for all the tags.
            isInitialized = true;
        }
    }

    public static boolean isInitialized() {
        return isInitialized;
    }

    private static class StatsPullCallbackHandler implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            // handle the tags appropriately.
            return StatsManager.PULL_SUCCESS;
        }
    }
}
