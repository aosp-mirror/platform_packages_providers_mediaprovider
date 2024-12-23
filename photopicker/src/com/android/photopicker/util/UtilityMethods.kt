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

package com.android.photopicker.util

/**
 * Custom hashing function to generate a stable hash code value for any object given the input
 * values.
 *
 * There is perhaps a couple of reasons for choosing 31. The main reason is that it is a prime
 * number and prime numbers have better distribution results in hashing algorithms, by other words
 * the hashing outputs have less collisions for different inputs.
 *
 * The second reason is because 31 has a nice property – its multiplication can be replaced by a
 * bitwise shift which is faster than the standard multiplication: 31 * i == (i << 5) - i
 *
 * Modern VMs (such as the Android runtime) will perform this optimization automatically.
 */
fun hashCodeOf(vararg values: Any?) =
    values.fold(0) { acc, value ->
        val hashCode =
            if (value != null && value is Array<*>) value.contentHashCode() else value.hashCode()
        (acc * 31) + hashCode
    }
