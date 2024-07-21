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

import android.content.res.Configuration;

/**
* Internal interface used to notify service about different events by apps.
*
* <p> Apps use {@link EmbeddedPhotopickerSession} class rather than going
* through this class directly.
* See {@link EmbeddedPhotopickerSession} for more complete documentation.
*
* @hide
*/
oneway interface IEmbeddedPhotopickerSession {

    /**
     * Indicate photopicker to close this session
     */
    void close();

    /**
     * Notifies photopicker that embedded picker's view or its parent view's
     * visibility changes
     */
    void notifyVisibilityChanged(boolean isVisible);

    /**
     * Notifies photopicker that host presentation area has changed
     */
    void notifyResized(int width, int height);

    /**
     * Notifies photopicker that host side configuration has changed
     */
    void notifyConfigurationChanged(in Configuration configuration);

    /**
     * Notifies photopicker when user switches picker between expanded/collapsed
     */
    void notifyPhotopickerExpanded(boolean isExpanded);
}
