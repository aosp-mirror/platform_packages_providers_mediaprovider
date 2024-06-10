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

package com.android.photopicker.test.utils

import android.content.ContentProvider
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.test.mock.MockContentProvider

/**
 * A small wrapper around MockContentProvider.
 *
 * This will be used to wrap [ContentResolver] to intercept calls to it and re-route them to the
 * internal mock this class holds.
 *
 * All not overridden / unimplemented methods will throw [UnsupportedOperationException] by the
 * underlying class.
 */
class MockContentProviderWrapper(val provider: ContentProvider) : MockContentProvider() {

    companion object {
        val AUTHORITY = "MOCK_CONTENT_PROVIDER"
    }

    /** Pass calls to the wrapped provider. */
    override fun openTypedAssetFile(
        uri: Uri,
        mimetype: String,
        opts: Bundle?,
        cancellationSignal: CancellationSignal?
    ): AssetFileDescriptor? {
        return provider.openTypedAssetFile(uri, mimetype, opts, cancellationSignal)
    }

    /** Pass calls to the wrapped provider. */
    override fun openTypedAssetFile(
        uri: Uri,
        mimeType: String,
        opts: Bundle?,
    ): AssetFileDescriptor? {
        return provider.openTypedAssetFile(uri, mimeType, opts)
    }

    /** Pass calls to the wrapped provider. */
    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        return provider.call(authority, method, arg, extras)
    }
}
