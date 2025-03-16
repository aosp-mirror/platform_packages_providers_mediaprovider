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

package com.android.photopicker.features.preparemedia

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.photopicker.core.Background
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.features.preparemedia.PrepareMediaResult.PrepareMediaFailed
import com.android.photopicker.features.preparemedia.PrepareMediaResult.PreparedMedia
import com.android.photopicker.features.preparemedia.Transcoder.Companion.toTranscodedUri
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Enumeration for the LoadStatus of a given preloaded item. */
private enum class LoadStatus {
    COMPLETED,
    FAILED,
    QUEUED,
}

/** Enumeration for the TranscodeStatus of a given media item. */
private enum class TranscodeStatus {
    NOT_APPLIED,
    SUCCEED,
    FAILED,
    QUEUED,
}

/** Data class for the prepare status of a given media item. */
private data class PrepareStatus(val loadStatus: LoadStatus, val transcodeStatus: TranscodeStatus) {
    val isPreloadCompleted = loadStatus == LoadStatus.COMPLETED
    val isTranscodeCompleted =
        transcodeStatus == TranscodeStatus.SUCCEED || transcodeStatus == TranscodeStatus.NOT_APPLIED

    val isCompleted = isPreloadCompleted && isTranscodeCompleted
    val isFailed = loadStatus == LoadStatus.FAILED || transcodeStatus == TranscodeStatus.FAILED
}

/** Data objects which contain all the UI data to render the various Preparer dialogs. */
sealed interface PreparerDialogData {

    /**
     * The preparing dialog data.
     *
     * @param total Total of items to be prepared
     * @param completed Number of items currently completed
     */
    data class PreparingDialogData(val total: Int, val completed: Int = 0) : PreparerDialogData

    /** Empty object for telling the UI to show a generic error dialog */
    object PreparingErrorDialog : PreparerDialogData
}

/**
 * The view model for the [MediaPreparer].
 *
 * This is the class responsible for preparing files before providing URI, e.g. request remote
 * providers to prepare remote media for local apps or pre-transcode video for incompatible apps.
 * The main preparing operation should only be triggered by the main activity, by emitting a set of
 * media to prepare into the flow provided to the MediaPreparer compose UI via [LocationParams].
 *
 * Additionally, this method exposes the required state data for the UI to draw the correct dialog
 * overlays as preparing is initiated, is progressing, and resolves with either a failure or a
 * success.
 *
 * This class should not be injected anywhere other than the MediaPreparer's context to attempt to
 * monitor the state of the ongoing prepare.
 *
 * When the prepare is complete, the [CompletableDeferred] that is passed in the [LocationParams]
 * will be marked completed, A TRUE value indicates success, and a FALSE value indicates a failure.
 */
