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

package com.android.photopicker.core

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineScope

/** Injection Module that provides access to objets bound to the [ViewModelScoped] lifecycle. */
@Module
@InstallIn(ViewModelComponent::class)
class ViewModelModule {

    /**
     * Provider for a [CoroutineScope] that can be injected into view models.
     *
     * For production, this should return [null] and ViewModels should inject this value and use the
     * [lifecycle.viewModelScope].
     *
     * For tests, this can be a [TestScope] to allow better control of testing coroutines initiated
     * by ViewModels.
     */
    @Provides
    @ViewModelScoped
    fun provideScopeOverride(): CoroutineScope? {
        return null
    }
}
