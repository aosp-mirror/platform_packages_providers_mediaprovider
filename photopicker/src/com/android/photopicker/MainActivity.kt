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
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        // In single select sessions, the activity needs to end after a media object is selected,
        // so register a listener to the selection so the activity can handle calling
        // [onMediaSelectionConfirmed] itself. For multi-select, the activity has to wait to receive
        // the [Event.MediaSelectionConfirmed] dispatch.
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
                PhotopickerTheme { PhotopickerAppWithBottomSheet(onDismissRequest = ::finish) }
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
                    is Event.MediaSelectionConfirmed -> {
                        lifecycleScope.launch {
                            // Move the work off the UI dispatcher.
                            withContext(background) { onMediaSelectionConfirmed() }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * This will end the activity.
     *
     * This method should be called when the user has confirmed their selection of media and would
     * like to exit the Photopicker. This method will then arrange for the correct data to be
     * returned based on the configuration Photopicker is running under.
     *
     * When this method is complete, the Photopicker session will end.
     *
     * See: [setResultForApp] for modes where the Photopicker returns media directly to the caller
     * See: [issueGrantsForApp] for permission mode grant writing in MediaProvider
     */
    private suspend fun onMediaSelectionConfirmed() {

        val configuration = configurationManager.configuration.first()
        when (configuration.action) {
            MediaStore.ACTION_PICK_IMAGES,
            Intent.ACTION_GET_CONTENT ->
                setResultForApp(canSelectMultiple = configuration.selectionLimit > 1)
            MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP -> {
                val uid =
                    configuration.intent?.getExtras()?.getInt(Intent.EXTRA_UID)
                    // If the permission controller did not provide a uid, there is no way to
                    // continue.
                    ?: throw IllegalStateException(
                            "Expected a uid to provided by PermissionController."
                        )
                issueGrantsForApp(uid)
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
     */
    private suspend fun setResultForApp(canSelectMultiple: Boolean) {

        val snapshot = selection.get().snapshot()
        if (snapshot.size < 1) return

        val resultData = Intent()

        val uris: MutableList<Uri> = snapshot.map { it.mediaUri }.toMutableList()

        if (!canSelectMultiple) {
            // For Single selection set the Uri on the intent directly.
            resultData.setData(uris.removeFirst())
        } else if (uris.isNotEmpty()) {
            // For multi-selection, returned data needs to be attached via [ClipData]
            val clipData =
                ClipData(
                    /* label= */ null,
                    /* mimeTypes= */ snapshot.map { it.mimeType }.distinct().toTypedArray(),
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
     */
    private suspend fun issueGrantsForApp(uid: Int) {

        val uris: List<Uri> = selection.get().snapshot().map { it.mediaUri }
        // TODO: b/328189932 Diff the initial selection and revoke grants as needed.
        MediaStore.grantMediaReadForPackage(getApplicationContext(), uid, uris)

        // No need to send any data back to the PermissionController, just send an OK signal
        // back to indicate the MediaGrants are available.
        setResult(RESULT_OK)
    }
}
