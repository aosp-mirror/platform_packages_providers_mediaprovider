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

package com.android.providers.media.util;

import static com.android.providers.media.util.TestUtils.QUERY_TYPE;
import static com.android.providers.media.util.TestUtils.RUN_INFINITE_ACTIVITY;

import android.app.Activity;
import android.os.Bundle;


public class TestAppActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        String queryType = getIntent().getStringExtra(QUERY_TYPE);
        queryType = queryType == null ? "null" : queryType;

        switch (queryType) {
            case RUN_INFINITE_ACTIVITY:
                while (true) {
                }
            default:
                throw new IllegalStateException(
                        "Unknown query received from launcher app: " + queryType);
        }
    }
}
