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

package com.android.providers.media.mediacognitionservices;

import android.content.UriMatcher;
import android.os.CancellationSignal;
import android.provider.MediaCognitionGetVersionsCallback;
import android.provider.MediaCognitionProcessingCallback;
import android.provider.MediaCognitionProcessingRequest;
import android.provider.MediaCognitionProcessingResponse;
import android.provider.MediaCognitionProcessingVersions;
import android.provider.MediaCognitionService;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestMediaCognitionService extends MediaCognitionService {
    private static final int TEST_IMAGE = 1;
    private static final int TEST_IMAGE_LARGE_DATA = 2;

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUriMatcher.addURI("media", "test_image/#", TEST_IMAGE);

        sUriMatcher.addURI("media", "test_image_large_data/#", TEST_IMAGE_LARGE_DATA);
    }
    @Override
    public void onProcessMedia(@NonNull List<MediaCognitionProcessingRequest> requests,
            CancellationSignal cancellationSignal,
            @NonNull MediaCognitionProcessingCallback callback) {
        if (requests.size() == 0) {
            callback.onFailure("empty request");
        }
        List<MediaCognitionProcessingResponse> responses =
                new ArrayList<MediaCognitionProcessingResponse>();
        int count = 1;
        String largeString = null;
        for (MediaCognitionProcessingRequest request: requests) {
            MediaCognitionProcessingResponse.Builder responseBuilder =
                    new MediaCognitionProcessingResponse.Builder(request);
            String image_ocr_latin = "";
            String image_label = "";
            switch (sUriMatcher.match(request.getUri())) {
                case TEST_IMAGE : {
                    image_ocr_latin = "image_ocr_latin_" + count;
                    image_label = "image_label_" + count;
                    break;
                }
                case TEST_IMAGE_LARGE_DATA : {
                    if (largeString == null) {
                        largeString = generateLargeString();
                    }
                    image_ocr_latin = largeString;
                    image_label = largeString;
                }
            }
            if (request.checkProcessingRequired(ProcessingTypes.IMAGE_LABEL)) {
                responseBuilder.setImageLabels(new ArrayList<String>(
                        Arrays.asList(image_label)));
            }
            if (request.checkProcessingRequired(ProcessingTypes.IMAGE_OCR_LATIN)) {
                responseBuilder.setImageOcrLatin(image_ocr_latin);
            }
            responses.add(responseBuilder.build());
            count++;
        }
        callback.onSuccess(responses);
    }

    private String generateLargeString() {
        StringBuilder builder = new StringBuilder(25000);
        for (int i = 0; i < 25000; i++) {
            builder.append('a');
        }
        return builder.toString();
    }


    @Override
    public void onGetProcessingVersions(@NonNull MediaCognitionGetVersionsCallback callback) {
        MediaCognitionProcessingVersions versions = new MediaCognitionProcessingVersions();
        versions.setProcessingVersion(MediaCognitionService.ProcessingTypes.IMAGE_LABEL, 1);
        versions.setProcessingVersion(MediaCognitionService.ProcessingTypes.IMAGE_OCR_LATIN, 1);
        callback.onSuccess(versions);
    }
}
