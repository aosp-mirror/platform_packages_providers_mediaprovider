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

package com.android.photopicker

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.PackageManager.PackageInfoFlags
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.core.Background
import com.android.photopicker.core.PhotopickerAppWithBottomSheet
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.IllegalIntentExtraException
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.PhotopickerEventLogger
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.events.dispatchReportPhotopickerApiInfoEvent
import com.android.photopicker.core.events.dispatchReportPhotopickerMediaItemStatusEvent
import com.android.photopicker.core.events.dispatchReportPhotopickerSessionInfoEvent
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.selection.GrantsAwareSelectionImpl
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.theme.PhotopickerTheme
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.canHandleGetContentIntentMimeTypes
import com.android.photopicker.features.preparemedia.PrepareMediaFeature
import com.android.photopicker.features.preparemedia.PrepareMediaResult
import com.android.photopicker.features.preparemedia.PrepareMediaResult.PrepareMediaFailed
import com.android.photopicker.features.preparemedia.PrepareMediaResult.PreparedMedia
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * This is the main entrypoint into the Android Photopicker.
 *
 * This class is responsible for bootstrapping the launched activity, session related dependencies,
 * and providing the compose ui entrypoint in [[PhotopickerApp]] with everything it needs.
 */
@AndroidEntryPoint(ComponentActivity::class)
class MainActivity : Hilt_MainActivity() {

    @Inject @ActivityRetainedScoped lateinit var configurationManager: ConfigurationManager
    @Inject @ActivityRetainedScoped lateinit var bannerManager: Lazy<BannerManager>
    @Inject @ActivityRetainedScoped lateinit var processOwnerUserHandle: UserHandle
    @Inject @ActivityRetainedScoped lateinit var selection: Lazy<Selection<Media>>
    @Inject @ActivityRetainedScoped lateinit var dataService: Lazy<DataService>
    // This needs to be injected lazily, to defer initialization until the action can be set
    // on the ConfigurationManager.
    @Inject @ActivityRetainedScoped lateinit var featureManager: Lazy<FeatureManager>
    @Inject @Background lateinit var background: CoroutineDispatcher
    @Inject lateinit var userMonitor: Lazy<UserMonitor>

    // Events requires the feature manager, so initialize this lazily until the action is set.
    @Inject lateinit var events: Lazy<Events>

    companion object {
        val TAG: String = "Photopicker"
    }

    /**
     * Keeps track of the result set for the calling activity that launched the photopicker for
     * logging purposes
     */
    private var activityResultSet = 0

    /**
     * Keeps track of whether or not the picker was closed by using the standard android back
     * gesture instead of the picker bottom sheet swipe down
     */
    private var isPickerClosedByBackGesture = false

    private lateinit var photopickerEventLogger: PhotopickerEventLogger

    /**
     * A flow used to trigger the preparer. When media is ready to be prepared it should be provided
     * to the preparer by emitting into this flow.
     *
     * The main activity should create a new [_prepareDeferred] before emitting, and then monitor
     * that deferred to obtain the result of the prepare operation that this flow will trigger.
     */
    private val prepareMedia: MutableSharedFlow<Set<Media>> = MutableSharedFlow()

    /**
     * A deferred which tracks the current state of any prepare operation requested by the main
     * activity.
     */
    private var _prepareDeferred: CompletableDeferred<PrepareMediaResult> = CompletableDeferred()

    /**
     * Public access to the deferred, behind a getter. (To ensure any access to this property always
     * obtains the latest value)
     */
    val prepareDeferred: CompletableDeferred<PrepareMediaResult>
        get() {
            return _prepareDeferred
        }

