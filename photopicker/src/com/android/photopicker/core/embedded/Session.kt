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
package com.android.photopicker.core.embedded

import android.content.Context
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.EmbeddedPhotopickerFeatureInfo
import android.provider.IEmbeddedPhotopickerClient
import android.provider.IEmbeddedPhotopickerSession
import android.util.Log
import android.view.SurfaceControlViewHost
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.RequiresApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.android.photopicker.core.EmbeddedServiceComponent
import com.android.photopicker.core.Main
import com.android.photopicker.core.PhotopickerApp
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.theme.PhotopickerTheme
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.requireSystemService
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/** Alias that describes a factory function that creates a Session. */
internal typealias SessionFactory =
    (
        packageName: String,
        uid: Int,
        hostToken: IBinder,
        displayId: Int,
        width: Int,
        height: Int,
        featureInfo: EmbeddedPhotopickerFeatureInfo,
        clientCallback: IEmbeddedPhotopickerClient,
    ) -> Session

/**
 * Session object for a single session/instance of the Embedded Photopicker.
 *
 * This class manages a single session of the embedded Photopicker and resolves all hilt
 * dependencies for the Photopicker views that run underneath it. It also holds the
 * [SurfaceControlViewHost] and ensures that its resources are released when the session is closed.
 *
 * Additionally, the Session drives the [EmbeddedLifecycle] from Session creation and signals from
 * the client app, such as notifyVisibilityChanged, and ultimately destroys the lifecycle when the
 * session is closed.
 *
 * @property context The service context which will be used for initializing Photopicker and the
 *   associated ComposeView.
 * @property component the [EmbeddedServiceComponent] which contains this session's individual hilt
 * @property clientPackageName The package name of the client application that is opening the
 *   embedded photopicker.
 * @property clientUid The uid of the client application that is opening the embedded photopicker.
 * @property hostToken Binder token from the client for the [SurfaceControlViewHost].
 * @property displayId the displayId to locate the display for the [SurfaceControlViewHost]. This
 *   must resolve to a corresponding display in [DisplayManager] or the Session will crash.
 * @property width the width in pixels of the embedded view
 * @property height the height in pixels of the embedded view
 * @property featureInfo The API featureInfo from the client to set in [photopickerConfiguration]
 * @property clientCallback The Binder IPC callback for the session to send signals to the client.
 *   dependencies.
 * @see [EmbeddedServiceModule] for dependency implementations
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
open class Session(
    context: Context,
    private val component: EmbeddedServiceComponent,
    private val clientPackageName: String,
    private val clientUid: Int,
    private val hostToken: IBinder,
    private val displayId: Int,
    private val width: Int,
    private val height: Int,
    private val featureInfo: EmbeddedPhotopickerFeatureInfo,
    private val clientCallback: IEmbeddedPhotopickerClient,
    // TODO(b/354929684): Replace AIDL implementations with wrapper classes.
) : IEmbeddedPhotopickerSession.Stub() {

    companion object {
        val TAG: String = "PhotopickerEmbeddedSession"
    }

    /**
     * Most dependencies are injected as [Dagger.Lazy] to avoid initializing classes in the wrong
     * order or before they are actually needed. This saves on initialization costs and ensures that
     * the sequence of initialization between [ConfigurationManager] -> [FeatureManager] flows in a
     * predictable manner.
     */
    @EntryPoint
    @InstallIn(EmbeddedServiceComponent::class)
    interface EmbeddedEntryPoint {

        fun bannerManager(): Lazy<BannerManager>

        fun configurationManager(): Lazy<ConfigurationManager>

        fun dataService(): Lazy<DataService>

        fun events(): Lazy<Events>

        fun featureManager(): Lazy<FeatureManager>

        fun lifecycle(): EmbeddedLifecycle

        @Main fun mainDispatcher(): CoroutineDispatcher

        @Main fun scope(): CoroutineScope

        fun selection(): Lazy<Selection<Media>>

        fun userMonitor(): Lazy<UserMonitor>
    }

    /** A set of Session specific dependencies that are only used by this session instance */
    private val _dependencies: EmbeddedEntryPoint =
        EntryPoints.get(component, EmbeddedEntryPoint::class.java)

    private val _embeddedViewLifecycle: EmbeddedLifecycle = _dependencies.lifecycle()
    private val _scope: CoroutineScope = _dependencies.scope()
    private val _main: CoroutineDispatcher = _dependencies.mainDispatcher()

    private val _host: SurfaceControlViewHost
    private val _view: ComposeView

    open val surfacePackage: SurfaceControlViewHost.SurfacePackage
        get() {
            return checkNotNull(_host.surfacePackage) { "SurfacePackage was null" }
        }

    /**
     * Whether the session is currently active and has active resources. Sessions cannot be
     * restarted once closed.
     */
    var isActive = true

    init {

        // Mark the [EmbeddedLifecycle] associated with the session as created when this class is
        // instantiated.
        runBlocking(_main) { _embeddedViewLifecycle.onCreate() }

        // After starting the Lifecycle, the next task is to initialize ConfigurationManager and
        // update the [PhotopickerConfiguration] with all the incoming parameters from the client
        // so that the configuration can be stable before the ComposeView & FeatureManager are
        // initialized.

        // Look up the client's package label for the Photopicker UI.
        val packageManager = context.getPackageManager()
        val clientPackageLabel =
            try {
                packageManager
                    .getApplicationLabel(
                        packageManager.getApplicationInfo(
                            clientPackageName,
                            ApplicationInfoFlags.of(0)
                        )
                    )
                    .toString() // convert CharSequence to String
            } catch (e: NameNotFoundException) {
                null
            }

        _dependencies
            .configurationManager()
            .get()
            .setCaller(
                callingPackage = clientPackageName,
                callingPackageUid = clientUid,
                callingPackageLabel = clientPackageLabel
            )

        // TODO(b/350965066): set featureInfo in the ConfigurationManager and hand any errors back
        // to the client

        // Configuration is now stable, so the view can be created.
        // NOTE: Do not update the configuration after this line, it will cause the UI to
        // re-initialize.
        Log.d(TAG, "EmbeddedConfiguration is stable, UI will now start.")
        _view = createPhotopickerComposeView(context)
        _host = createSurfaceControlViewHost(context, displayId, hostToken)
        runBlocking(_main) { _host.setView(_view, width, height) }
    }

    override fun close() {
        Log.d(TAG, "Session close was requested.")
        // Mark the [EmbeddedLifecycle] associated with the session as destroyed when this class is
        // closed. Block until the call is complete to ensure the lifecycle is marked as destroyed.
        runBlocking(_main) {
            _host.release()
            _embeddedViewLifecycle.onDestroy()
        }

        // This session is now closed, and can never be reactivated.
        isActive = false
    }

    /**
     * Creates the [SurfaceControlViewHost] which owns the [SurfacePackage] that will be used for
     * remote rendering the Photopicker's [ComposeView] inside the client app's [SurfaceView].
     *
     * SurfaceControlViewHost needs to be created on the Main thread, so this method will spawn a
     * coroutine on the @Main dispatcher and block until that coroutine has completed.
     *
     * @param context The service context
     * @param displayId the displayId to locate the display for the [SurfaceControlViewHost]. This
     *   must resolve to a corresponding display in [DisplayManager] or the Session will crash.
     * @param hostToken A [Binder] token from the client to pass to the [SurfaceControlViewHost]
     */
    private fun createSurfaceControlViewHost(
        context: Context,
        displayId: Int,
        hostToken: IBinder
    ): SurfaceControlViewHost {
        val displayManager: DisplayManager = context.requireSystemService()
        val display =
            checkNotNull(displayManager.getDisplay(displayId)) {
                "The displayId provided to openSession did not result in a valid display."
            }
        return runBlocking(_main) { SurfaceControlViewHost(context, display, hostToken) }
    }

    /**
     * Creates a ComposeView, and sets the internal content to the EmbeddedPhotopicker UI entrypoint
     * in the compose tree.
     *
     * NOTE: This method will start the UI immediately after view creation, so the
     * [PhotopickerConfiguration] should be stable before starting the UI.
     *
     * @param context The service context
     * @return A [ComposeView] that has the Photopicker compose UI running inside.
     */
    private fun createPhotopickerComposeView(context: Context): ComposeView {

        // Creates embedded photopicker view and wraps it in [ComposeView].
        // This view is then wrapped in SurfacePackage by the [Session] and sent to client.
        return runBlocking(_main) {
            val composeView =
                ComposeView(context).apply {
                    _dependencies.lifecycle().attachView(this)
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                    )
                    setContent {
                        val photopickerConfiguration by
                            _dependencies
                                .configurationManager()
                                .get()
                                .configuration
                                .collectAsStateWithLifecycle()

                        // Provide values to the entire compose stack.
                        CompositionLocalProvider(
                            LocalFeatureManager provides _dependencies.featureManager().get(),
                            LocalPhotopickerConfiguration provides photopickerConfiguration,
                            LocalSelection provides _dependencies.selection().get(),
                            LocalEvents provides _dependencies.events().get(),

                            // Embedded photopicker specific providers
                            LocalEmbeddedLifecycle provides _embeddedViewLifecycle,
                            LocalViewModelStoreOwner provides _embeddedViewLifecycle,
                            LocalOnBackPressedDispatcherOwner provides _embeddedViewLifecycle,
                        ) {
                            PhotopickerTheme(config = photopickerConfiguration) {
                                PhotopickerApp(_dependencies.bannerManager().get())
                            }
                        }
                    }
                }
            Log.d(TAG, "ComposeView is ready.")
            // Mark the [EmbeddedLifecycle] associated with the session as resumed when
            // creating [ComposeView] for embedded content.
            _embeddedViewLifecycle.onStart()
            _embeddedViewLifecycle.onResume()
            composeView
        }
    }

    override fun notifyVisibilityChanged(isVisible: Boolean) {
        Log.d(TAG, "Session visibility has changed: $isVisible")
        when (isVisible) {
            true -> runBlocking(_main) { _embeddedViewLifecycle.onResume() }
            false -> runBlocking(_main) { _embeddedViewLifecycle.onStop() }
        }
    }

    override fun notifyResized(width: Int, height: Int) {
        TODO("Not yet implemented")
    }

    override fun notifyConfigurationChanged(configuration: Configuration?) {
        TODO("Not yet implemented")
    }

    override fun notifyPhotopickerExpanded(isExpanded: Boolean) {
        TODO("Not yet implemented")
    }
}
