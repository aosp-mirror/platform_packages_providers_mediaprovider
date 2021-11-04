/**
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

package com.android.providers.media.tests.utils;

import android.os.Bundle;
import android.os.SystemClock;

import android.util.Log;

import androidx.test.InstrumentationRegistry;

import java.util.concurrent.TimeUnit;

/**
 * General Timer function for MediaProvider tests.
 */
public class Timer {
    private static final String TAG = "Timer";

    private final String name;
    private int count;
    private long duration;
    private long start;
    private long maxRunDuration = 0;

    public Timer(String name) {
        this.name = name;
    }

    public void start() {
        if (start != 0) {
            throw new IllegalStateException();
        } else {
            start = SystemClock.elapsedRealtimeNanos();
        }
    }

    public void stop() {
        long currentRunDuration = 0;
        if (start == 0) {
            throw new IllegalStateException();
        } else {
            currentRunDuration = (SystemClock.elapsedRealtimeNanos() - start);
            maxRunDuration = (currentRunDuration > maxRunDuration) ? currentRunDuration :
                maxRunDuration;
            duration += currentRunDuration;
            start = 0;
            count++;
        }
    }

    public long getAverageDurationMillis() {
        return TimeUnit.MILLISECONDS.convert(duration / count, TimeUnit.NANOSECONDS);
    }

    public long getMaxDurationMillis() {
        return TimeUnit.MILLISECONDS.convert(maxRunDuration, TimeUnit.NANOSECONDS);
    }

    public void dumpResults() {
        final long duration = getAverageDurationMillis();
        Log.v(TAG, name + ": " + duration + "ms");

        final Bundle results = new Bundle();
        results.putLong(name + " (ms)", duration);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, results);
    }
}
