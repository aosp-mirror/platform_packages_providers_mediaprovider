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

package com.android.photopicker.tests.utils.mockito

import android.content.Context
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock

/**
 * Wrapper around Mockito's when method. "when" is a protected keyword in Kotlin, so this provides a
 * convenient wrapper to use that also includes a block for creating a .thenAnswer chained call.
 */
fun <Type> whenever(mock: Type, block: InvocationOnMock.() -> Type) =
    Mockito.`when`(mock).thenAnswer { block(it) }

/**
 * Returns ArgumentCaptor.capture() as nullable type to avoid [java.lang.IllegalStateException] when
 * null is returned.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 */
fun <Type> capture(argumentCaptor: ArgumentCaptor<Type>): Type = argumentCaptor.capture()

/**
 * Registers mock returns for the designated system service. This is a Mockito helper to help with
 * the [requireSystemService] extension for correctly mocking out services based on their type
 * signatures.
 *
 * @param context The (mock) context object to stub out service on.
 * @param classToMock The java class of the system service to be mocked.
 * @param block A block that returns the mocked service value.
 */
fun <Type> mockSystemService(
    context: Context,
    classToMock: Class<Type>,
    block: InvocationOnMock.() -> Type
) {
    whenever(context.getSystemServiceName(classToMock)) { classToMock.simpleName }
    whenever(context.getSystemService(classToMock.simpleName)) { block() }
}
