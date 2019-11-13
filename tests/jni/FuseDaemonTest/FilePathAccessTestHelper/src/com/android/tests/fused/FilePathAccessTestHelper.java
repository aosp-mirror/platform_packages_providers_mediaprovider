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
package com.android.tests.fused;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;


import com.android.tests.fused.lib.ReaddirTestHelper;
import static com.android.tests.fused.lib.ReaddirTestHelper.QUERY_TYPE;
import static com.android.tests.fused.lib.ReaddirTestHelper.READDIR_QUERY;
import static com.android.tests.fused.lib.ReaddirTestHelper.CREATE_FILE_QUERY;
import static com.android.tests.fused.lib.ReaddirTestHelper.DELETE_FILE_QUERY;

/**
 * App for FilePathAccessTest Functions.
 *
 * App is used to perform FilePathAccessTest functions as a different app. Based on the Query type
 * app can perform different functions and send the result back to host app.
 */
public class FilePathAccessTestHelper extends Activity {
    private static final String TAG = "FilePathAccessTestHelper";
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().hasExtra(QUERY_TYPE)) {
            String queryType = getIntent().getStringExtra(QUERY_TYPE);
            if (queryType.equals(READDIR_QUERY)) {
                sendDirectoryEntries(queryType);
            } else if (queryType.equals(CREATE_FILE_QUERY) ||
                    queryType.equals(DELETE_FILE_QUERY)) {
                createOrDeleteFile(queryType);
            } else {
                Log.e(TAG, "Unknown query received from launcher app");
            }
        } else {
            Log.e(TAG, "No query received from launcher app");
        }
    }

    private void sendDirectoryEntries(String queryType) {
        if (getIntent().hasExtra(queryType)) {
            final String directoryPath = getIntent().getStringExtra(queryType);
            ArrayList<String> directoryEntries = new ArrayList<String>();
            if (queryType.equals(READDIR_QUERY)) {
                directoryEntries = ReaddirTestHelper.readDirectory(directoryPath);
            }
            final Intent intent = new Intent(queryType);
            intent.putStringArrayListExtra(queryType, directoryEntries);
            sendBroadcast(intent);
        } else {
            Log.e(TAG, "Directory path not set from launcher app");
        }
    }

    private void createOrDeleteFile(String queryType) {
        if (getIntent().hasExtra(queryType)) {
            final String filePath = getIntent().getStringExtra(queryType);
            final File file = new File(filePath);
            boolean returnStatus = false;
            try {
                if (queryType.equals(CREATE_FILE_QUERY)) {
                    returnStatus = file.createNewFile();
                } else if (queryType.equals(DELETE_FILE_QUERY)) {
                    returnStatus = file.delete();
                }
            } catch(IOException e) {
                Log.e(TAG, "IOException occurred while creating/deleting " + filePath);
            }
            final Intent intent = new Intent(queryType);
            intent.putExtra(queryType, returnStatus);
            sendBroadcast(intent);
        } else {
            Log.e(TAG, "file path not set from launcher app");
        }
    }
}
