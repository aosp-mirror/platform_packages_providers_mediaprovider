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
import androidx.compose.material3.ExperimentalMaterial3Api
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.glide.GlideLoadable
import com.android.photopicker.core.glide.Resolution
import com.android.photopicker.util.hashCodeOf
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.signature.ObjectKey

/** Holds metadata for a type of media item like [Image] or [Video]. */
sealed interface Media : GlideLoadable, Grantable, Parcelable, Selectable {
    /** This is the ID that provider has shared with Picker */
    val mediaId: String

    /** This is the Picker ID auto-generated in Picker DB */
    val pickerId: Long

    /**
     * This is an optional field that holds the value of the current item's index relative to other
     * data in the Data Source.
     */
    val index: Int?
    val authority: String
    val mediaSource: MediaSource
    val mediaUri: Uri
    val glideLoadableUri: Uri
    val dateTakenMillisLong: Long
    val sizeInBytes: Long
    val mimeType: String
    val standardMimeTypeExtension: Int
    override val selectionSource: Telemetry.MediaLocation?
    override val mediaItemAlbum: Group.Album?
    override val isPreGranted: Boolean

    companion object {
        fun withSelectable(
            item: Media,
            selectionSource: Telemetry.MediaLocation,
            album: Group.Album?,
        ): Media {
            return when (item) {
                is Image -> item.copy(selectionSource = selectionSource, mediaItemAlbum = album)
                is Video -> item.copy(selectionSource = selectionSource, mediaItemAlbum = album)
            }
        }
    }

    override fun getSignature(resolution: Resolution): ObjectKey {
        return ObjectKey("${mediaUri}_$resolution")
    }

    override fun getLoadableUri(): Uri {
        return glideLoadableUri
    }

    override fun getDataSource(): DataSource {
        return when (mediaSource) {
            MediaSource.LOCAL -> DataSource.LOCAL
            MediaSource.REMOTE -> DataSource.REMOTE
        }
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
        out.writeString(index?.toString())
        out.writeString(authority)
        out.writeString(mediaSource.toString())
        out.writeString(mediaUri.toString())
        out.writeString(glideLoadableUri.toString())
        out.writeLong(dateTakenMillisLong)
        out.writeLong(sizeInBytes)
        out.writeString(mimeType)
        out.writeInt(standardMimeTypeExtension)
    }

    // TODO Make selectable values hold UNSET values instead of null
    /** Holds metadata for an image item. */
    data class Image
    constructor(
        override val mediaId: String,
        override val pickerId: Long,
        override val index: Int? = null,
        override val authority: String,
        override val mediaSource: MediaSource,
        override val mediaUri: Uri,
        override val glideLoadableUri: Uri,
        override val dateTakenMillisLong: Long,
        override val sizeInBytes: Long,
        override val mimeType: String,
        override val standardMimeTypeExtension: Int,
        override val isPreGranted: Boolean = false,
        override val selectionSource: Telemetry.MediaLocation? = null,
        override val mediaItemAlbum: Group.Album? = null,
    ) : Media {

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
        }

        /**
         * Implement a custom equals method since not all fields need to be equal to ensure the same
         * Image is being referenced. Image instances are considered equal to each other when three
         * fields match:
         * - mediaId (the id from the provider)
         * - authority (the authority of the provider)
         * - mediaSource ( Remote or Local )
         */
        override fun equals(other: Any?): Boolean {
            return other is Media &&
                other.mediaId == mediaId &&
                other.authority == authority &&
                other.mediaSource == mediaSource
        }

        /**
         * Implement a custom hashCode method since not all fields need to be equal to ensure the
         * same Image is being referenced. The object's hashed value is equal to its three fields
         * used in the equals comparison, to ensure objects that equal each other end up in the same
         * hash bucket.
         */
        override fun hashCode(): Int = hashCodeOf(mediaId, authority, mediaSource)

