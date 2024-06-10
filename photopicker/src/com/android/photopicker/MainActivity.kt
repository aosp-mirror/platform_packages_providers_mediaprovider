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
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.android.photopicker.core.Background
import com.android.photopicker.core.PhotopickerAppWithBottomSheet
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.IllegalIntentExtraException
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.theme.PhotopickerTheme
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.cloudmedia.CloudMediaFeature
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
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
    @Inject @ActivityRetainedScoped lateinit var processOwnerUserHandle: UserHandle
    @Inject @ActivityRetainedScoped lateinit var selection: Lazy<Selection<Media>>
    // This needs to be injected lazily, to defer initialization until the action can be set
    // on the ConfigurationManager.
    @Inject @ActivityRetainedScoped lateinit var featureManager: Lazy<FeatureManager>
    @Inject @Background lateinit var background: CoroutineDispatcher

    // Events requires the feature manager, so initialize this lazily until the action is set.
    @Inject lateinit var events: Lazy<Events>

    companion object {
        val TAG: String = "Photopicker"
    }

    /**
     * A flow used to trigger the preloader. When media is ready to be preloaded it should be
     * provided to the preloader by emitting into this flow.
     *
     * The main activity should create a new [_preloadDeferred] before emitting, and then monitor
     * that deferred to obtain the result of the preload operation that this flow will trigger.
     */
    val preloadMedia: MutableSharedFlow<Set<Media>> = MutableSharedFlow()

    /**
     * A deferred which tracks the current state of any preload operation requested by the main
     * activity.
     */
    private var _preloadDeferred: CompletableDeferred<Boolean> = CompletableDeferred()

    /**
     * Public access to the deferred, behind a getter. (To ensure any access to this property always
     * obtains the latest value)
     */
    public val preloadDeferred: CompletableDeferred<Boolean>
        get() {
            return _preloadDeferred
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

        enableEdgeToEdge()

        // Set the action before allowing FeatureManager to be initialized, so that it receives
        // the correct config with this activity's action.
        try {
            getIntent()?.let { configurationManager.setIntent(it) }
        } catch (exception: IllegalIntentExtraException) {
            // If the incoming intent contains intent extras that are not supported in the current
            // configuration, then cancel the activity and close.
            Log.e(TAG, "Unable to start Photopicker with illegal configuration", exception)
            setResult(RESULT_CANCELED)
            finish()
        }

        // Begin listening for events before starting the UI.
        listenForEvents()

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
                PhotopickerTheme(intent = photopickerConfiguration.intent) {
                    PhotopickerAppWithBottomSheet(
                        onDismissRequest = ::finish,
                        onMediaSelectionConfirmed = {
                            lifecycleScope.launch {
                                // Move the work off the UI dispatcher.
                                withContext(background) { onMediaSelectionConfirmed() }
                            }
                        },
                        preloadMedia = preloadMedia,
                        obtainPreloaderDeferred = { preloadDeferred }
                    )
                }
            }
        }
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
                withContext(background) {
                    selection.get().flow.collect {
                        if (it.size == 1) {
                            onMediaSelectionConfirmed()
                        }
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

                    /**
                     * [MediaSelectionConfirmed] will be dispatched in response to the user
                     * confirming their selection of Media in the UI.
                     */
                    is Event.MediaSelectionConfirmed -> onMediaSelectionConfirmed()
                    else -> {}
                }
            }
        }
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
    private suspend fun onMediaSelectionConfirmed() {

        val snapshot = selection.get().snapshot()
        // Determine if any preload of the selected media needs to happen, and
        // await the result of the preloader before proceeding.
        if (featureManager.get().isFeatureEnabled(CloudMediaFeature::class.java)) {

            // Create a new [CompletableDeferred] that represents the result of this
            // preload operation
            _preloadDeferred = CompletableDeferred()
            preloadMedia.emit(snapshot)

            // Await a response from the deferred before proceeding.
            // This will suspend until the response is available.
            val preloadSuccessful = _preloadDeferred.await()

            // The preload failed, so the activity cannot be completed.
            if (!preloadSuccessful) {
                return
            }
        }

        onMediaSelectionReady(snapshot)
    }

    /**
     * This will end the activity.
     *
     * This method should be called when the user has confirmed their selection of media and would
     * like to exit the Photopicker. All Media preloading should be completed before this method is
     * invoked. This method will then arrange for the correct data to be returned based on the
     * configuration Photopicker is running under.
     *
     * When this method is complete, the Photopicker session will end.
     *
     * @param selection The prepared media that is ready to be returned to the caller.
     * @see [setResultForApp] for modes where the Photopicker returns media directly to the caller
     * @see [issueGrantsForApp] for permission mode grant writing in MediaProvider
     */
    private suspend fun onMediaSelectionReady(selection: Set<Media>) {

        val configuration = configurationManager.configuration.first()

        when (configuration.action) {
            MediaStore.ACTION_PICK_IMAGES,
            Intent.ACTION_GET_CONTENT ->
                setResultForApp(selection, canSelectMultiple = configuration.selectionLimit > 1)
            MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP -> {
                val uid =
                    configuration.intent?.getExtras()?.getInt(Intent.EXTRA_UID)
                        // If the permission controller did not provide a uid, there is no way to
                        // continue.
                        ?: throw IllegalStateException(
                            "Expected a uid to provided by PermissionController."
                        )
                issueGrantsForApp(selection, uid)
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
                    /* item= */ ClipData.Item(uris.removeFirst())
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
            return
        }

        resultData.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resultData.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        setResult(RESULT_OK, resultData)
    }

    /**
     * When Photopicker is in permission mode, the PermissionController is the calling application,
     * and rather than returning a list of media uris to the caller, instead MediaGrants must be
     * generated for the app uid provided by the PermissionController. (Which in this context is the
     * app that has invoked the permission controller, and thus caused PermissionController to open
     * photopicker).
     *
     * This is part of the sequence of ending a Photopicker Session, and is done in place of
     * returning data to the caller.
     *
     * @param selection The prepared media that is ready to be returned to the caller.
     * @param uid The uid of the calling application to issue media grants for.
     */
    private suspend fun issueGrantsForApp(selection: Set<Media>, uid: Int) {

        val uris: List<Uri> = selection.map { it.mediaUri }
        // TODO: b/328189932 Diff the initial selection and revoke grants as needed.
        MediaStore.grantMediaReadForPackage(getApplicationContext(), uid, uris)

        // No need to send any data back to the PermissionController, just send an OK signal
        // back to indicate the MediaGrants are available.
        setResult(RESULT_OK)
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
            canHandleIntentMimeTypes(intent) -> false
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

    /**
     * Determines if [MainActivity] is capable of handling the [Intent.EXTRA_MIME_TYPES] provided to
     * the activity in this Photopicker session.
     *
     * @return true if the list of mimetypes can be handled by Photopicker.
     */
    private fun canHandleIntentMimeTypes(intent: Intent): Boolean {

        if (!intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            // If the incoming type is */* then Photopicker can't handle this mimetype
            return isMediaMimeType(intent.getType())
        }

        val mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)

        mimeTypes?.let {

            // If the list of MimeTypes is empty, nothing was explicitly set, so assume that
            // non-media files should be displayed.
            if (mimeTypes.size == 0) return false

            // Ensure all mimetypes in the incoming filter list are supported
            for (mimeType in mimeTypes) {
                if (!isMediaMimeType(mimeType)) {
                    return false
                }
            }
        }
            // Should not be null at this point (the intent contains the extra key),
            // but better safe than sorry.
            ?: return false

        return true
    }

    /**
     * Determines if the mimeType is a media mimetype that Photopicker can support.
     *
     * @return Whether the mimetype is supported by Photopicker.
     */
    private fun isMediaMimeType(mimeType: String?): Boolean {
        return mimeType?.let { it.startsWith("image/") || it.startsWith("video/") } ?: false
    }
}
