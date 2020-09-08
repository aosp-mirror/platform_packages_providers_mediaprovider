/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.providers.media.util;

import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
public class CachedSupplierTest {
    @Test
    public void testSimple() {
        final AtomicInteger counter = new AtomicInteger();
        final Supplier<Integer> supplier = new Supplier<Integer>() {
            @Override
            public Integer get() {
                return counter.incrementAndGet();
            }
        };

        // Multiple calls should only return first value
        final CachedSupplier<Integer> cachedSupplier = new CachedSupplier<Integer>(supplier);
        assertEquals(1, (int) cachedSupplier.get());
        assertEquals(1, (int) cachedSupplier.get());
        assertEquals(1, (int) cachedSupplier.get());

        // And confirm we only constructed it once
        assertEquals(1, counter.get());
    }
}
