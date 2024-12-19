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

package com.android.photopicker.data.model

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import com.android.photopicker.core.glide.GlideLoadable
import com.android.photopicker.core.glide.ParcelableGlideLoadable
import com.android.photopicker.core.glide.Resolution
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.signature.ObjectKey

/**
 * An icon is a simple object which points to a media resource can be loaded by [Glide] because it
 * implements the [GlideLoadable] interface.
 */
data class Icon(val uri: Uri, val mediaSource: MediaSource) : ParcelableGlideLoadable {
    override fun getSignature(resolution: Resolution): ObjectKey {
        return ObjectKey("${uri}_$resolution")
    }

    override fun getLoadableUri(): Uri {
        return uri
    }

    override fun getDataSource(): DataSource {
        return when (mediaSource) {
            MediaSource.LOCAL -> DataSource.LOCAL
            MediaSource.REMOTE -> DataSource.REMOTE
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeString(uri.toString())
        out.writeString(mediaSource.name)
    }

    companion object CREATOR : Parcelable.Creator<Icon> {

        override fun createFromParcel(parcel: Parcel): Icon {
            return Icon(
                uri = Uri.parse(parcel.readString() ?: ""),
                mediaSource = MediaSource.valueOf(parcel.readString() ?: "LOCAL"),
            )
        }

        override fun newArray(size: Int): Array<Icon?> {
            return arrayOfNulls(size)
        }
    }
}
