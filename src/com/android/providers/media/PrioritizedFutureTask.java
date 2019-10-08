/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.os.SystemClock;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

public class PrioritizedFutureTask<T> extends FutureTask<T>
        implements Comparable<PrioritizedFutureTask<T>> {
    static final int PRIORITY_LOW = 20;
    static final int PRIORITY_NORMAL = 10;
    static final int PRIORITY_HIGH = 5;
    static final int PRIORITY_CRITICAL = 0;

    final long requestTime;
    final int priority;

    public PrioritizedFutureTask(Callable<T> callable, int priority) {
        super(callable);
        this.requestTime = SystemClock.elapsedRealtime();
        this.priority = priority;
    }

    @Override
    public final int compareTo(PrioritizedFutureTask<T> other) {
        if (this.priority != other.priority) {
            return this.priority < other.priority ? -1 : 1;
        }
        if (this.requestTime != other.requestTime) {
            return this.requestTime < other.requestTime ? -1 : 1;
        }
        return 0;
    }
}
