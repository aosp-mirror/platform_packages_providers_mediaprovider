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
import com.android.photopicker.core.glide.Resolution
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.signature.ObjectKey

/** Holds metadata for a group of media items. */
sealed interface Group : GlideLoadable, Parcelable {
    /** Unique identifier for this group */
    val id: String

    /**
     * Holds metadata for a album item. It is a type of a [Group] object because it represents a
     * collection of media items.
     */
    data class Album(
        /** This is the ID provided by the [Provider] of this data */
        override val id: String,

        /** This is the Picker ID auto-generated in Picker DB */
        val pickerId: Long,
        val authority: String,
        val dateTakenMillisLong: Long,
        val displayName: String,
        val coverUri: Uri,
        val coverMediaSource: MediaSource,
    ) : Group {
        override fun getSignature(resolution: Resolution): ObjectKey {
            return ObjectKey("${coverUri}_$resolution")
        }

        override fun getLoadableUri(): Uri {
            return coverUri
        }

        override fun getDataSource(): DataSource {
            return when (coverMediaSource) {
                MediaSource.LOCAL -> DataSource.LOCAL
                MediaSource.REMOTE -> DataSource.REMOTE
            }
        }

        override fun getTimestamp(): Long {
            return dateTakenMillisLong
        }

        override fun describeContents(): Int {
            return 0
        }

        /** Implemented for [Parcelable], and handles all the common attributes. */
        override fun writeToParcel(out: Parcel, flags: Int) {
            out.writeString(id)
            out.writeLong(pickerId)
            out.writeString(authority)
            out.writeLong(dateTakenMillisLong)
            out.writeString(displayName)
            out.writeString(coverUri.toString())
            out.writeString(coverMediaSource.name)
        }

        companion object CREATOR : Parcelable.Creator<Album> {

            override fun createFromParcel(parcel: Parcel): Album {
                val album =
                    Album(
                        /* id =*/ parcel.readString() ?: "",
                        /* pickerId=*/ parcel.readLong(),
                        /* authority=*/ parcel.readString() ?: "",
                        /* dateTakenMillisLong=*/ parcel.readLong(),
                        /* displayName =*/ parcel.readString() ?: "",
                        /* uri= */ Uri.parse(parcel.readString() ?: ""),
                        /* coverUriMediaSource =*/ MediaSource.valueOf(
                            parcel.readString() ?: "LOCAL"
                        ),
                    )
                return album
            }

            override fun newArray(size: Int): Array<Album?> {
                return arrayOfNulls(size)
            }
        }
    }
}
