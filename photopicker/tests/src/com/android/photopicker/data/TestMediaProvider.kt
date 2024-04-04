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

package src.com.android.photopicker.data

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.test.mock.MockContentProvider
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider

/**
 * A test utility that provides implementation for some MediaProvider queries.
 *
 * This will be used to wrap [ContentResolver] to intercept calls to it and re-route them to the
 * internal mock this class holds.
 *
 * All not overridden / unimplemented operations will throw [UnsupportedOperationException].
 */
class TestMediaProvider(
    var providers: List<Provider> = listOf(
        Provider(
            authority = "test_authority",
            mediaSource = MediaSource.LOCAL,
            uid = 0
        )
    )
) : MockContentProvider() {

    override fun query (
        uri: Uri,
        projection: Array<String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?
    ): Cursor? {
        if (uri.lastPathSegment == "available_providers") {
            return getAvailableProviders()
        }
        throw UnsupportedOperationException("Could not recognize uri $uri")
    }

    /**
     * Returns a [Cursor] with the providers currently in the [providers] list.
     */
    private fun getAvailableProviders(): Cursor {
        val cursor = MatrixCursor(arrayOf(
            "authority",
            "media_source",
            "uid"
        ))
        providers.forEach {
            cursor.addRow(arrayOfStrings(it))
        }
        return cursor
    }

    /**
     * Converts a [Provider] object to an Array of Strings.
     */
    private fun arrayOfStrings(provider: Provider): Array<String> {
        return arrayOf(
            provider.authority,
            provider.mediaSource.name,
            provider.uid.toString()
        )
    }
}