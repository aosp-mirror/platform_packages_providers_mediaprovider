/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.modules.utils.BackgroundThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BackgroundThreadUtils {
    public static void waitForIdle() {
        final CountDownLatch latch = new CountDownLatch(1);
        BackgroundThread.getExecutor().execute(latch::countDown);
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }

    }
}