        companion object CREATOR : Parcelable.Creator<Image> {

            @OptIn(ExperimentalMaterial3Api::class)
            override fun createFromParcel(parcel: Parcel): Image {
                val image =
                    Image(
                        /* mediaId=*/ parcel.readString() ?: "",
                        /* pickerId=*/ parcel.readLong(),
                        /* index=*/ parcel.readString()?.toIntOrNull(),
                        /* authority=*/ parcel.readString() ?: "",
                        /* mediaSource=*/ MediaSource.valueOf(parcel.readString() ?: "LOCAL"),
                        /* mediaUri= */ Uri.parse(parcel.readString() ?: ""),
                        /* loadableUri= */ Uri.parse(parcel.readString() ?: ""),
                        /* dateTakenMillisLong=*/ parcel.readLong(),
                        /* sizeInBytes=*/ parcel.readLong(),
                        /* mimeType=*/ parcel.readString() ?: "",
                        /* standardMimeTypeExtension=*/ parcel.readInt(),
                    )
                return image
            }

            override fun newArray(size: Int): Array<Image?> {
                return arrayOfNulls(size)
            }
        }
    }

    // TODO Make selectable values hold UNSET values instead of null
    /** Holds metadata for a video item. */
    data class Video
    constructor(
        override val mediaId: String,
        override val pickerId: Long,
        override val index: Int? = null,
        override val authority: String,
        override val mediaSource: MediaSource,
        override val mediaUri: Uri,
        override val glideLoadableUri: Uri,
        override val dateTakenMillisLong: Long,
        override val sizeInBytes: Long,
        override val mimeType: String,
        override val standardMimeTypeExtension: Int,
        val duration: Int,
        override val isPreGranted: Boolean = false,
        override val selectionSource: Telemetry.MediaLocation? = null,
        override val mediaItemAlbum: Group.Album? = null,
    ) : Media {

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(duration)
        }

        /**
         * Implement a custom equals method since not all fields need to be equal to ensure the same
         * Video is being referenced. Video instances are considered equal to each other when three
         * fields match:
         * - mediaId (the id from the provider)
         * - authority (the authority of the provider)
         * - mediaSource ( Remote or Local )
         */
        override fun equals(other: Any?): Boolean {
            return other is Media &&
                other.mediaId == mediaId &&
                other.authority == authority &&
                other.mediaSource == mediaSource
        }

        /**
         * Implement a custom hashCode method since not all fields need to be equal to ensure the
         * same Video is being referenced. The object's hashed value is equal to its three fields
         * used in the equals comparison, to ensure objects that equal each other end up in the same
         * hash bucket.
         */
        override fun hashCode(): Int = hashCodeOf(mediaId, authority, mediaSource)

        companion object CREATOR : Parcelable.Creator<Video> {

            @OptIn(ExperimentalMaterial3Api::class)
            override fun createFromParcel(parcel: Parcel): Video {
                val video =
                    Video(

                        /* mediaId=*/ parcel.readString() ?: "",
                        /* pickerId=*/ parcel.readLong(),
                        /* index=*/ parcel.readString()?.toIntOrNull(),
                        /* authority=*/ parcel.readString() ?: "",
                        /* mediaSource=*/ MediaSource.valueOf(parcel.readString() ?: "LOCAL"),
                        /* mediaUri= */ Uri.parse(parcel.readString() ?: ""),
                        /* loadableUri= */ Uri.parse(parcel.readString() ?: ""),
                        /* dateTakenMillisLong=*/ parcel.readLong(),
                        /* sizeInBytes=*/ parcel.readLong(),
                        /* mimeType=*/ parcel.readString() ?: "",
                        /* standardMimeTypeExtension=*/ parcel.readInt(),
                        /* duration=*/ parcel.readInt(),
                    )
                return video
            }

            override fun newArray(size: Int): Array<Video?> {
                return arrayOfNulls(size)
            }
        }
    }
}
