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

package com.android.photopicker.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.tests.utils.mockito.capture
import com.android.photopicker.tests.utils.mockito.mockSystemService
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/** Unit tests for the [NetworkManager] */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkMonitorTest {
    lateinit var networkMonitor: NetworkMonitor

    @Mock lateinit var context: Context
    @Mock lateinit var mockNetwork: Network
    @Mock lateinit var mockConnectivityManager: ConnectivityManager
    @Captor lateinit var callback: ArgumentCaptor<ConnectivityManager.NetworkCallback>
    @Captor lateinit var networkRequest: ArgumentCaptor<NetworkRequest>

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mockSystemService(context, ConnectivityManager::class.java) { mockConnectivityManager }
        whenever(mockConnectivityManager.activeNetwork) { mockNetwork }
        whenever(mockConnectivityManager.getNetworkCapabilities(mockNetwork)) {
            NetworkCapabilities.Builder()
                .apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                .build()
        }
    }

    /** Ensures the initial [NetworkStatus] is emitted before any callbacks are received. */
    @Test
    fun testInitialNetworkIsAvailable() {
        runTest { // this: TestScope
            networkMonitor = NetworkMonitor(context, this.backgroundScope)
            launch {
                val reportedStatus = networkMonitor.networkStatus.first()
                assertThat(reportedStatus).isEqualTo(NetworkStatus.Available)
            }
        }
    }

    /** Ensures the [NetworkMonitor] correctly sets up a [ConnectivityManager.NetworkCallback] */
    @Test
    fun testRegistersNetworkCallback() {
        runTest {
            networkMonitor = NetworkMonitor(context, this.backgroundScope)
            launch {
                val reportedStatus = networkMonitor.networkStatus.first()
                assertThat(reportedStatus).isEqualTo(NetworkStatus.Available)
            }
            advanceTimeBy(100)
            verify(mockConnectivityManager)
                .registerNetworkCallback(capture(networkRequest), capture(callback))

            val request: NetworkRequest = networkRequest.getValue()
            val callback: ConnectivityManager.NetworkCallback = callback.getValue()

            assertThat(callback).isNotNull()
            assertThat(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isTrue()
        }
    }

    /**
     * Ensures the [ConnectivityManager.NetworkCallback] emits the correct [NetworkStatus] when
     * called.
     */
    @Test
    fun testCallbackEmitsNewNetworkStatus() {
        runTest {
            networkMonitor = NetworkMonitor(context, this.backgroundScope)
            val emissions = mutableListOf<NetworkStatus>()
            backgroundScope.launch { networkMonitor.networkStatus.toList(emissions) }
            advanceTimeBy(100)
            verify(mockConnectivityManager)
                .registerNetworkCallback(capture(networkRequest), capture(callback))

            val callback: ConnectivityManager.NetworkCallback = callback.getValue()

            assertThat(emissions.removeFirst()).isEqualTo(NetworkStatus.Available)

            callback.onUnavailable()
            advanceTimeBy(100)

            assertThat(emissions.removeFirst()).isEqualTo(NetworkStatus.Unavailable)

            callback.onAvailable(mockNetwork)
            advanceTimeBy(100)

            assertThat(emissions.removeFirst()).isEqualTo(NetworkStatus.Available)

            callback.onLost(mockNetwork)
            advanceTimeBy(100)

            assertThat(emissions.removeFirst()).isEqualTo(NetworkStatus.Unavailable)
        }
    }

    /** Ensures new subscribers to the flow only receive the latest [NetworkStatus] */
    @Test
    fun testNetworkStatusReplayedForNewSubscribers() {
        runTest {
            networkMonitor = NetworkMonitor(context, this.backgroundScope)
            val allEmissions = mutableListOf<NetworkStatus>()
            backgroundScope.launch { networkMonitor.networkStatus.toList(allEmissions) }
            advanceTimeBy(100)
            verify(mockConnectivityManager)
                .registerNetworkCallback(capture(networkRequest), capture(callback))

            val callback: ConnectivityManager.NetworkCallback = callback.getValue()

            callback.onUnavailable()
            advanceTimeBy(100)

            callback.onAvailable(mockNetwork)
            advanceTimeBy(100)

            callback.onLost(mockNetwork)
            advanceTimeBy(100)

            assertThat(allEmissions.size).isEqualTo(4)

            // Register a new collector, which should jump straight to the end of emissions.
            val emissions = mutableListOf<NetworkStatus>()
            backgroundScope.launch { networkMonitor.networkStatus.toList(emissions) }
            advanceTimeBy(100)

            assertThat(emissions.first()).isEqualTo(NetworkStatus.Unavailable)
            assertThat(emissions.size).isEqualTo(1)
        }
    }

    /** Ensures only new values are emitted from the NetworkStatus flow. */
    @Test
    fun testNetworkStatusIsDistinctUntilChanged() {
        runTest {
            networkMonitor = NetworkMonitor(context, this.backgroundScope)
            val emissions = mutableListOf<NetworkStatus>()
            backgroundScope.launch { networkMonitor.networkStatus.toList(emissions) }
            advanceTimeBy(100)
            verify(mockConnectivityManager)
                .registerNetworkCallback(capture(networkRequest), capture(callback))

            val callback: ConnectivityManager.NetworkCallback = callback.getValue()

            callback.onAvailable(mockNetwork)
            advanceTimeBy(100)

            callback.onAvailable(mockNetwork)
            advanceTimeBy(100)

            callback.onAvailable(mockNetwork)
            advanceTimeBy(100)

            assertThat(emissions.first()).isEqualTo(NetworkStatus.Available)
            assertThat(emissions.size).isEqualTo(1)
        }
    }
}
