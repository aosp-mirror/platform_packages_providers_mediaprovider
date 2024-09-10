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

import android.content.res.Configuration;

/**
* Internal interface used to notify service about different events by apps.
*
* <p> Apps use {@link EmbeddedPhotoPickerSession} class rather than going
* through this class directly.
* See {@link EmbeddedPhotoPickerSession} for more complete documentation.
*
* @hide
*/
oneway interface IEmbeddedPhotoPickerSession {

    void close();

    void notifyVisibilityChanged(boolean isVisible);

    void notifyResized(int width, int height);

    void notifyConfigurationChanged(in Configuration configuration);

    void notifyPhotopickerExpanded(boolean isExpanded);

    void requestRevokeUriPermission(in List<Uri> uris);
}
