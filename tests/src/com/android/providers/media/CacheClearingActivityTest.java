/*
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

package com.android.providers.media;

import android.app.Instrumentation;
import android.content.Intent;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * We already have solid coverage of this logic in {@code CtsProviderTestCases},
 * but the coverage system currently doesn't measure that, so we add the bare
 * minimum local testing here to convince the tooling that it's covered.
 */
@RunWith(AndroidJUnit4.class)
public class CacheClearingActivityTest {
    @Test
    public void testSimple() throws Exception {
        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        final Intent intent = new Intent(inst.getContext(), GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final GetResultActivity activity = (GetResultActivity) inst.startActivitySync(intent);
        activity.startActivityForResult(createIntent(), 42);
    }

    private static Intent createIntent() {
        final Intent intent = new Intent(null, null,
                InstrumentationRegistry.getContext(), CacheClearingActivity.class);
        return intent;
    }
}
