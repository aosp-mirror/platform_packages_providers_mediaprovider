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

package com.android.providers.media.photopicker;

import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;

public class TestableContentObserverCallback
        implements NotificationContentObserver.ContentObserverCallback {

    @Nullable private CountDownLatch mLatch;

    public TestableContentObserverCallback() {}

    public TestableContentObserverCallback(CountDownLatch latch) {
        this.mLatch = latch;
    }

    @Override
    public void onNotificationReceived(String dateTakenMs, String albumId) {
        // do nothing
        if (mLatch != null) {
            mLatch.countDown();
        }
    }
}