@HiltViewModel
class MediaPreparerViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val selection: Selection<Media>,
    private val userMonitor: UserMonitor,
    private val configurationManager: ConfigurationManager,
    private val events: Events,
) : ViewModel() {

    companion object {
        private const val EXTRA_URI = "uri"
        private const val PICKER_TRANSCODE_CALL = "picker_transcode"
        @VisibleForTesting const val PICKER_TRANSCODE_RESULT = "picker_transcode_result"

        // Ensure only 2 downloads are occurring in parallel.
        val MAX_CONCURRENT_LOADS = 2
    }

    /* Parent job that owns the overall preparer operation & monitor */
    private var job: Job? = null

    /*
     * A heartbeat flow to drive the prepare monitor job.
     * Replay = 1 and DROP_OLDEST due to the fact the heartbeat doesn't contain any useful
     * data, so as long as something is in the buffer to be collected, there's no need
     * for duplicate emissions.
     */
    private val heartbeat: MutableSharedFlow<Unit> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Protect [preparingItems] with a Mutex since multiple coroutines are reading/writing it.
    private val mutex = Mutex()

    // A map that tracks the p [PrepareStatus] of media items.
    // NOTE: This should always be accessed after acquiring the [Mutex] to ensure data
    // accuracy during concurrency.
    @GuardedBy("mutex") private val preparingItems = mutableMapOf<Media, PrepareStatus>()

    /*
     * A flow to drive the media transcoding job.
     * Replay = selectionLimit so that any place that emits to this flow, won't suspend.
     * (Each media is expected to be emitted only once, so each media should only be emitted when
     *  it is ready for transcoding)
     */
    private val itemsToTranscode: MutableSharedFlow<Media> =
        MutableSharedFlow(
            replay = configurationManager.configuration.value.selectionLimit,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    // Transcoder that help media transcode process.
    @VisibleForTesting var transcoder: Transcoder = TranscoderImpl()

    // Check if a scope override was injected before using the default [viewModelScope]
    private val scope: CoroutineScope =
        if (scopeOverride == null) {
            this.viewModelScope
        } else {
            scopeOverride
        }

    /* Flow for monitoring the activeContentResolver:
     *   - map to get rid of other [UserStatus] fields this does not care about
     *   - distinctUntilChanged to only emit when the resolver actually changes, since
     *     UserStatus might be updated if other profiles turn on and off
     */
    private val _contentResolver =
        userMonitor.userStatus.map { it.activeContentResolver }.distinctUntilChanged()

    /** Flow that can push new data into the preparer's dialogs. */
    private val _dialogData = MutableStateFlow<PreparerDialogData?>(null)

    /** Public flow for the compose ui to collect. */
    val dialogData: StateFlow<PreparerDialogData?> =
        _dialogData.stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            initialValue = _dialogData.value,
        )

    init {

        // If the active user's resolver changes, cancel any pending prepare work.
        scope.launch {
            _contentResolver.collect {
                // Action is only required if there's currently a job running.
                job?.let {
                    Log.d(PrepareMediaFeature.TAG, "User was changed, abandoning prepares")
                    it.cancel()
                    hideAllDialogs()
                }
            }
        }
    }

    /**
     * Entrypoint of the selected media prepare operation.
     *
     * This is triggered when the prepareMedia flow from compose receives a new Set<Media> to
     * prepare.
     *
     * Once the new set of media is received from its source, the compose UI will call startPrepare
     * to begin the prepare of the set.
     *
     * This operation will enqueue work to prepare any media files that are present in the current
     * selection to ensure they are downloaded by the remote provider or transcoded to a compatible
     * format. This has the benefit of ensuring that the files can be immediately opened by the App
     * that started Photopicker without having to deal with awaiting any remote procedures to bring
     * the remote file down to the device.
     *
     * This method will run a parent [CoroutineScope] (see [job] in this class), which will
     * subsequently schedule child jobs for each media item in the selection. For remote media
     * preloading, the [Background] [CoroutineDispatcher] is used for this operation, however the
     * parallel execution is limited to [MAX_CONCURRENT_LOADS] to avoid over-stressing the remote
     * providers and saturating the available network bandwidth. For media transcoding, the
     * execution is running sequentially, and if a media item requires remote preloading before
     * transcoding, subsequent items are processed first to maximize efficiency.
     *
     * @param selection The set of media to prepare.
     * @param deferred A [CompletableDeferred] that can be used to signal when the prepare operation
     *   is complete. TRUE represents success, FALSE represents failure.
     * @param context The current context.
     * @see [LocationParams.WithMediaPreparer] for the data that is passed to the UI to attach the
     *   preparer.
     */
    suspend fun startPrepare(
        selection: Set<Media>,
        deferred: CompletableDeferred<PrepareMediaResult>,
        context: Context,
    ) {
        initialMediaPreparation(selection)

        val countPrepareRequired: Int =
            mutex.withLock { preparingItems.filter { (_, status) -> !status.isCompleted }.size }

        // End early if there are not any items need to be prepared.
        if (countPrepareRequired == 0) {
            Log.i(PrepareMediaFeature.TAG, "Prepare not required, no remote or incompatible items.")
            deferred.complete(PreparedMedia(preparedMedia = getPreparedMedia()))
            return
        }

        Log.i(
            PrepareMediaFeature.TAG,
            "SelectionMediaBeginPrepare operation was requested. " +
                "Total items to prepare: $countPrepareRequired",
        )

        // Update the UI so the Preparing dialog can be displayed with the initial preparing data.
        _dialogData.update {
            PreparerDialogData.PreparingDialogData(
                total = selection.size,
                completed = (selection.size - countPrepareRequired),
            )
        }

        // All preparing work must be a child of this job, a reference of the job is saved
        // so that if the User requests cancellation the child jobs receive the cancellation as
        // well.
        job =
            scope.launch(backgroundDispatcher) {
                // Enqueue a job to monitor the ongoing operation. This job is crucially also a
                // child of the main preloading job, so it will be canceled anytime loading is
                // canceled.
                launch { monitorPrepareOperation(deferred) }

                // Start a parallelism constrained child job to actually handle the loads to
                // enforce that the device bandwidth doesn't become over saturated by trying
                // to load too many files at once.
                launch(
                    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                    backgroundDispatcher.limitedParallelism(MAX_CONCURRENT_LOADS)
                ) {
                    // This is the main preloading job coroutine, enqueue other work here, but
                    // don't run any heavy / blocking work, as it will prevent the loading
                    // from starting.
                    val remoteItems =
                        mutex.withLock {
                            preparingItems.entries
                                .toList()
                                .filter { it.value.loadStatus == LoadStatus.QUEUED }
                                .map { it.key }
                        }
                    for (item in remoteItems) {
                        launch { preloadMediaItem(item, deferred) }
                    }
                }

                // Start a child job to wait and send the readied media items to transcode.
                launch {
                    itemsToTranscode.collect { item ->
                        try {
                            // The transcoding process is only started when an item's transcoding
                            // is not completed (currently only video can be transcoded) and
                            // does not require loading.
                            val prepareStatus = mutex.withLock { preparingItems.getValue(item) }
                            if (
                                !prepareStatus.isTranscodeCompleted &&
                                    prepareStatus.isPreloadCompleted
                            ) {
                                transcodeMediaItem(item, deferred, context)
                            }
                        } catch (e: NoSuchElementException) {
                            // Should not go here.
                            Log.e(
                                PrepareMediaFeature.TAG,
                                "Expected media object was not in the status map",
                                e,
                            )
                        }
                    }
                }
            }
    }

    /**
     * Initializes required objects for media prepare processing.
     *
     * @param selection The set of media to be prepared.
     */
    private suspend fun initialMediaPreparation(selection: Set<Media>) {
        val config = configurationManager.configuration.value
        val mediaCapabilities = config.callingPackageMediaCapabilities
        val isTranscodingEnabled = config.flags.PICKER_TRANSCODING_ENABLED

        // Initial preparation states.
        mutex.withLock {
            // Begin by clearing any prior state.
            preparingItems.clear()

            for (item in selection) {
                // Check if media need to be preloaded.
                val loadStatus =
                    if (item.mediaSource == MediaSource.REMOTE) {
                        LoadStatus.QUEUED
                    } else {
                        LoadStatus.COMPLETED
                    }

                // Check if media need to be transcoded.
                val transcodeStatus =
                    if (isTranscodingEnabled && mediaCapabilities != null && item is Media.Video) {
                        TranscodeStatus.QUEUED
                    } else {
                        TranscodeStatus.NOT_APPLIED
                    }

                val prepareStatus = PrepareStatus(loadStatus, transcodeStatus)
                preparingItems.put(item, prepareStatus)

                // Queue readied media items for transcoding. Items that have not been preloaded
                // will be queued after loading.
                if (!prepareStatus.isTranscodeCompleted && prepareStatus.isPreloadCompleted) {
                    itemsToTranscode.emit(item)
                }
            }
        }
    }

    /**
     * Entrypoint for preloading a single [Media] item.
     *
     * This begins preparing the file by requesting the file from the current user's
     * [ContentResolver], and updates the dialog data and remote items statuses when a load is
     * successful.
     *
     * If a file cannot be opened or the ContentResolver throws a [FileNotFoundException], the item
     * is marked as failed.
     *
     * @param item The item to load from the [ContentResolver].
     * @param deferred The overall deferred for the preload operation which is used to see if the
     *   preload has been canceled already)
     */
    private suspend fun preloadMediaItem(
        item: Media,
        deferred: CompletableDeferred<PrepareMediaResult>,
    ) {
        Log.v(PrepareMediaFeature.TAG, "Beginning preload of: $item")
        try {
            if (!deferred.isCompleted) {
                userMonitor.userStatus.value.activeContentResolver
                    .openAssetFileDescriptor(item.mediaUri, "r")
                    ?.close()

                // Mark the item as complete in the result status.
                updatePrepareStatus(item, loadStatus = LoadStatus.COMPLETED)
                Log.v(PrepareMediaFeature.TAG, "Preload successful: $item")

                // Pass the loaded item for transcoding. The transcoding status does not to check
                // here, since it will be examined before passing to "transcodeMediaItem" to start
                // the transcoding process.
                itemsToTranscode.emit(item)

                // Emit a new monitor heartbeat so the prepare can continue or finish.
                heartbeat.emit(Unit)
            }
        } catch (e: FileNotFoundException) {
            Log.e(PrepareMediaFeature.TAG, "Error while preloading $item", e)

            // Only need to take action if the deferred is already not marked as completed,
            // another prepare job may have already failed.
            if (!deferred.isCompleted) {
                Log.d(
                    PrepareMediaFeature.TAG,
                    "Failure detected, cancelling the rest of the preload operation.",
                )
                // Log failure of media items preloading
                scope.launch {
                    val configuration = configurationManager.configuration.value
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.CORE.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.PICKER_PRELOADING_FAILED,
                        )
                    )
                }
                // Mark the item as failed in the result status.
                updatePrepareStatus(item, loadStatus = LoadStatus.FAILED)
                // Emit a new heartbeat so the monitor will react to this failure.
                heartbeat.emit(Unit)
            }
        }
    }

    /**
     * Entrypoint for transcoding a single [Media.Video] item.
     *
     * This begins transcoding the file by triggering the call method of the current user's
     * [ContentResolver], and updates the dialog data and incompatible items statuses when a
     * transcode is successful.
     *
     * If a file cannot be opened or the ContentResolver throws a [FileNotFoundException], the item
     * is marked as failed.
     *
     * @param item The item to transcode from the [ContentResolver].
     * @param deferred The overall deferred for the transcode operation which is used to see if the
     *   transcode has been canceled already).
     * @param context The current context.
     */
    private suspend fun transcodeMediaItem(
        item: Media,
        deferred: CompletableDeferred<PrepareMediaResult>,
        context: Context,
    ) {
        Log.v(PrepareMediaFeature.TAG, "Beginning transcode of: $item")
        if (!deferred.isCompleted) {
            if (item is Media.Video) {
                val contentResolver = userMonitor.userStatus.value.activeContentResolver
                val mediaCapabilities =
                    configurationManager.configuration.value.callingPackageMediaCapabilities

                // Trigger transcoding.
                val transcodeStatus =
                    if (transcoder.isTranscodeRequired(context, mediaCapabilities, item)) {
                        val uri = item.mediaUri
                        val resultBundle =
                            contentResolver.call(
                                uri,
                                PICKER_TRANSCODE_CALL,
                                null,
                                Bundle().apply { putParcelable(EXTRA_URI, uri) },
                            )

                        if (resultBundle?.getBoolean(PICKER_TRANSCODE_RESULT, false) == true) {
                            Log.v(PrepareMediaFeature.TAG, "Transcode successful: $item")
                            TranscodeStatus.SUCCEED
                        } else {
                            Log.w(PrepareMediaFeature.TAG, "Not able to transcode: $item")
                            TranscodeStatus.NOT_APPLIED
                        }
                    } else {
                        Log.v(PrepareMediaFeature.TAG, "No need to transcode: $item")
                        TranscodeStatus.NOT_APPLIED
                    }

                // Mark the item as complete in the result status.
                updatePrepareStatus(item, transcodeStatus = transcodeStatus)
            } else {
                // Should not go here. Currently, only video can be transcoded.
                Log.e(PrepareMediaFeature.TAG, "Expected media object was not a video")

                // Mark the item as failed in the result status.
                updatePrepareStatus(item, transcodeStatus = TranscodeStatus.FAILED)
            }

            // Emit a new monitor heartbeat so the prepare can continue or finish.
            heartbeat.emit(Unit)
        }
    }

    /**
     * Updates the preparation status for the given media.
     *
     * @param item The media whose status to update.
     * @param loadStatus The new load status. If null, the existing status is preserved.
     * @param transcodeStatus The new transcode status. If null, the existing status is preserved.
     */
    private suspend fun updatePrepareStatus(
        item: Media,
        loadStatus: LoadStatus? = null,
        transcodeStatus: TranscodeStatus? = null,
    ) {
        val oldStatus: PrepareStatus
        val newStatus: PrepareStatus

        mutex.withLock {
            oldStatus =
                try {
                    preparingItems.getValue(item)
                } catch (e: NoSuchElementException) {
                    Log.e(
                        PrepareMediaFeature.TAG,
                        "Failed to update preparing status, item not in the map",
                        e,
                    )
                    return
                }
            newStatus =
                oldStatus.copy(
                    loadStatus = loadStatus ?: oldStatus.loadStatus,
                    transcodeStatus = transcodeStatus ?: oldStatus.transcodeStatus,
                )
            preparingItems.put(item, newStatus)
        }

        if (!oldStatus.isCompleted && newStatus.isCompleted) {
            increaseCompletionOnUI()
        }
    }

    /** Update the [PreparerDialogData] flow an increment the completed operations by one on UI. */
    private fun increaseCompletionOnUI() {
        _dialogData.update {
            when (it) {
                is PreparerDialogData.PreparingDialogData -> it.copy(completed = it.completed + 1)
                else -> it
            }
        }
    }

    /**
     * Suspended function that monitors media preparing and takes an action when [PrepareStatus] of
     * all items are completed or a failure is found in [preparingItems].
     *
     * When all preparingItems are completed -> mark the [CompletableDeferred] that represents this
     * prepare operation as completed(TRUE) to signal the prepare was successful.
     *
     * When one of the preparingItems is failed any pending prepares are cancelled, and the parent
     * job is also canceled. The failed item(s) will be removed from the current selection, and the
     * deferred will be completed(FALSE) to signal the prepare has failed.
     *
     * This method will run a new check for every heartbeat, and does not observe the
     * [preparingItems] data structure directly. As such, it's important that any status changes in
     * the state of preparing trigger an update of heartbeat for the collector in this method to
     * execute.
     *
     * @param deferred the status of the overall prepare operation. TRUE signals a successful
     *   prepare, and FALSE a failure.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun monitorPrepareOperation(deferred: CompletableDeferred<PrepareMediaResult>) {

        heartbeat.collect {

            // Outcomes, another possibility is neither is met, and the prepare should continue
            // until the next result.
            var prepareFailed = false
            var prepareCompleted = false

            // Fetch the current results with the mutex, but don't hold the mutex longer than
            // needed.
            mutex.withLock {

                // The prepare is failed if any single item fails to prepare.
                prepareFailed = preparingItems.any { (_, status) -> status.isFailed }

                // The prepare is complete if all items are completed successfully.
                prepareCompleted = preparingItems.all { (_, status) -> status.isCompleted }
            }

            // Outcomes, if none of these branches are yet met, the prepare will continue, and this
            // block will run on the next known result.
            when {
                prepareFailed -> {
                    // Remove any failed items from the selection
                    selection.removeAll(
                        preparingItems.filter { (_, status) -> status.isFailed }.keys
                    )
                    // Now that a failure has been detected, update the [PreparerDialogData]
                    // so the UI will show the preparing error dialog.
                    _dialogData.update { PreparerDialogData.PreparingErrorDialog }

                    // Since something has failed, mark the overall prepare operation as failed.
                    deferred.complete(PrepareMediaFailed)
                }
                prepareCompleted -> {
                    // If all of the remote items have completed successfully and the videos have
                    // been transcoded to the compatible formats, the prepare operation is
                    // complete, deferred can be marked as complete(true) to instruct the
                    // application to send the selected Media to the caller.
                    Log.d(PrepareMediaFeature.TAG, "Prepare operation was successful.")
                    deferred.complete(PreparedMedia(preparedMedia = getPreparedMedia()))

                    // Dispatch UI event to mark the end of preparing of media items
                    scope.launch {
                        val configuration = configurationManager.configuration.value
                        events.dispatch(
                            Event.LogPhotopickerUIEvent(
                                FeatureToken.CORE.token,
                                configuration.sessionId,
                                configuration.callingPackageUid ?: -1,
                                Telemetry.UiEvent.PICKER_PRELOADING_FINISHED,
                            )
                        )
                    }
                }
            }

            // If the prepare has a result, clean up the active running job.
            if (prepareFailed || prepareCompleted) {
                job?.cancel()
                // Drop any pending heartbeats or transcoding items as the preparer job is being
                // shutdown.
                heartbeat.resetReplayCache()
                itemsToTranscode.resetReplayCache()
            }
        }
    }

    /**
     * Gets the set of media that have been prepared.
     *
     * Note that if the media is transcoded, its media URI will be updated to the transcoded URI .
     *
     * @return The set of media.
     */
    private suspend fun getPreparedMedia(): Set<Media> {
        return mutex.withLock {
            preparingItems
                .asSequence()
                .map { (media, status) ->
                    if (status.transcodeStatus == TranscodeStatus.SUCCEED) {
                        if (media is Media.Video) {
                            // Replace media uri with transcoded uri if the media is transcoded.
                            return@map media.copy(mediaUri = toTranscodedUri(media.mediaUri))
                        } else {
                            // Should not go here. Currently, only video can be transcoded.
                            Log.e(PrepareMediaFeature.TAG, "Expected media object was not a video")
                        }
                    }
                    media
                }
                .toSet()
        }
    }

    /**
     * Cancels any pending prepare operation by canceling the parent job.
     *
     * This method is safe to call if no prepare is currently active, it will have no effect.
     *
     * NOTE: This does not cancel any file open calls that have already started, but will prevent
     * any additional file open calls from being started.
     *
     * @param deferred The [CompletableDeferred] for the job to cancel, if one exists.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelPrepare(deferred: CompletableDeferred<PrepareMediaResult>? = null) {
        job?.let {
            it.cancel()
            Log.i(PrepareMediaFeature.TAG, "Prepare operation was cancelled.")
            // Dispatch an event to log cancellation of media items preparing
            scope.launch {
                val configuration = configurationManager.configuration.value
                events.dispatch(
                    Event.LogPhotopickerUIEvent(
                        FeatureToken.CORE.token,
                        configuration.sessionId,
                        configuration.callingPackageUid ?: -1,
                        Telemetry.UiEvent.PICKER_PRELOADING_CANCELLED,
                    )
                )
            }
        }

        // In the event of single selection mode, the selection needs to be cleared.
        if (configurationManager.configuration.value.selectionLimit == 1) {
            scope.launch { selection.clear() }
        }

        // If a deferred was passed, mark it as failed.
        deferred?.complete(PrepareMediaFailed)

        // Drop any pending heartbeats or transcoding items as the preparer job is being shutdown.
        heartbeat.resetReplayCache()
        itemsToTranscode.resetReplayCache()
    }

    /**
     * Forces the [PreparerDialogData] flows back to their initialization state so that any dialog
     * currently being shown will be hidden.
     *
     * NOTE: This does not cancel a prepare operation, so future progress may show a dialog.
     */
    fun hideAllDialogs() {
        _dialogData.update { null }
    }
}
