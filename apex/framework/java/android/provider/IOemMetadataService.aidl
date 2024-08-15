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

import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;

/**
* @hide
*/
oneway interface IOemMetadataService {

   /**
    * Method to get a callback of supported mime-types by the OEM metadata provider. MediaProvider
    * module will be making calling to get OEM custom data only for files which have one of the
    * supported mimetypes. List of supported mime types will be set in the callback.
    */
   void getSupportedMimeTypes(in RemoteCallback callback);


   /**
    * Method to get a callback of OEM custom metadata for file whose file descriptor has been
    * passed. A key-value map of metadata is expected. List of keys and values will be set in the
    * callback.
    */
   void getOemCustomData(in ParcelFileDescriptor fd, in RemoteCallback callback);
}
