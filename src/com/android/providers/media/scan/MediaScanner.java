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

package com.android.providers.media.scan;

import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_EVENT__REASON__DEMAND;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_EVENT__REASON__IDLE;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_EVENT__REASON__MOUNTED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_EVENT__REASON__UNKNOWN;

import android.content.Context;
import android.net.Uri;

import java.io.File;

public interface MediaScanner {
    public static final int REASON_UNKNOWN = MEDIA_PROVIDER_SCAN_EVENT__REASON__UNKNOWN;
    public static final int REASON_MOUNTED = MEDIA_PROVIDER_SCAN_EVENT__REASON__MOUNTED;
    public static final int REASON_DEMAND = MEDIA_PROVIDER_SCAN_EVENT__REASON__DEMAND;
    public static final int REASON_IDLE = MEDIA_PROVIDER_SCAN_EVENT__REASON__IDLE;

    public Context getContext();
    public void scanDirectory(File file, int reason);
    public Uri scanFile(File file, int reason);
    public void onDetachVolume(String volumeName);
}
