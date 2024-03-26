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

package com.android.photopicker.inject

import android.content.Context
import android.os.Parcel
import android.os.UserHandle
import com.android.photopicker.core.Background
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.DeviceConfigProxy
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.Media
import dagger.Module
import dagger.Provides
import dagger.hilt.migration.DisableInstallInCheck
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * A basic Hilt test module that resolves common injected dependencies. Tests can install extend and
 * install this module to customize dependencies on a per test suite basis and avoid a lot of DI
 * setup of classes that may not be used in the test cases.
 *
 * For the purposes of the test case, all of these values are provided as [@Singleton] to ensure
 * that each injector receives the same value.
 *
 * This module does not provide overrides for CoroutineScope bindings, as those should be provided
 * by the test class directly via [@BindValue] and [TestScope]
 *
 * Note: This module is not actually Installed into the Hilt graph. @DisableInstallInCheck prevents
 * this module from being installed, each test will extend & install this module independently.
 */
@Module
@DisableInstallInCheck
abstract class PhotopickerTestModule {

    @Singleton
    @Provides
    fun createConfigurationManager(
        @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        deviceConfigProxy: DeviceConfigProxy
    ): ConfigurationManager {
        return ConfigurationManager(
            scope,
            dispatcher,
            deviceConfigProxy,
        )
    }

    @Provides
    fun createDeviceConfigProxy(): DeviceConfigProxy {
        return TestDeviceConfigProxyImpl()
    }

    @Singleton
    @Provides
    fun createUserHandle(): UserHandle {
        val parcel1 = Parcel.obtain()
        parcel1.writeInt(0)
        parcel1.setDataPosition(0)
        return UserHandle(parcel1)
    }

    @Singleton
    @Provides
    fun createUserMonitor(
        context: Context,
        @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        userHandle: UserHandle,
    ): UserMonitor {
        return UserMonitor(context, scope, dispatcher, userHandle)
    }

    @Singleton
    @Provides
    fun createDataService(): DataService {
        return TestDataServiceImpl()
    }

    @Singleton
    @Provides
    fun createFeatureManager(
        @Background scope: CoroutineScope,
    ): FeatureManager {
        return FeatureManager(
            provideTestConfigurationFlow(scope = scope),
            scope,
        )
    }

    @Singleton
    @Provides
    fun createSelection(@Background scope: CoroutineScope): Selection<Media> {
        return Selection<Media>(scope = scope)
    }
}
