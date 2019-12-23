/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.util.Log;

public class Logging {
    public static final String TAG = "MediaProvider";
    public static final boolean LOGW = Log.isLoggable(TAG, Log.WARN);
    public static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);
}
