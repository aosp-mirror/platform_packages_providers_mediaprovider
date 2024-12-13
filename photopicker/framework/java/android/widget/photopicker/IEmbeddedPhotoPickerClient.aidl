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

package android.widget.photopicker;

import android.os.Parcelable;
import android.net.Uri;
import java.util.List;
import android.widget.photopicker.EmbeddedPhotoPickerSessionResponse;
import android.widget.photopicker.ParcelableException;

/**
 * Internal interface used to send callbacks to the host apps.
 *
 * <p> Use {@link EmbeddedPhotoPickerClient} class rather than going through this class
 * directly. See {@link EmbeddedPhotoPickerClient} for more complete documentation.
 *
 * @hide
 */
oneway interface IEmbeddedPhotoPickerClient {

    void onSessionOpened(in EmbeddedPhotoPickerSessionResponse response);

    void onSessionError(in ParcelableException exception);

    void onUriPermissionGranted(in List<Uri> uri);

    void onUriPermissionRevoked(in List<Uri> uri);

    void onSelectionComplete();
}