    /**
     * A top level flow that listens for disruptive data events from the [DataService]. This flow
     * will emit when the DataService detects that its data is inaccurate or stale and will be used
     * to force refresh the UI and navigate the user back to the start destination.
     */
    private val disruptiveDataNotification: Flow<Int> by lazy {
        dataService.get().disruptiveDataUpdateChannel.receiveAsFlow().runningFold(initial = 0) {
            prev,
            _ ->
            prev + 1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [ACTION_GET_CONTENT]: Check to see if Photopicker should handle this session, or if the
        // user should instead be referred to [com.android.documentsui]. This is necessary because
        // Photopicker has a higher priority for "image/*" and "video/*" mimetypes that DocumentsUi.
        // An unfortunate side effect is that a mimetype of "*/*" also matches Photopicker's
        // intent-filter, and in that case, the user is not in a pure media selection mode, so refer
        // the user to DocumentsUi to handle all file types.
        if (shouldRerouteGetContentRequest()) {
            referToDocumentsUi()
        }

        // Set a Black color scrim behind the status bar.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.Black.toArgb()))

        // Set the action before allowing FeatureManager to be initialized, so that it receives
        // the correct config with this activity's action.
        try {
            getIntent()?.let { configurationManager.setIntent(it) }
        } catch (exception: IllegalIntentExtraException) {
            // If the incoming intent contains intent extras that are not supported in the current
            // configuration, then cancel the activity and close.
            Log.e(TAG, "Unable to start Photopicker with illegal configuration", exception)
            setResult(RESULT_CANCELED)
            activityResultSet = RESULT_CANCELED
            finish()
        }

        // Add information about the caller to the configuration.
        setCallerInConfiguration()

        // Begin listening for events before starting the UI.
        listenForEvents()

        // Picker event logger starts listening for events dispatched throughout the app
        photopickerEventLogger = PhotopickerEventLogger(dataService)
        photopickerEventLogger.start(lifecycleScope, background, events.get())

        /*
         * In single select sessions, the activity needs to end after a media object is selected,
         * so register a listener to the selection so the activity can handle calling
         * [onMediaSelectionConfirmed] itself.
         *
         * For multi-select, the activity has to wait for onMediaSelectionConfirmed to be called
         * by the selection bar click handler, or for the [Event.MediaSelectionConfirmed], in
         * the event the user ends the session from the [PreviewFeature]
         */
        listenForSelectionIfSingleSelect()

        setContent {
            val photopickerConfiguration by
                configurationManager.configuration.collectAsStateWithLifecycle()
            // Provide values to the entire compose stack.
            CompositionLocalProvider(
                LocalFeatureManager provides featureManager.get(),
                LocalPhotopickerConfiguration provides photopickerConfiguration,
                LocalSelection provides selection.get(),
                LocalEvents provides events.get(),
            ) {
                PhotopickerTheme(config = photopickerConfiguration) {
                    PhotopickerAppWithBottomSheet(
                        onDismissRequest = ::finish,
                        onMediaSelectionConfirmed = {
                            lifecycleScope.launch {
                                // Move the work off the UI dispatcher.
                                withContext(background) { onMediaSelectionConfirmed() }
                            }
                        },
                        prepareMedia = prepareMedia,
                        obtainPreparerDeferred = { prepareDeferred },
                        disruptiveDataNotification,
                    )
                }
            }
        }
        // Check if the picker was closed by the back gesture instead of simply swiping it down
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    isPickerClosedByBackGesture = true
                }
            },
        )

        // Log the picker launch details
        reportPhotopickerApiInfo()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity OnResume")

        // Initialize / Refresh the banner state, it's possible that external state has changed if
        // the activity is returning from the background.
        lifecycleScope.launch {
            withContext(background) {
                // Always ensure providers before requesting a banner refresh, banners depend on
                // having accurate provider information to generate the correct banners.
                dataService.get().ensureProviders()
                bannerManager.get().refreshBanners()
            }
        }
    }

    /** Dispatches an event to log all details with which the photopicker launched */
    private fun reportPhotopickerApiInfo() {
        val intentAction =
            when (intent.action) {
                MediaStore.ACTION_PICK_IMAGES -> Telemetry.PickerIntentAction.ACTION_PICK_IMAGES
                Intent.ACTION_GET_CONTENT -> Telemetry.PickerIntentAction.ACTION_GET_CONTENT
                MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP ->
                    Telemetry.PickerIntentAction.ACTION_USER_SELECT
                else -> Telemetry.PickerIntentAction.UNSET_PICKER_INTENT_ACTION
            }

        dispatchReportPhotopickerApiInfoEvent(
            coroutineScope = lifecycleScope,
            lazyEvents = events,
            photopickerConfiguration = configurationManager.configuration.value,
            pickerIntentAction = intentAction,
        )
    }

    /**
     * A collector that starts when Photopicker is running in single-select mode. This collector
     * will trigger [onMediaSelectionConfirmed] when the first (and only) item is selected.
     */
    private fun listenForSelectionIfSingleSelect() {

        // Only set up a collector if the selection limit is 1, otherwise the [SelectionBarFeature]
        // will be enabled for the user to confirm the selection.
        if (configurationManager.configuration.value.selectionLimit == 1) {
            lifecycleScope.launch {
                selection.get().flow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                    if (it.size == 1) {
                        launch { onMediaSelectionConfirmed() }
                    }
                }
            }
        }
    }

    /** Setup an [Event] listener for the [MainActivity] to monitor the event bus. */
    private fun listenForEvents() {
        lifecycleScope.launch {
            events.get().flow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { event
                ->
                when (event) {
                    is Event.BrowseToDocumentsUi -> referToDocumentsUi()
                    else -> {}
                }
            }
        }
    }

    override fun finish() {
        reportSessionInfo()
        super.finish()
    }

    /** Dispatches an event to log all the final state details of the picker */
    private fun reportSessionInfo() {
        val pickerStatus =
            if (activityResultSet == RESULT_CANCELED) {
                Telemetry.PickerStatus.CANCELED
            } else {
                Telemetry.PickerStatus.CONFIRMED
            }
        val pickerCloseMethod =
            if (isPickerClosedByBackGesture) {
                Telemetry.PickerCloseMethod.BACK_BUTTON
            } else if (pickerStatus == Telemetry.PickerStatus.CONFIRMED) {
                Telemetry.PickerCloseMethod.SELECTION_CONFIRMED
            } else {
                Telemetry.PickerCloseMethod.SWIPE_DOWN
            }

        dispatchReportPhotopickerSessionInfoEvent(
            coroutineScope = lifecycleScope,
            lazyEvents = events,
            photopickerConfiguration = configurationManager.configuration.value,
            lazyDataService = dataService,
            lazyUserMonitor = userMonitor,
            lazyMediaSelection = selection,
            pickerStatus = pickerStatus,
            pickerCloseMethod = pickerCloseMethod,
        )
    }

    /**
     * Sets the caller related fields in [PhotopickerConfiguration] with the calling application's
     * information, if available. This should only be called once and will cause a configuration
     * update.
     */
    private fun setCallerInConfiguration() {

        val pm = getPackageManager()

        var callingPackage: String?
        var callingPackageUid: Int?

        when (getIntent()?.getAction()) {
            // For permission mode, the caller will always be the permission controller,
            // and the permission controller will pass the UID of the app.
            MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP -> {

                callingPackageUid = getIntent()?.extras?.getInt(Intent.EXTRA_UID)
                checkNotNull(callingPackageUid) {
                    "Photopicker cannot run in permission mode without Intent.EXTRA_UID set."
                }
                callingPackage =
                    callingPackageUid.let {
                        // In the case of multiple packages sharing a uid, use the first one.
                        pm.getPackagesForUid(it)?.first()
                    }
            }

            // Extract the caller from the activity class inputs
            else -> {
                callingPackage = getCallingPackage()
                callingPackageUid =
                    callingPackage?.let {
                        try {
                            if (SdkLevel.isAtLeastT()) {
                                // getPackageUid API is T+
                                pm.getPackageUid(it, PackageInfoFlags.of(0))
                            } else {
                                // Fallback for S or lower
                                pm.getPackageUid(it, /* flags= */ 0)
                            }
                        } catch (e: NameNotFoundException) {
                            null
                        }
                    }
            }
        }

        val callingPackageLabel: String? =
            callingPackage?.let {
                try {
                    if (SdkLevel.isAtLeastT()) {
                        // getApplicationInfo API is T+
                        pm.getApplicationLabel(
                                pm.getApplicationInfo(it, ApplicationInfoFlags.of(0))
                            )
                            .toString() // convert CharSequence to String
                    } else {
                        // Fallback for S or lower
                        pm.getApplicationLabel(pm.getApplicationInfo(it, /* flags= */ 0))
                            .toString() // convert CharSequence to String
                    }
                } catch (e: NameNotFoundException) {
                    null
                }
            }
        configurationManager.setCaller(
            callingPackage = callingPackage,
            callingPackageUid = callingPackageUid,
            callingPackageLabel = callingPackageLabel,
        )
    }

    /**
     * Entrypoint for confirming the set of selected media and preparing the media for the calling
     * application.
     *
     * This should be called when the user has confirmed their selection, and would like to exit
     * photopicker and grant access to the media to the calling application.
     *
     * This will result in access being issued to the calling app if the media can be successfully
     * prepared.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    suspend fun onMediaSelectionConfirmed() {

        val snapshot = selection.get().snapshot()
        var selection = snapshot
        // Determine if any prepare of the selected media needs to happen, and
        // await the result of the preparer before proceeding.
        if (featureManager.get().isFeatureEnabled(PrepareMediaFeature::class.java)) {

            // Create a new [CompletableDeferred] that represents the result of this
            // prepare operation
            _prepareDeferred = CompletableDeferred()
            prepareMedia.emit(snapshot)

            // Await a response from the deferred before proceeding.
            // This will suspend until the response is available.
            val prepareResult = _prepareDeferred.await()
            if (prepareResult is PreparedMedia) {
                selection = prepareResult.preparedMedia
            } else {
                if (prepareResult !is PrepareMediaFailed) {
                    Log.e(TAG, "Expected prepare result object was not a PrepareMediaFailed")
                }

                // The prepare failed, so the activity cannot be completed.
                return
            }
        }

        val deselectionSnapshot = this.selection.get().getDeselection().toHashSet()
        onMediaSelectionReady(selection, deselectionSnapshot)
    }

    /**
     * This will end the activity.
     *
     * This method should be called when the user has confirmed their selection of media and would
     * like to exit the Photopicker. All Media preparing should be completed before this method is
     * invoked. This method will then arrange for the correct data to be returned based on the
     * configuration Photopicker is running under.
     *
     * When this method is complete, the Photopicker session will end.
     *
     * @param selection The prepared media that is ready to be returned to the caller.
     * @see [setResultForApp] for modes where the Photopicker returns media directly to the caller
     * @see [issueGrantsForApp] for permission mode grant writing in MediaProvider
     */
    private suspend fun onMediaSelectionReady(selection: Set<Media>, deselection: Set<Media>) {

        val configuration = configurationManager.configuration.first()

        when (configuration.action) {
            MediaStore.ACTION_PICK_IMAGES,
            Intent.ACTION_GET_CONTENT ->
                setResultForApp(selection, canSelectMultiple = configuration.selectionLimit > 1)
            MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP -> {
                val uid =
                    getIntent().getExtras()?.getInt(Intent.EXTRA_UID)
                        // If the permission controller did not provide a uid, there is no way to
                        // continue.
                        ?: throw IllegalStateException(
                            "Expected a uid to provided by PermissionController."
                        )
                updateGrantsForApp(selection, deselection, uid)
            }
            else -> {}
        }

        finish()
    }

    /**
     * The selection must be returned to the calling app via [setResult] and [ClipData]. When the
     * [MainActivity] is ending, this is part of the sequence of events to close the picker and
     * provide the selected media uris to the caller.
     *
     * This work runs on the @Background [CoroutineDispatcher] to avoid any UI disruption.
     *
     * @param selection the prepared media that can be safely returned to the app.
     * @param canSelectMultiple whether photopicker is in multi-select mode.
     */
    private suspend fun setResultForApp(selection: Set<Media>, canSelectMultiple: Boolean) {

        if (selection.size < 1) return

        val resultData = Intent()

        val uris: MutableList<Uri> = selection.map { it.mediaUri }.toMutableList()

        if (!canSelectMultiple) {
            // For Single selection set the Uri on the intent directly.
            resultData.setData(uris.removeFirst())
        } else if (uris.isNotEmpty()) {
            // For multi-selection, returned data needs to be attached via [ClipData]
            val clipData =
                ClipData(
                    /* label= */ null,
                    /* mimeTypes= */ selection.map { it.mimeType }.distinct().toTypedArray(),
                    /* item= */ ClipData.Item(uris.removeFirst()),
                )

            // If there are any remaining items in the list, attach those as additional
            // [ClipData.Item]
            for (uri in uris) {
                clipData.addItem(ClipData.Item(uri))
            }
            resultData.setClipData(clipData)
        } else {
            // The selection is empty, and there is no data to return to the caller.
            setResult(RESULT_CANCELED)
            activityResultSet = RESULT_CANCELED
            return
        }

        resultData.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resultData.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        setResult(RESULT_OK, resultData)
        activityResultSet = RESULT_OK
        dispatchSelectedMediaItemsStatusEvent(selection)
    }

    /** Dispatches an Event to log details of all the picked media items */
    private fun dispatchSelectedMediaItemsStatusEvent(selection: Set<Media>) {
        val mediaStatus = Telemetry.MediaStatus.SELECTED

        for (mediaItem in selection) {
            dispatchReportPhotopickerMediaItemStatusEvent(
                coroutineScope = lifecycleScope,
                lazyEvents = events,
                photopickerConfiguration = configurationManager.configuration.value,
                mediaItem = mediaItem,
                mediaStatus = mediaStatus,
            )
        }
    }

    /**
     * When Photopicker is in permission mode, the PermissionController is the calling application,
     * and rather than returning a list of media uris to the caller, instead MediaGrants must be
     * generated for the app uid provided by the PermissionController. (Which in this context is the
     * app that has invoked the permission controller, and thus caused PermissionController to open
     * photopicker).
     *
     * In addition to this, the preGranted items that are now de-selected by the user, the app
     * should no longer hold MediaGrants for them. This method takes care of revoking these grants.
     *
     * This is part of the sequence of ending a Photopicker Session, and is done in place of
     * returning data to the caller.
     *
     * @param selection The prepared media that is ready to be returned to the caller.
     * @param deselection The media for which the read grants should be revoked.
     * @param uid The uid of the calling application to issue media grants for.
     */
    private suspend fun updateGrantsForApp(
        currentSelection: Set<Media>,
        currentDeSelection: Set<Media>,
        uid: Int,
    ) {

        val selection = selection.get()
        val deselectAllEnabled =
            if (selection is GrantsAwareSelectionImpl) {
                selection.isDeSelectAllEnabled
            } else {
                false
            }
        if (deselectAllEnabled) {
            // removing all grants for preGranted items for this package.
            MediaStore.revokeAllMediaReadForPackages(getApplicationContext(), uid)
        } else {
            // Removing grants for preGranted items that have now been de-selected by the user.
            val urisForItemsToBeRevoked = currentDeSelection.map { it.mediaUri }
            MediaStore.revokeMediaReadForPackages(
                getApplicationContext(),
                uid,
                urisForItemsToBeRevoked,
            )
        }
        // Adding grants for items selected by the user.
        val uris: List<Uri> = currentSelection.map { it.mediaUri }
        MediaStore.grantMediaReadForPackage(getApplicationContext(), uid, uris)

        // No need to send any data back to the PermissionController, just send an OK signal
        // back to indicate the MediaGrants are available.
        setResult(RESULT_OK)
        activityResultSet = RESULT_OK
    }

    /**
     * This will end the activity. Refer the current session to [com.android.documentsui]
     *
     * Note: Complete any pending logging or work before calling this method as this will end the
     * process immediately.
     */
    private fun referToDocumentsUi() {
        // The incoming intent is not changed in any way when redirecting to DocumentsUi.
        // The calling app launched [ACTION_GET_CONTENT] probably without knowing it would first
        // come to Photopicker, so if Photopicker isn't going to handle the intent, just pass it
        // along unmodified.
        @Suppress("UnsafeIntentLaunch") val intent = getIntent()
        intent?.apply {
            addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
            addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
            setComponent(getDocumentssUiComponentName())
        }
        startActivityAsUser(intent, processOwnerUserHandle)
        finish()
    }

    /**
     * Determines if this session should end and the user should be redirected to
     * [com.android.documentsUi]. The evaluates the incoming [Intent] to see if Photopicker is
     * running in [ACTION_GET_CONTENT], and if the mimetypes requested can be correctly handled by
     * Photopicker. If the activity is not running in [ACTION_GET_CONTENT] this will always return
     * false.
     *
     * A notable exception would be if Photopicker was started by DocumentsUi rather than the
     * original app, in which case this method will return [false].
     *
     * @return true if the activity is running [ACTION_GET_CONTENT] and Photopicker shouldn't handle
     *   the session.
     */
    private fun shouldRerouteGetContentRequest(): Boolean {
        val intent = getIntent()

        return when {
            Intent.ACTION_GET_CONTENT != intent.getAction() -> false

            // GET_CONTENT for all (media and non-media) files opens DocumentsUi, but it still shows
            // "Photo Picker app option. When the user clicks on "Photo Picker", the same intent
            // which includes filters to show non-media files as well is forwarded to PhotoPicker.
            // Make sure Photo Picker is opened when the intent is explicitly forwarded by
            // documentsUi
            isIntentReferredByDocumentsUi(getReferrer()) -> false

            // Ensure Photopicker can handle the specified MIME types.
            intent.canHandleGetContentIntentMimeTypes() -> false
            else -> true
        }
    }

    /**
     * Resolves a [ComponentName] for DocumentsUi via [Intent.ACTION_OPEN_DOCUMENT]
     *
     * ACTION_OPEN_DOCUMENT is used to find DocumentsUi's component due to DocumentsUi being the
     * default handler.
     *
     * @return the [ComponentName] for DocumentsUi's picker activity.
     */
    private fun getDocumentssUiComponentName(): ComponentName? {

        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                setType("*/*")
            }

        val componentName = intent.resolveActivity(getPackageManager())
        return componentName
    }

    /**
     * Determines if the referrer uri came from [com.android.documentsui]
     *
     * @return true if the referrer [Uri] is from DocumentsUi.
     */
    private fun isIntentReferredByDocumentsUi(referrer: Uri?): Boolean {
        return referrer?.getHost() == getDocumentssUiComponentName()?.getPackageName()
    }
}
