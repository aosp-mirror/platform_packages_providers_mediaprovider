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

package com.android.providers.media.transcode.testapp;

import static com.android.providers.media.transcode.TranscodeTestConstants.INTENT_EXTRA_CALLING_PKG;
import static com.android.providers.media.transcode.TranscodeTestConstants.INTENT_EXTRA_PATH;
import static com.android.providers.media.transcode.TranscodeTestConstants.OPEN_FILE_QUERY;
import static com.android.providers.media.transcode.TranscodeTestConstants.INTENT_QUERY_TYPE;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Helper app for TranscodeTest.
 *
 * <p>Used to perform TranscodeTest functions as a different app. Based on the Query type
 * app can perform different functions and send the result back to host app.
 */
public class TranscodeTestHelper extends Activity {
    private static final String TAG = "TranscodeTestHelper";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String queryType = getIntent().getStringExtra(INTENT_QUERY_TYPE);
        if (!OPEN_FILE_QUERY.equals(queryType)) {
            throw new IllegalStateException(
                    "Unknown query received from launcher app: " + queryType);
        }

        final File file = new File(getIntent().getStringExtra(INTENT_EXTRA_PATH));
        Uri contentUri = FileProvider.getUriForFile(this, getPackageName(), file);

        final Intent intent = new Intent(queryType);
        intent.putExtra(queryType, contentUri);

        // Grant permission to the calling package
        getApplicationContext().grantUriPermission(getIntent().getStringExtra(
                        INTENT_EXTRA_CALLING_PKG),
                contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        sendBroadcast(intent);
    }
}
