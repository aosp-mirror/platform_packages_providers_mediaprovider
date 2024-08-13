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

package android.provider;

import android.provider.ParcelableException;

/**
 * A callback interface used for communication between {@link MediaStore} and
 * {@link com.android.providers.media.MediaProvider} to return results
 * for {@link OpenAssetFileRequest}.
 *
 * @hide
 */
oneway interface IOpenAssetFileCallback {
    void onSuccess(in AssetFileDescriptor afd);
    void onFailure(in ParcelableException exception);
}
