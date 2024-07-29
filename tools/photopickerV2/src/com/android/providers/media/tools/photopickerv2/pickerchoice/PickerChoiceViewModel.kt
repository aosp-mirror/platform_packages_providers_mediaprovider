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
package com.android.providers.media.tools.photopickerv2.pickerchoice

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.app.Application
import android.content.ContentResolver
import android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PickerChoiceViewModel is responsible for managing the state and logic
 * of the PickerChoice feature.
 */
class PickerChoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val _permissionRequest = MutableLiveData<Array<String>>()
    val permissionRequest: LiveData<Array<String>> = _permissionRequest

    private val _media = MutableLiveData<List<Media>>(emptyList())
    val media: LiveData<List<Media>> get() = _media

    private val _latestSelectionOnly = MutableLiveData(false)
    val latestSelectionOnly: LiveData<Boolean> get() = _latestSelectionOnly

    fun setLatestSelectionOnly(enabled: Boolean) {
        _latestSelectionOnly.value = enabled
    }

    /**
     * Requests the necessary permissions for accessing media on the device.
     *
     * This method sets the appropriate permissions to request based on the
     * provided parameters and the Android version.
     *
     * @param imagesOnly a Boolean flag indicating if only image permissions should be requested.
     * @param videosOnly a Boolean flag indicating if only video permissions should be requested.
     */
    fun requestAppPermissions(imagesOnly: Boolean = false, videosOnly: Boolean = false) {
        when {
            imagesOnly -> {
                _permissionRequest.value = arrayOf(READ_MEDIA_IMAGES)
            }
            videosOnly -> {
                _permissionRequest.value = arrayOf(READ_MEDIA_VIDEO)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                _permissionRequest.value = arrayOf(
                    READ_MEDIA_IMAGES,
                    READ_MEDIA_VIDEO,
                    READ_MEDIA_VISUAL_USER_SELECTED
                )
            }
        }
    }

    /**
     * Checks the permissions for accessing media on the device.
     *
     * This method checks if the application has been granted the
     * READ_MEDIA_VISUAL_USER_SELECTED permission. If the device is
     * running Android 14 (UPSIDE_DOWN_CAKE) or higher and the permission
     * is granted, it shows a toast indicating partial access. Otherwise,
     * it shows a toast indicating access denied.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun checkPermissions(contentResolver: ContentResolver) {
        val context = getApplication<Application>().applicationContext
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    ContextCompat.checkSelfPermission(context, READ_MEDIA_VISUAL_USER_SELECTED) ==
                    PERMISSION_GRANTED -> {
                Toast.makeText(context, "Partial access on Android 14 or higher",
                    Toast.LENGTH_SHORT).show()
                fetchMedia(contentResolver)
            }
            else -> {
                Toast.makeText(context, "Access denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun fetchMedia(contentResolver: ContentResolver) {
        viewModelScope.launch {
            _media.value = getMedia(contentResolver)
        }
    }

    data class Media(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mimeType: String,
    )
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun getMedia(
        contentResolver: ContentResolver
    ): List<Media> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
        )

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val mediaList = mutableListOf<Media>()

        // TODO:  BuildCompat.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 12
        val queryArgs = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            latestSelectionOnly.value == true
        ) {
            bundleOf(
                QUERY_ARG_SQL_SORT_ORDER to "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                "android:query-arg-latest-selection-only" to true
            )
        } else {
            null
        }

        contentResolver.query(
            collectionUri,
            projection,
            queryArgs,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val displayNameColumn = cursor.getColumnIndexOrThrow(
                MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collectionUri, cursor.getLong(idColumn))
                val name = cursor.getString(displayNameColumn)
                val size = cursor.getLong(sizeColumn)
                val mimeType = cursor.getString(mimeTypeColumn)

                val media = Media(uri, name, size, mimeType)
                mediaList.add(media)
            }
        }
        return@withContext mediaList
    }
}
