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

import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__REASON__DEMAND;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__REASON__IDLE;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__REASON__MOUNTED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__REASON__UNKNOWN;

import android.content.Context;
import android.net.Uri;

import com.android.providers.media.MediaVolume;

import java.io.File;

public interface MediaScanner {
    int REASON_UNKNOWN = MEDIA_PROVIDER_SCAN_OCCURRED__REASON__UNKNOWN;
    int REASON_MOUNTED = MEDIA_PROVIDER_SCAN_OCCURRED__REASON__MOUNTED;
    int REASON_DEMAND = MEDIA_PROVIDER_SCAN_OCCURRED__REASON__DEMAND;
    int REASON_IDLE = MEDIA_PROVIDER_SCAN_OCCURRED__REASON__IDLE;

    Context getContext();
    void scanDirectory(File file, int reason);
    Uri scanFile(File file, int reason);
    void onDetachVolume(MediaVolume volume);
    void onIdleScanStopped();
    void onDirectoryDirty(File file);
}
