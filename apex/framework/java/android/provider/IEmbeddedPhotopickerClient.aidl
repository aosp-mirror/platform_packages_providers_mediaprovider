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

package android.provider;

import android.os.Parcelable;
import android.net.Uri;
import android.provider.EmbeddedPhotopickerSessionResponse;

/**
 * Internal interface used to send callbacks to the host apps.
 *
 * <p> Use {@link EmbeddedPhotopickerClient} class rather than going through this class
 * directly. See {@link EmbeddedPhotopickerClient} for more complete documentation.
 *
 * @hide
 */
oneway interface IEmbeddedPhotopickerClient {

    void onSessionOpened(in EmbeddedPhotopickerSessionResponse response);

    void onSessionError(String errorMsg);

    void onItemSelected(in Uri uri);

    void onItemDeselected(in Uri uri);
}




