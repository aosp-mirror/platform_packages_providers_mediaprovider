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
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injection Module that provides Dispatchers for injection when scoping Coroutines. This module is
 * installed in the [SingletonComponent] and is Application wide, not Activity wide.
 */
@Module
@InstallIn(SingletonComponent::class)
object ConcurrencyModule {

    /** Injectable dispatcher to dispatch jobs that will run immediately on the main thread. */
    @Provides @Main fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    /** Injectable dispatcher to dispatch jobs that will run on [Dispatchers.IO]. */
    @Provides @Background fun provideBackgroundDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
