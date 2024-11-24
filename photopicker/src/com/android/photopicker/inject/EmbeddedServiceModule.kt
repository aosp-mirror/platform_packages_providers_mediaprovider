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

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.banners.BannerManagerImpl
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.DeviceConfigProxy
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.database.DatabaseManagerImpl
import com.android.photopicker.core.embedded.EmbeddedLifecycle
import com.android.photopicker.core.embedded.EmbeddedViewModelFactory
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.selection.GrantsAwareSelectionImpl
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.selection.SelectionImpl
import com.android.photopicker.core.selection.SelectionStrategy
import com.android.photopicker.core.selection.SelectionStrategy.Companion.determineSelectionStrategy
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.DataServiceImpl
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.NotificationService
import com.android.photopicker.data.NotificationServiceImpl
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.search.data.SearchDataService
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Injection Module that provides access to objects bound to a single [EmbeddedServiceComponent].
 *
 * The module is bound to a single instance of the embedded Photopicker, and first obtained in the
 * [Session].
 *
 * Note: Jobs that are launched in the [CoroutineScope] provided by this module will be
 * automatically cancelled when the [EmbeddedLifecycle] provided by this module ends.
 */
@Module
@InstallIn(EmbeddedServiceComponent::class)
class EmbeddedServiceModule {

    companion object {
        val TAG: String = "PhotopickerEmbeddedModule"
    }

    // Avoid initialization until it's actually needed.
    private lateinit var backgroundScope: CoroutineScope
    private lateinit var bannerManager: BannerManager
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var databaseManager: DatabaseManager
    private lateinit var dataService: DataService
    private lateinit var events: Events
    private lateinit var embeddedLifecycle: EmbeddedLifecycle
    private lateinit var embeddedViewModelFactory: EmbeddedViewModelFactory
    private lateinit var featureManager: FeatureManager
    private lateinit var mainScope: CoroutineScope
    private lateinit var notificationService: NotificationService
    private lateinit var selection: Selection<Media>
    private lateinit var userMonitor: UserMonitor

    @Provides
    @SessionScoped
    fun provideEmbeddedLifecycle(
        viewModelFactory: EmbeddedViewModelFactory,
        @Main dispatcher: CoroutineDispatcher,
    ): EmbeddedLifecycle {
        if (::embeddedLifecycle.isInitialized) {
            return embeddedLifecycle
        } else {
            Log.d(TAG, "Initializing custom embedded lifecycle.")

            // This must initialize on the MainThread so the Lifecycle state can be set.
            embeddedLifecycle = runBlocking(dispatcher) { EmbeddedLifecycle(viewModelFactory) }
            return embeddedLifecycle
        }
    }

    @Provides
    @SessionScoped
    fun provideViewModelFactory(
        @Background backgroundDispatcher: CoroutineDispatcher,
        featureManager: Lazy<FeatureManager>,
        configurationManager: Lazy<ConfigurationManager>,
        bannerManager: Lazy<BannerManager>,
        selection: Lazy<Selection<Media>>,
        userMonitor: Lazy<UserMonitor>,
        dataService: Lazy<DataService>,
        searchDataService: Lazy<SearchDataService>,
        events: Lazy<Events>,
    ): EmbeddedViewModelFactory {
        if (::embeddedViewModelFactory.isInitialized) {
            return embeddedViewModelFactory
        } else {
            Log.d(TAG, "Initializing embedded view model factory.")
            embeddedViewModelFactory =
                EmbeddedViewModelFactory(
                    backgroundDispatcher,
                    configurationManager,
                    bannerManager,
                    dataService,
                    searchDataService,
                    events,
                    featureManager,
                    selection,
                    userMonitor,
                )
            return embeddedViewModelFactory
        }
    }

