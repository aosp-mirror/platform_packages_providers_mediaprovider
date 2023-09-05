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

package com.android.providers.media.photopicker.util;

import android.os.Looper;

/**
 * Provide the utility methods to handle thread.
 */
public class ThreadUtils {
    /**
     * Assert if the current {@link Thread} is the {@link androidx.annotation.MainThread}.
     */
    public static void assertMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            return;
        }
        throw new IllegalStateException("Must be called from the Main thread. Current thread: "
                + Thread.currentThread());
    }

    /**
     * Assert if the current {@link Thread} is NOT the {@link androidx.annotation.MainThread}.
     */
    public static void assertNonMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalStateException("Must NOT be called from the Main thread."
                    + " Current thread: " + Thread.currentThread());
        }
    }
}
