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

package com.android.photopicker.features.cloudmedia

import android.util.Log
import androidx.annotation.GuardedBy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.photopicker.core.Background
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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

/** Enumeration for the LoadState of a given preloaded item. */
private enum class LoadResult {
    COMPLETED,
    FAILED,
    QUEUED,
}

/** Data objects which contain all the UI data to render the various Preloader dialogs. */
sealed interface PreloaderDialogData {

    /**
     * The loading dialog data
     *
     * @param total Total of items to be loaded
     * @param completed Number of items currently completed
     */
    data class PreloaderLoadingDialogData(
        val total: Int,
        val completed: Int = 0,
    ) : PreloaderDialogData

    /** Empty object for telling the UI to show a generic error dialog */
    object PreloaderLoadingErrorDialog : PreloaderDialogData
}

/**
 * The view model for the [MediaPreloader].
 *
 * This is the class responsible for the requests to remote providers to prepare remote media for
 * local apps. The main preloading operation should only be triggered by the main activity, by
 * emitting a set of media to preload into the flow provided to the MediaPreloader compose UI via
 * [LocationParams].
 *
 * Additionally, this method exposes the required state data for the UI to draw the correct dialog
 * overlays as preloading is initiated, is progressing, and resolves with either a failure or a
 * success.
 *
 * This class should not be injected anywhere other than the MediaPreloader's context to attempt to
 * monitor the state of the ongoing preload.
 *
 * When the preload is complete, the [CompletableDeferred] that is passed in the [LocationParams]
 * will be marked completed, A TRUE value indicates success, and a FALSE value indicates a failure.
 */