    /** Provider for a @Background Dispatcher [CoroutineScope]. */
    @Provides
    @SessionScoped
    @Background
    fun provideBackgroundScope(
        @Background dispatcher: CoroutineDispatcher,
        @Main mainDispatcher: CoroutineDispatcher,
        embeddedLifecycle: EmbeddedLifecycle,
    ): CoroutineScope {
        if (::backgroundScope.isInitialized) {
            return backgroundScope
        } else {
            Log.d(TAG, "Initializing background scope.")
            backgroundScope = CoroutineScope(SupervisorJob() + dispatcher)

            // addObserver must be called from the main thread
            runBlocking(mainDispatcher) {
                embeddedLifecycle.lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_DESTROY -> {
                                Log.d(
                                    TAG,
                                    "Embedded lifecycle is ending, cancelling background scope.",
                                )
                                backgroundScope.cancel()
                            }
                            else -> {}
                        }
                    }
                )
            }
            return backgroundScope
        }
    }

    /** Provider for an implementation of [BannerManager]. */
    @Provides
    @SessionScoped
    fun provideBannerManager(
        @Background backgroundScope: CoroutineScope,
        @Background backgroundDispatcher: CoroutineDispatcher,
        configurationManager: ConfigurationManager,
        databaseManager: DatabaseManager,
        featureManager: FeatureManager,
        dataService: DataService,
        userMonitor: UserMonitor,
        processOwnerHandle: UserHandle,
    ): BannerManager {
        if (::bannerManager.isInitialized) {
            return bannerManager
        } else {
            Log.d(TAG, "BannerManager requested and initializing.")
            bannerManager =
                BannerManagerImpl(
                    backgroundScope,
                    backgroundDispatcher,
                    configurationManager,
                    databaseManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    processOwnerHandle,
                )
            return bannerManager
        }
    }

    /** Provider for the [ConfigurationManager]. */
    @Provides
    @SessionScoped
    fun provideConfigurationManager(
        @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        deviceConfigProxy: DeviceConfigProxy,
    ): ConfigurationManager {
        if (::configurationManager.isInitialized) {
            return configurationManager
        } else {
            Log.d(
                ConfigurationManager.TAG,
                "ConfigurationManager requested but not yet initialized." +
                    " Initializing ConfigurationManager.",
            )
            configurationManager =
                ConfigurationManager(
                    /* runtimeEnv= */ PhotopickerRuntimeEnv.EMBEDDED,
                    /* scope= */ scope,
                    /* dispatcher= */ dispatcher,
                    /* deviceConfigProxy= */ deviceConfigProxy,
                    /* sessionId */ generatePickerSessionId(),
                )
            return configurationManager
        }
    }

    /**
     * Provider method for [DataService]. This is lazily initialized only when requested to save on
     * initialization costs of this module.
     */
    @Provides
    @SessionScoped
    fun provideDataService(
        @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        userMonitor: UserMonitor,
        notificationService: NotificationService,
        configurationManager: ConfigurationManager,
        featureManager: FeatureManager,
        @ApplicationContext appContext: Context,
        events: Events,
        processOwnerHandle: UserHandle,
    ): DataService {

        if (!::dataService.isInitialized) {
            Log.d(
                DataService.TAG,
                "DataService requested but not yet initialized. Initializing DataService.",
            )
            dataService =
                DataServiceImpl(
                    userMonitor.userStatus,
                    scope,
                    dispatcher,
                    notificationService,
                    MediaProviderClient(),
                    configurationManager.configuration,
                    featureManager,
                    appContext,
                    events,
                    processOwnerHandle,
                )
        }
        return dataService
    }

    @Provides
    @SessionScoped
    fun provideDatabaseManager(@ApplicationContext context: Context): DatabaseManager {
        if (::databaseManager.isInitialized) {
            return databaseManager
        } else {
            Log.d(TAG, "Initializing DatabaseManager")
            databaseManager = DatabaseManagerImpl(context)
            return databaseManager
        }
    }

    /**
     * Provider method for [Events]. This is lazily initialized only when requested to save on
     * initialization costs of this module.
     */
    @Provides
    @SessionScoped
    fun provideEvents(
        @Background scope: CoroutineScope,
        featureManager: FeatureManager,
        configurationManager: ConfigurationManager,
    ): Events {
        if (::events.isInitialized) {
            return events
        } else {
            Log.d(Events.TAG, "Events requested but not yet initialized. Initializing Events.")
            events = Events(scope, configurationManager.configuration, featureManager)
            return events
        }
    }

    @Provides
    @SessionScoped
    fun provideFeatureManager(
        @SessionScoped @Background scope: CoroutineScope,
        @SessionScoped configurationManager: ConfigurationManager,
    ): FeatureManager {

        if (::featureManager.isInitialized) {
            return featureManager
        } else {
            Log.d(
                FeatureManager.TAG,
                "FeatureManager requested but not yet initialized. Initializing FeatureManager.",
            )
            featureManager =
                // Do not pass a set of FeatureRegistrations here to use the standard set of
                // enabled features.
                FeatureManager(configurationManager.configuration, scope)
            return featureManager
        }
    }

    /** Provider for a @Main Dispatcher [CoroutineScope]. */
    @Provides
    @SessionScoped
    @Main
    fun provideMainScope(
        @Main dispatcher: CoroutineDispatcher,
        embeddedLifecycle: EmbeddedLifecycle,
    ): CoroutineScope {

        if (::mainScope.isInitialized) {
            return mainScope
        } else {
            Log.d(TAG, "Initializing main scope.")
            mainScope = CoroutineScope(SupervisorJob() + dispatcher)

            // addObserver must be called from the main thread
            runBlocking(dispatcher) {
                embeddedLifecycle.lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_DESTROY -> {
                                Log.d(TAG, "Embedded lifecycle is ending, cancelling main scope.")
                                mainScope.cancel()
                            }
                            else -> {}
                        }
                    }
                )
            }
            return mainScope
        }
    }

    @Provides
    @SessionScoped
    fun provideNotificationService(): NotificationService {

        if (!::notificationService.isInitialized) {
            Log.d(
                NotificationService.TAG,
                "NotificationService requested but not yet initialized. " +
                    "Initializing NotificationService.",
            )
            notificationService = NotificationServiceImpl()
        }
        return notificationService
    }

    @Provides
    @SessionScoped
    fun provideSelection(
        @Background scope: CoroutineScope,
        configurationManager: ConfigurationManager,
        dataService: DataService,
    ): Selection<Media> {

        if (::selection.isInitialized) {
            return selection
        } else {
            Log.d(TAG, "Initializing selection.")
            selection =
                when (determineSelectionStrategy(configurationManager.configuration.value)) {
                    SelectionStrategy.GRANTS_AWARE_SELECTION ->
                        GrantsAwareSelectionImpl(
                            scope = scope,
                            configuration = configurationManager.configuration,
                            preGrantedItemsCount = dataService.preGrantedMediaCount,
                        )
                    SelectionStrategy.DEFAULT ->
                        SelectionImpl(
                            scope = scope,
                            configuration = configurationManager.configuration,
                            preSelectedMedia = dataService.preSelectionMediaData,
                        )
                }
            return selection
        }
    }

    /** Provides the UserHandle of the current process owner. */
    @Provides
    @SessionScoped
    fun provideUserHandle(): UserHandle {
        return Process.myUserHandle()
    }

    /** Provider for the [UserMonitor]. This is lazily initialized only when requested. */
    @Provides
    @SessionScoped
    fun provideUserMonitor(
        @ApplicationContext context: Context,
        configurationManager: ConfigurationManager,
        @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        handle: UserHandle,
    ): UserMonitor {
        if (::userMonitor.isInitialized) {
            return userMonitor
        } else {
            Log.d(
                UserMonitor.TAG,
                "UserMonitor requested but not yet initialized. Initializing UserMonitor.",
            )
            userMonitor =
                UserMonitor(context, configurationManager.configuration, scope, dispatcher, handle)
            return userMonitor
        }
    }
}
