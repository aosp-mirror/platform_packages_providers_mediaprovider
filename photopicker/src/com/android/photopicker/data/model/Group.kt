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

/** Holds metadata for a group of media items. */
sealed interface Group : Parcelable {
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
    ) : Group, GlideLoadable {
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

    /**
     * Holds metadata for a category item. It is a type of a [Group] object which can either hold
     * other categories or media sets.
     */
    data class Category(
        /** This is the ID provided by the [Provider] of this data */
        override val id: String,

        /** This is the Picker ID generated in Picker Backend */
        val pickerId: Long,
        /** Authority of the source [Provider]. */
        val authority: String,
        val displayName: String?,
        val categoryType: CategoryType,
        val icons: List<ParcelableGlideLoadable>,
        val isLeafCategory: Boolean,
    ) : Group {

        override fun describeContents(): Int {
            return 0
        }

        /** Implemented for [Parcelable], and handles all the common attributes. */
        override fun writeToParcel(out: Parcel, flags: Int) {
            out.writeString(id)
            out.writeLong(pickerId)
            out.writeString(authority)
            out.writeString(displayName)
            out.writeString(categoryType.name)
            out.writeParcelableList(icons, /* flags */ 0)
            out.writeBoolean(isLeafCategory)
        }

        companion object CREATOR : Parcelable.Creator<Category> {

            override fun createFromParcel(parcel: Parcel): Category {
                @Suppress("DEPRECATION") // For backward-compatibility
                return Category(
                    id = parcel.readString() ?: "",
                    pickerId = parcel.readLong(),
                    authority = parcel.readString() ?: "",
                    displayName = parcel.readString(),
                    categoryType =
                        CategoryType.valueOf(
                            parcel.readString() ?: CategoryType.PEOPLE_AND_PETS.name
                        ),
                    icons =
                        arrayListOf<ParcelableGlideLoadable>().apply {
                            parcel.readParcelableList(
                                this,
                                ParcelableGlideLoadable::class.java.classLoader,
                            )
                        },
                    isLeafCategory = parcel.readBoolean(),
                )
            }

            override fun newArray(size: Int): Array<Category?> {
                return arrayOfNulls(size)
            }
        }
    }

    /**
     * Holds metadata for a media set item. It is a type of a [Group] object which contains media
     * items.
     *
     * It is very similar to albums because they both contain media items, but it's a bit more
     * generic and meant to handle a wide range of use cases.
     */
    data class MediaSet(
        /** This is the ID provided by the [Provider] of this data */
        override val id: String,

        /** This is the Picker ID generated in Picker Backend */
        val pickerId: Long,
        /** Authority of the source [Provider]. */
        val authority: String,
        val displayName: String?,
        val icon: ParcelableGlideLoadable,
    ) : Group {

        override fun describeContents(): Int {
            return 0
        }

        /** Implemented for [Parcelable], and handles all the common attributes. */
        override fun writeToParcel(out: Parcel, flags: Int) {
            out.writeString(id)
            out.writeLong(pickerId)
            out.writeString(authority)
            out.writeString(displayName)
            out.writeParcelable(icon, /* flags */ 0)
        }

        companion object CREATOR : Parcelable.Creator<MediaSet> {

            override fun createFromParcel(parcel: Parcel): MediaSet {
                @Suppress("DEPRECATION") // For backward-compatibility
                return MediaSet(
                    id = parcel.readString() ?: "",
                    pickerId = parcel.readLong(),
                    authority = parcel.readString() ?: "",
                    displayName = parcel.readString(),
                    icon =
                        parcel.readParcelable(ParcelableGlideLoadable::class.java.classLoader)
                            ?: Icon(uri = Uri.parse(""), mediaSource = MediaSource.LOCAL),
                )
            }

            override fun newArray(size: Int): Array<MediaSet?> {
                return arrayOfNulls(size)
            }
        }
    }
}
