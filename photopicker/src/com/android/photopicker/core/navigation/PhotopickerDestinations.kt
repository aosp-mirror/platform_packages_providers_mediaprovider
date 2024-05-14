/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.core.navigation

enum class PhotopickerDestinations(val route: String) {

    // The default route, only used when no other Routes are registered.
    DEFAULT("default"),

    // The main route which shows a grid of the user's photos.
    PHOTO_GRID("photogrid"),

    // Preview routes that show Full resolution media for the user to preview.
    // See the [NavController] extensions to navigate to these routes, as [PREVIEW_MEDIA] requires
    // data to be provided to the route in order to work correctly.
    PREVIEW_MEDIA("preview/media"), // Preview route for an individual item
    PREVIEW_SELECTION("preview/selection"), // Preview route for the current selection

    // The route which shows a grid of the user's albums.
    ALBUM_GRID("albumgrid"),
}
