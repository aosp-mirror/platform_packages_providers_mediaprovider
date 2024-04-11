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

/** Holds metadata for a type of media item like [Image] or [Video]. */
sealed interface Media : GlideLoadable, Parcelable {
    /** This is the ID that provider has shared with Picker */
    val mediaId: String

    /** This is the Picker ID auto-generated in Picker DB */
    val pickerId: Long
    val authority: String
    val uri: Uri
    val dateTakenMillisLong: Long
    val sizeInBytes: Long
    val mimeType: String
    val standardMimeTypeExtension: Int

    override fun getSignature(resolution: Resolution): ObjectKey {
        return ObjectKey("${uri}_$resolution")
    }

    override fun getLoadableUri(): Uri {
        return uri
    }

    override fun getDataSource(): DataSource {
        // TODO(b/303782563): Use MediaSource to determine datasource.
        return DataSource.LOCAL
    }

    override fun getTimestamp(): Long {
        return dateTakenMillisLong
    }

    /** Implemented for [Parcelable], but always returns 0 since Media is never a FileDescriptor. */
    override fun describeContents(): Int {
        return 0
    }

    /** Implemented for [Parcelable], and handles all the common attributes. */
    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeString(mediaId)
        out.writeLong(pickerId)
        out.writeString(authority)
        out.writeString(uri.toString())
        out.writeLong(dateTakenMillisLong)
        out.writeLong(sizeInBytes)
        out.writeString(mimeType)
        out.writeInt(standardMimeTypeExtension)
    }

    /** Holds metadata for an image item. */
    data class Image(
        override val mediaId: String,
        override val pickerId: Long,
        override val authority: String,
        override val uri: Uri,
        override val dateTakenMillisLong: Long,
        override val sizeInBytes: Long,
        override val mimeType: String,
        override val standardMimeTypeExtension: Int,
    ) : Media {

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
        }

        companion object CREATOR : Parcelable.Creator<Image> {

            override fun createFromParcel(parcel: Parcel): Image {
                val image =
                    Image(
                        /* mediaId=*/ parcel.readString() ?: "",
                        /* pickerId=*/ parcel.readLong(),
                        /* authority=*/ parcel.readString() ?: "",
                        /* uri= */ Uri.parse(parcel.readString() ?: ""),
                        /* dateTakenMillisLong=*/ parcel.readLong(),
                        /* sizeInBytes=*/ parcel.readLong(),
                        /* mimeType=*/ parcel.readString() ?: "",
                        /* standardMimeTypeExtension=*/ parcel.readInt(),
                    )
                parcel.recycle()
                return image
            }

            override fun newArray(size: Int): Array<Image?> {
                return arrayOfNulls(size)
            }
        }
    }

    /** Holds metadata for a video item. */
    data class Video(
        override val mediaId: String,
        override val pickerId: Long,
        override val authority: String,
        override val uri: Uri,
        override val dateTakenMillisLong: Long,
        override val sizeInBytes: Long,
        override val mimeType: String,
        override val standardMimeTypeExtension: Int,
        val duration: Int,
    ) : Media {

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(duration)
        }

        companion object CREATOR : Parcelable.Creator<Video> {

            override fun createFromParcel(parcel: Parcel): Video {
                return Video(

                    /* mediaId=*/ parcel.readString() ?: "",
                    /* pickerId=*/ parcel.readLong(),
                    /* authority=*/ parcel.readString() ?: "",
                    /* uri= */ Uri.parse(parcel.readString() ?: ""),
                    /* dateTakenMillisLong=*/ parcel.readLong(),
                    /* sizeInBytes=*/ parcel.readLong(),
                    /* mimeType=*/ parcel.readString() ?: "",
                    /* standardMimeTypeExtension=*/ parcel.readInt(),
                    /* duration=*/ parcel.readInt(),
                )
            }

            override fun newArray(size: Int): Array<Video?> {
                return arrayOfNulls(size)
            }
        }
    }
}
