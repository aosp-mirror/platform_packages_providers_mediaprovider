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
import android.provider.EmbeddedPhotoPickerFeatureInfo;
import android.provider.IEmbeddedPhotoPickerClient;

/**
* Internal interface used to open a new session for embedded photopicker
*
* <p> Use {@link com.android.EmbeddedPhotoPickerProvider} class rather than going through
* this binder class directly. See {@link com.android.EmbeddedPhotoPickerProvider} for
* more complete documentation
*
* @hide
*/
oneway interface IEmbeddedPhotoPicker {

    void openSession(String packageName,
                     in IBinder hostToken,
                     int displayId,
                     int width,
                     int height,
                     in EmbeddedPhotoPickerFeatureInfo featureInfo, // parcelable class
                     in IEmbeddedPhotoPickerClient clientCallback
                     );
}