@HiltViewModel
class MediaPreloaderViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val selection: Selection<Media>,
    private val userMonitor: UserMonitor,
    private val events: Events,
) : ViewModel() {

    companion object {
        // Ensure only 2 downloads are occurring in parallel.
        val MAX_CONCURRENT_LOADS = 2
    }

    /* Parent job that owns the overall preloader operation & monitor */
    private var job: Job? = null

    /*
     * A heartbeat flow to drive the preload monitor job.
     * Replay = 1 and DROP_OLDEST due to the fact the heartbeat doesn't contain any useful
     * data, so as long as something is in the buffer to be collected, there's no need
     * for duplicate emissions.
     */
    private val heartbeat: MutableSharedFlow<Unit> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Protect [remoteItems] with a Mutex since multiple coroutines are reading/writing it.
    private val mutex = Mutex()
    // A list of remote items to be loaded, and their current [LoadResult].
    // NOTE: This should always be accessed after acquiring the [Mutex] to ensure data
    // accuracy during concurrency.
    @GuardedBy("mutex") private val remoteItems = mutableMapOf<Media, LoadResult>()

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

    /** Flow that can push new data into the preloader's dialogs. */
    private val _dialogData = MutableStateFlow<PreloaderDialogData?>(null)

    /** Public flow for the compose ui to collect. */
    val dialogData: StateFlow<PreloaderDialogData?> =
        _dialogData.stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            initialValue = _dialogData.value
        )

    init {

        // If the active user's resolver changes, cancel any pending preload work.
        scope.launch {
            _contentResolver.collect {
                // Action is only required if there's currently a job running.
                job?.let {
                    Log.d(CloudMediaFeature.TAG, "User was changed, abandoning preloads")
                    it.cancel()
                    hideAllDialogs()
                }
            }
        }
    }

    /**
     * Entrypoint of the selected media preload operation.
     *
     * This is triggered when the preloadMedia flow from compose receives a new Set<Media> to
     * preload.
     *
     * Once the new set of media is received from its source, the compose UI will call startPreload
     * to begin the preload of the set.
     *
     * This operation will enqueue work to load any [DataSource.REMOTE] files that are present in
     * the current selection to ensure they are downloaded / prepared by the remote provider. This
     * has the benefit of ensuring that the files can be immediately opened by the App that started
     * Photopicker without having to deal with awaiting any remote procedures to bring the remote
     * file down to the device.
     *
     * This method will run a parent [CoroutineScope] (see [job] in this class), which will
     * subsequently schedule child jobs for each remote item in the selection. The [Background]
     * [CoroutineDispatcher] is used for this operation, however the parallel execution is limited
     * to [MAX_CONCURRENT_LOADS] to avoid over-stressing the remote providers and saturating the
     * available network bandwidth.
     *
     * @param selection The set of media to preload
     * @param deferred A [CompletableDeferred] that can be used to signal when the preload operation
     *   is complete. TRUE represents success, FALSE represents failure.
     * @see [LocationParams.WithMediaPreloader] for the data that is passed to the UI to attach the
     *   preloader.
     */
    suspend fun startPreload(selection: Set<Media>, deferred: CompletableDeferred<Boolean>) {

        mutex.withLock {

            // Begin by clearing any prior state.
            remoteItems.clear()

            for (item in selection.filter { it.mediaSource == MediaSource.REMOTE }) {
                remoteItems.put(item, LoadResult.QUEUED)
            }
        }

        // End early if there are not any [DataSource.REMOTE] items in the current selection.
        if (remoteItems.isEmpty()) {
            Log.i(CloudMediaFeature.TAG, "Preload not required, no remote items.")
            deferred.complete(true)
            return
        }

        Log.i(
            CloudMediaFeature.TAG,
            "SelectionMediaBeginPreload operation was requested. " +
                "Total remote items: ${remoteItems.size}"
        )
        // Update the UI so the Loading dialog can be displayed with the initial loading data.
        _dialogData.update {
            PreloaderDialogData.PreloaderLoadingDialogData(
                total = selection.size,
                // Local items are automatically "completed" as there is nothing to preload.
                completed = (selection.size - remoteItems.size),
            )
        }

        // All preloading work must be a child of this job, a reference of the job is saved
        // so that if the User requests cancellation the child jobs receive the cancellation as
        // well.
        job =
            scope.launch(backgroundDispatcher) {
                // Enqueue a job to monitor the ongoing operation. This job is crucially also a
                // child of the main preloading job, so it will be canceled anytime loading is
                // canceled.
                launch { monitorPreloadOperation(deferred) }

                // Start a parallelism constrained child job to actually handle the loads to
                // enforce that the device bandwidth doesn't become over saturated by trying
                // to load too many files at once.
                launch(
                    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                    backgroundDispatcher.limitedParallelism(MAX_CONCURRENT_LOADS)
                ) {
                    // This is the main preloader job coroutine, enqueue other work here, but
                    // don't run any heavy / blocking work, as it will prevent the loading
                    // from starting.
                    for (item in remoteItems.keys) {
                        launch { preloadMediaItem(item, deferred) }
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
    private suspend fun preloadMediaItem(item: Media, deferred: CompletableDeferred<Boolean>) {
        Log.v(CloudMediaFeature.TAG, "Beginning preload of: $item")
        try {
            if (!deferred.isCompleted) {
                userMonitor.userStatus.value.activeContentResolver
                    .openAssetFileDescriptor(item.mediaUri, "r")
                    .use {

                        // Mark the item as complete in the result status.
                        mutex.withLock { remoteItems.set(item, LoadResult.COMPLETED) }

                        // Update the [PreloaderDialogData] flow an increment the
                        // completed operations by one so the UI updates.
                        _dialogData.update {
                            when (it) {
                                is PreloaderDialogData.PreloaderLoadingDialogData ->
                                    it.copy(completed = it.completed + 1)
                                else -> it
                            }
                        }
                        Log.v(CloudMediaFeature.TAG, "Preload successful: $item")
                    }
                // Emit a new monitor heartbeat so the preload can continue or finish.
                heartbeat.emit(Unit)
            }
        } catch (e: FileNotFoundException) {
            Log.e(CloudMediaFeature.TAG, "Error while preloading $item", e)

            // Only need to take action if the deferred is already not marked as completed,
            // another load job may have already failed.
            if (!deferred.isCompleted) {
                Log.d(
                    CloudMediaFeature.TAG,
                    "Failure detected, cancelling the rest of the preload operation."
                )
                // Mark the item as failed in the result status.
                mutex.withLock { remoteItems.set(item, LoadResult.FAILED) }
                // Emit a new heartbeat so the monitor will react to this failure.
                heartbeat.emit(Unit)
            }
        }
    }

    /**
     * Suspended function that monitors [remoteItems] preloading and takes an action when all items
     * are [LoadResult.COMPLETED] or a [LoadResult.FAILURE] is found in [remoteItems].
     *
     * When all remoteItems are [LoadResult.COMPLETED] -> mark the [CompletableDeferred] that
     * represents this preload operation as completed(TRUE) to signal the preload was successful.
     *
     * When one of the remoteItems returns [LoadResult.FAILED] any pending preloads are cancelled,
     * and the parent job is also canceled. The failed item(s) will be removed from the current
     * selection, and the deferred will be completed(FALSE) to signal the preload has failed.
     *
     * This method will run a new check for every heartbeat, and does not observe the [remoteItems]
     * data structure directly. As such, it's important that any status changes in the state of
     * loading trigger an update of heartbeat for the collector in this method to execute.
     *
     * @param deferred the status of the overall preload operation. TRUE signals a successful
     *   preload, and FALSE a failure.
     */
    private suspend fun monitorPreloadOperation(deferred: CompletableDeferred<Boolean>) {

        heartbeat.collect {

            // Outcomes, another possibility is neither is met, and the load should continue until
            // the next result.
            var loadFailed = false
            var loadCompleted = false

            // Fetch the current results with the mutex, but don't hold the mutex longer than
            // needed.
            mutex.withLock {

                // The load is failed if any single item fails to load.
                loadFailed = remoteItems.any { (_, loadResult) -> loadResult == LoadResult.FAILED }

                // The load is complete if all items are completed successfully.
                loadCompleted =
                    remoteItems.all { (_, loadResult) -> loadResult == LoadResult.COMPLETED }
            }

            // Outcomes, if none of these branches are yet met, the load will continue, and this
            // block will run on the next known result.
            when {
                loadFailed -> {
                    // Remove any failed items from the selection
                    selection.removeAll(
                        remoteItems
                            .filter { (_, loadResult) -> loadResult == LoadResult.FAILED }
                            .keys
                    )
                    // Now that a failure has been detected, update the [PreloaderDialogData]
                    // so the UI will show the loading error dialog.
                    _dialogData.update { PreloaderDialogData.PreloaderLoadingErrorDialog }

                    // Since something has failed, mark the overall preload operation as failed.
                    deferred.complete(false)
                }
                loadCompleted -> {
                    // If all of the remote items have completed successfully, the preload operation
                    // is complete, deferred can be marked as complete(true) to instruct the
                    // application to send the selected Media to the caller.
                    Log.d(CloudMediaFeature.TAG, "Preload operation was successful.")
                    deferred.complete(true)
                }
            }

            // If the load has a result, clean up the active running job.
            if (loadFailed || loadCompleted) {
                job?.cancel()
                // Drop any pending heartbeats as the monitor job is being shutdown.
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                heartbeat.resetReplayCache()
            }
        }
    }

    /**
     * Cancels any pending preload operation by canceling the parent job.
     *
     * This method is safe to call if no preload is currently active, it will have no effect.
     *
     * NOTE: This does not cancel any file open calls that have already started, but will prevent
     * any additional file open calls from being started.
     */
    fun cancelPreload() {
        job?.let {
            it.cancel()
            Log.i(CloudMediaFeature.TAG, "Preload operation was cancelled.")
        }

        // Drop any pending heartbeats as the monitor job is being shutdown.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) heartbeat.resetReplayCache()
    }

    /**
     * Forces the [PreloaderDialogData] flows back to their initialization state so that any dialog
     * currently being shown will be hidden.
     *
     * NOTE: This does not cancel a preload operation, so future progress may show a dialog.
     */
    fun hideAllDialogs() {
        _dialogData.update { null }
    }
}
