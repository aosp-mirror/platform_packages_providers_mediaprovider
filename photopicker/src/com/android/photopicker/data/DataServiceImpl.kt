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

package com.android.photopicker.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.UserHandle
import android.provider.CloudMediaProviderContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.paging.PagingSource
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.model.CloudMediaProviderDetails
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Group.Album
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.data.paging.AlbumMediaPagingSource
import com.android.photopicker.data.paging.AlbumPagingSource
import com.android.photopicker.data.paging.MediaPagingSource
import com.android.photopicker.features.cloudmedia.CloudMediaFeature
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provides data to the Photo Picker UI. The data comes from a [ContentProvider] called
 * [MediaProvider].
 *
 * Underlying data changes in [MediaProvider] are observed using [ContentObservers]. When a change
 * in data is observed, the data is re-fetched from the [MediaProvider] process and the new data is
 * emitted to the [StateFlows]-s.
 *
 * @param userStatus A [StateFlow] with the current active user's details.
 * @param scope The [CoroutineScope] the data flows will be shared in.
 * @param dispatcher A [CoroutineDispatcher] to run the coroutines in.
 * @param notificationService An instance of [NotificationService] responsible to listen to data
 *   change notifications.
 * @param mediaProviderClient An instance of [MediaProviderClient] responsible to get data from
 *   MediaProvider.
 * @param config [StateFlow] that emits [PhotopickerConfiguration] changes.
 */
class DataServiceImpl(
    private val userStatus: StateFlow<UserStatus>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val notificationService: NotificationService,
    private val mediaProviderClient: MediaProviderClient,
    private val config: StateFlow<PhotopickerConfiguration>,
    private val featureManager: FeatureManager,
    private val appContext: Context,
    private val events: Events,
    private val processOwnerHandle: UserHandle,
) : DataService {
    // Here default value being null signifies that the look up for the grants has not happened yet.
    // Use [refreshPreGrantedItemsCount] to populate this with the latest value.
    private var _preGrantedMediaCount: MutableStateFlow<Int?> = MutableStateFlow(null)

    // Here default value being null signifies that the look up for the uris has not happened yet.
    // Use [fetchMediaDataForUris] to populate this with the latest value.
    private var _preSelectionMediaData: MutableStateFlow<List<Media>?> = MutableStateFlow(null)

    // Keep track of the photo grid media, album grid and preview media paging sources so that we
    // can invalidate them in case the underlying data changes.
    private val mediaPagingSources: MutableList<MediaPagingSource> = mutableListOf()
    private val albumPagingSources: MutableList<AlbumPagingSource> = mutableListOf()

    // Keep track of the album grid media paging sources so that we can invalidate
    // them in case the underlying data changes or re-use them if the user re-opens the same album
    // again. If something drastically changes that would require a refresh of the data source
    // cache, remove the paging source from the below map. If a paging source is found the in map,
    // it is assumed that a refresh request was already sent to the data source once in the session
    // and there is no need to send it again, even if the paging source is invalid.
    private val albumMediaPagingSources:
        MutableMap<String, MutableMap<String, AlbumMediaPagingSource>> =
        mutableMapOf()

    // An internal lock to allow thread-safe updates to the [MediaPagingSource] and
    // [AlbumPagingSource].
    private val mediaPagingSourceMutex = Mutex()

    // An internal lock to allow thread-safe updates to the [AlbumMediaPagingSource].
    private val albumMediaPagingSourceMutex = Mutex()

    /**
     * Callback flow that listens to changes in the available providers and emits updated list of
     * available providers.
     */
    private var availableProviderCallbackFlow: Flow<List<Provider>>? = null

    /**
     * Callback flow that listens to changes in media and emits a [Unit] when change is observed.
     */
    private var mediaUpdateCallbackFlow: Flow<Unit>? = null

    /**
     * Callback flow that listens to changes in album media and emits a [Pair] of album authority
     * and album id when change is observed.
     */
    private var albumMediaUpdateCallbackFlow: Flow<Pair<String, String>>? = null

    /**
     * Saves the current job that collects the [availableProviderCallbackFlow]. Cancel this job when
     * there is a change in the [activeContentResolver]
     */
    private var availableProviderCollectJob: Job? = null

    /**
     * Saves the current job that collects the [mediaUpdateCallbackFlow]. Cancel this job when there
     * is a change in the [activeContentResolver]
     */
    private var mediaUpdateCollectJob: Job? = null

    /**
     * Saves the current job that collects the [albumMediaUpdateCallbackFlow]. Cancel this job when
     * there is a change in the [activeContentResolver]
     */
    private var albumMediaUpdateCollectJob: Job? = null

    /**
     * Internal [StateFlow] that emits when the [availableProviderCallbackFlow] emits a new list of
     * providers. The [availableProviderCallbackFlow] can change if the active user in a session has
     * changed.
     *
     * This flow is directly initialized with the available providers fetched from the data source
     * because if we initialize with a default empty list here, all PagingSource objects will get
     * created with an empty provider list and result in a transient error state.
     */
    private val _availableProviders: MutableStateFlow<List<Provider>> by lazy {
        MutableStateFlow(fetchAvailableProviders())
    }

    override val activeContentResolver =
        MutableStateFlow<ContentResolver>(userStatus.value.activeContentResolver)

    /**
     * Create an immutable state flow from the callback flow [_availableProviders]. The state flow
     * helps retain and provide immediate access to the last emitted value.
     *
     * The producer block remains active for some time after the last observer stops collecting.
     * This helps retain the flow through transient changes like activity recreation due to config
     * changes.
     *
     * Note that [StateFlow] automatically filters out subsequent repetitions of the same value.
     */
    override val availableProviders: StateFlow<List<Provider>> =
        _availableProviders.stateIn(
            scope,
            SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MILLI_SECONDS),
            _availableProviders.value,
        )

    // Contains collection info cache
    private val collectionInfoState =
        CollectionInfoState(mediaProviderClient, activeContentResolver, availableProviders)

    override val disruptiveDataUpdateChannel = Channel<Unit>(CONFLATED)

    /**
     * Same as [_preGrantedMediaCount] but as an immutable StateFlow. The count contains the latest
     * value set during the most recent [refreshPreGrantedItemsCount] call.
     */
    override val preGrantedMediaCount: StateFlow<Int?> = _preGrantedMediaCount

    /**
     * Same as [_preSelectionMediaData] but as an immutable StateFlow. The flow contains the latest
     * value set during the most recent [fetchMediaDataForUris] call.
     */
    override val preSelectionMediaData: StateFlow<List<Media>?> = _preSelectionMediaData

    companion object {
        const val FLOW_TIMEOUT_MILLI_SECONDS: Long = 5000
    }

    init {
        scope.launch(dispatcher) {
            availableProviders.collect { providers: List<Provider> ->
                Log.d(DataService.TAG, "Available providers have changed to $providers.")

                mediaPagingSourceMutex.withLock {
                    mediaPagingSources.forEach { mediaPagingSource ->
                        mediaPagingSource.invalidate()
                    }
                    albumPagingSources.forEach { albumPagingSource ->
                        albumPagingSource.invalidate()
                    }

                    mediaPagingSources.clear()
                    albumPagingSources.clear()
                }

                albumMediaPagingSourceMutex.withLock {
                    albumMediaPagingSources.values.forEach { albumMediaPagingSourceMap ->
                        albumMediaPagingSourceMap.values.forEach { albumMediaPagingSource ->
                            albumMediaPagingSource.invalidate()
                        }
                    }
                    albumMediaPagingSources.clear()
                }
            }
        }

        scope.launch(dispatcher) {
            // Only observe the changes in the active content resolver
            activeContentResolver.collect { activeContentResolver: ContentResolver ->
                Log.d(DataService.TAG, "Active content resolver has changed.")

                // Stop collecting available providers from previously initialized callback flow.
                availableProviderCollectJob?.cancel()
                availableProviderCallbackFlow = initAvailableProvidersFlow(activeContentResolver)

                availableProviderCollectJob =
                    scope.launch(dispatcher) {
                        availableProviderCallbackFlow?.collect { providers: List<Provider> ->
                            Log.d(
                                DataService.TAG,
                                "Available providers update notification received $providers",
                            )

                            updateAvailableProviders(providers)
                        }
                    }

                // Stop collecting media updates from previously initialized callback flow.
                mediaUpdateCollectJob?.cancel()
                mediaUpdateCallbackFlow = initMediaUpdateFlow(activeContentResolver)

                mediaUpdateCollectJob =
                    scope.launch(dispatcher) {
                        mediaUpdateCallbackFlow?.collect {
                            Log.d(DataService.TAG, "Media update notification received")
                            mediaPagingSourceMutex.withLock {
                                mediaPagingSources.forEach { mediaPagingSource ->
                                    mediaPagingSource.invalidate()
                                }
                            }
                        }
                    }

                // Stop collecting album media updates from previously initialized callback flow.
                albumMediaUpdateCollectJob?.cancel()
                albumMediaUpdateCallbackFlow = initAlbumMediaUpdateFlow(activeContentResolver)

                albumMediaUpdateCollectJob =
                    scope.launch(dispatcher) {
                        albumMediaUpdateCallbackFlow?.collect {
                            (albumAuthority, albumId): Pair<String, String> ->
                            Log.d(
                                DataService.TAG,
                                "Album media update notification " +
                                    "received for album authority $albumAuthority " +
                                    "and album id $albumId",
                            )
                            albumMediaPagingSourceMutex.withLock {
                                albumMediaPagingSources
                                    .get(albumAuthority)
                                    ?.get(albumId)
                                    ?.invalidate()
                            }
                        }
                    }
            }
        }

        scope.launch(dispatcher) {
            userStatus.collect { userStatusValue: UserStatus ->
                activeContentResolver.update { userStatusValue.activeContentResolver }
            }
        }
    }

    /**
     * Creates a callback flow that listens to changes in the available providers using
     * [ContentObserver] and emits updated list of available providers.
     */
    private fun initAvailableProvidersFlow(resolver: ContentResolver): Flow<List<Provider>> =
        callbackFlow<Unit> {
                // Define a callback that tries sending a [Unit] in the [Channel].
                val observer =
                    object : ContentObserver(/* handler */ null) {
                        override fun onChange(selfChange: Boolean, uri: Uri?) {
                            trySend(Unit)
                        }
                    }

                // Register the content observer callback.
                notificationService.registerContentObserverCallback(
                    resolver,
                    AVAILABLE_PROVIDERS_CHANGE_NOTIFICATION_URI,
                    /* notifyForDescendants */ true,
                    observer,
                )

                // Trigger the first fetch of available providers.
                trySend(Unit)

                // Unregister when the flow is closed.
                awaitClose {
                    notificationService.unregisterContentObserverCallback(resolver, observer)
                }
            }
            .map {
                // Fetch the available providers again when a change is detected.
                fetchAvailableProviders()
            }

    /**
     * Creates a callback flow that emits a [Unit] when an update in media is observed using
     * [ContentObserver] notifications.
     */
    private fun initMediaUpdateFlow(resolver: ContentResolver): Flow<Unit> =
        callbackFlow<Unit> {
            val observer =
                object : ContentObserver(/* handler */ null) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        trySend(Unit)
                    }
                }

            // Register the content observer callback.
            notificationService.registerContentObserverCallback(
                resolver,
                MEDIA_CHANGE_NOTIFICATION_URI,
                /* notifyForDescendants */ true,
                observer,
            )

            // Unregister when the flow is closed.
            awaitClose { notificationService.unregisterContentObserverCallback(resolver, observer) }
        }

    /**
     * Creates a callback flow that emits the album ID when an update in the album's media is
     * observed using [ContentObserver] notifications.
     */
    private fun initAlbumMediaUpdateFlow(resolver: ContentResolver): Flow<Pair<String, String>> =
        callbackFlow {
            val observer =
                object : ContentObserver(/* handler */ null) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        // Verify that album authority and album ID is present in the URI
                        if (
                            uri?.pathSegments?.size ==
                                (2 + ALBUM_CHANGE_NOTIFICATION_URI.pathSegments.size)
                        ) {
                            val albumAuthority = uri.pathSegments[uri.pathSegments.size - 2] ?: ""
                            val albumID = uri.pathSegments[uri.pathSegments.size - 1] ?: ""
                            trySend(Pair(albumAuthority, albumID))
                        }
                    }
                }

            // Register the content observer callback.
            notificationService.registerContentObserverCallback(
                resolver,
                ALBUM_CHANGE_NOTIFICATION_URI,
                /* notifyForDescendants */ true,
                observer,
            )

            // Unregister when the flow is closed.
            awaitClose { notificationService.unregisterContentObserverCallback(resolver, observer) }
        }

    @GuardedBy("albumMediaPagingSourceMutex")
    override fun albumMediaPagingSource(album: Album): PagingSource<MediaPageKey, Media> =
        runBlocking {
            refreshAlbumMedia(album)

            albumMediaPagingSourceMutex.withLock {
                val albumMap = albumMediaPagingSources.getOrDefault(album.authority, mutableMapOf())

                if (!albumMap.containsKey(album.id) || albumMap[album.id]!!.invalid) {
                    val availableProviders: List<Provider> = availableProviders.value
                    val contentResolver: ContentResolver = activeContentResolver.value
                    val albumMediaPagingSource =
                        AlbumMediaPagingSource(
                            album.id,
                            album.authority,
                            contentResolver,
                            availableProviders,
                            mediaProviderClient,
                            dispatcher,
                            config.value,
                            events,
                        )

                    Log.v(
                        DataService.TAG,
                        "Created an album media paging source that queries " + "$availableProviders",
                    )

                    albumMap[album.id] = albumMediaPagingSource
                    albumMediaPagingSources[album.authority] = albumMap
                }

                albumMap[album.id]!!
            }
        }

    @GuardedBy("mediaPagingSourceMutex")
    override fun albumPagingSource(): PagingSource<MediaPageKey, Album> = runBlocking {
        mediaPagingSourceMutex.withLock {
            val availableProviders: List<Provider> = availableProviders.value
            val contentResolver: ContentResolver = activeContentResolver.value
            val albumPagingSource =
                AlbumPagingSource(
                    contentResolver,
                    availableProviders,
                    mediaProviderClient,
                    dispatcher,
                    config.value,
                    events,
                )

            Log.v(
                DataService.TAG,
                "Created an album paging source that queries " + "$availableProviders",
            )

            albumPagingSources.add(albumPagingSource)
            albumPagingSource
        }
    }

    override fun cloudMediaProviderDetails(
        authority: String
    ): StateFlow<CloudMediaProviderDetails?> =
        throw NotImplementedError("This method is not implemented yet.")

    @GuardedBy("mediaPagingSourceMutex")
    override fun mediaPagingSource(): PagingSource<MediaPageKey, Media> = runBlocking {
        mediaPagingSourceMutex.withLock {
            val availableProviders: List<Provider> = availableProviders.value
            val contentResolver: ContentResolver = activeContentResolver.value
            val mediaPagingSource =
                MediaPagingSource(
                    contentResolver,
                    availableProviders,
                    mediaProviderClient,
                    dispatcher,
                    config.value,
                    events,
                )

            Log.v(DataService.TAG, "Created a media paging source that queries $availableProviders")

            mediaPagingSources.add(mediaPagingSource)
            mediaPagingSource
        }
    }

    @GuardedBy("mediaPagingSourceMutex")
    override fun previewMediaPagingSource(
        currentSelection: Set<Media>,
        currentDeselection: Set<Media>,
    ): PagingSource<MediaPageKey, Media> = runBlocking {
        mediaPagingSourceMutex.withLock {
            val availableProviders: List<Provider> = availableProviders.value
            val contentResolver: ContentResolver = activeContentResolver.value
            val mediaPagingSource =
                MediaPagingSource(
                    contentResolver,
                    availableProviders,
                    mediaProviderClient,
                    dispatcher,
                    config.value,
                    events,
                    /* is_preview_request */ true,
                    currentSelection.mapNotNull { it.mediaId }.toCollection(ArrayList()),
                    currentDeselection.mapNotNull { it.mediaId }.toCollection(ArrayList()),
                )

            Log.v(
                DataService.TAG,
                "Created a media paging source that queries database for" + "preview items.",
            )
            mediaPagingSources.add(mediaPagingSource)
            mediaPagingSource
        }
    }

    override suspend fun refreshMedia() {
        val availableProviders: List<Provider> = availableProviders.value
        refreshMedia(availableProviders)
    }

    @GuardedBy("albumMediaPagingSourceMutex")
    override suspend fun refreshAlbumMedia(album: Album) {
        albumMediaPagingSourceMutex.withLock {
            // Send album media refresh request only when the album media paging source is not
            // already cached.
            if (
                albumMediaPagingSources.containsKey(album.authority) &&
                    albumMediaPagingSources[album.authority]!!.containsKey(album.id)
            ) {
                Log.i(
                    DataService.TAG,
                    "A media paging source is available for " +
                        "album ${album.id}. Not sending a refresh album media request.",
                )
                return
            }
        }

        val providers = availableProviders.value
        val isAlbumProviderAvailable =
            providers.any { provider -> provider.authority == album.authority }

        if (isAlbumProviderAvailable) {
            mediaProviderClient.refreshAlbumMedia(
                album.id,
                album.authority,
                providers,
                activeContentResolver.value,
                config.value,
            )
        } else {
            Log.e(
                DataService.TAG,
                "Available providers $providers " +
                    "does not contain album authority ${album.authority}. " +
                    "Skip sending refresh album media request.",
            )
        }
    }

    override suspend fun getCollectionInfo(provider: Provider): CollectionInfo {
        return collectionInfoState.getCollectionInfo(provider)
    }

    override suspend fun ensureProviders() {
        mediaProviderClient.ensureProviders(activeContentResolver.value)
        updateAvailableProviders(fetchAvailableProviders())
    }

    override fun getAllAllowedProviders(): List<Provider> {
        val configSnapshot = config.value
        val user = userStatus.value.activeUserProfile.handle
        val enforceAllowlist = configSnapshot.flags.CLOUD_ENFORCE_PROVIDER_ALLOWLIST
        val allowlist = configSnapshot.flags.CLOUD_ALLOWED_PROVIDERS
        val intent = Intent(CloudMediaProviderContract.PROVIDER_INTERFACE)
        val packageManager = appContext.getPackageManager()
        val allProviders: List<ResolveInfo> =
            packageManager.queryIntentContentProvidersAsUser(intent, /* flags */ 0, user)

        val allowedProviders =
            allProviders
                .filter {
                    it.providerInfo.authority != null &&
                        CloudMediaProviderContract.MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION.equals(
                            it.providerInfo.readPermission
                        ) &&
                        (!enforceAllowlist || allowlist.contains(it.providerInfo.packageName))
                }
                .map {
                    Provider(
                        authority = it.providerInfo.authority,
                        mediaSource = MediaSource.REMOTE,
                        uid =
                            packageManager.getPackageUid(
                                it.providerInfo.packageName,
                                /* flags */ 0,
                            ),
                        displayName = it.loadLabel(packageManager) as? String ?: "",
                    )
                }

        return allowedProviders
    }

    /**
     * Sends an update to the [_availableProviders] State flow. Collection info cache gets cleared
     * because it is potentially stale. If the new set of available providers does not contain all
     * of the previously available providers, then the UI should ideally clear itself immediately to
     * avoid displaying any media items from a clud provider that is not currently available. To
     * communicate this with the UI, [disruptiveDataUpdateChannel] might emit a Unit object.
     *
     * @param providers The list of new available providers.
     */
    private suspend fun updateAvailableProviders(providers: List<Provider>) {
        // Send refresh media request to Photo Picker.
        // TODO(b/340246010): This is required even when there is no change in
        // the [availableProviders] state flow because PhotoPicker relies on the
        // UI to trigger a sync when the cloud provider changes. Further, a
        // successful sync enables cloud queries, which then updates the UI.
        refreshMedia(providers)

        // refresh count for preGranted media.
        refreshPreGrantedItemsCount()

        config.value.preSelectedUris?.let { fetchMediaDataForUris(it) }

        val previouslyAvailableProviders = _availableProviders.value

        _availableProviders.update { providers }

        // If the available providers are not a superset of previously available
        // providers, this is a disruptive data update that should ideally
        // reset the UI.
        if (!providers.containsAll(previouslyAvailableProviders)) {
            Log.d(DataService.TAG, "Sending a disruptive data update notification.")
            disruptiveDataUpdateChannel.send(Unit)
        }

        // Clear collection info cache immediately and update the cache from
        // data source in a child coroutine.
        collectionInfoState.clear()
    }

    override fun refreshPreGrantedItemsCount() {
        // value for _preGrantedMediaCount being null signifies that the count has not been fetched
        // yet for this photopicker session.
        // This should only be used in ACTION_USER_SELECT_IMAGES_FOR_APP mode since grants only
        // exist for this mode.
        if (
            _preGrantedMediaCount.value == null &&
                MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.equals(config.value.action)
        ) {
            _preGrantedMediaCount.update {
                mediaProviderClient.fetchMediaGrantsCount(
                    activeContentResolver.value,
                    config.value.callingPackageUid ?: -1,
                )
            }
        }
    }

    override fun fetchMediaDataForUris(uris: List<Uri>) {
        // value for _preSelectionMediaData being null signifies that the data has not been fetched
        // yet for this photopicker session.
        if (_preSelectionMediaData.value == null && uris.isNotEmpty()) {
            // Pre-selection state is not accessible cross-profile, so any time the
            // [activeUserProfile] is not the Process owner's profile, pre-selections should not be
            // refreshed and any cached state should not be updated to the UI.
            if (
                userStatus.value.activeUserProfile.handle.identifier ==
                    processOwnerHandle.getIdentifier()
            ) {
                _preSelectionMediaData.update {
                    mediaProviderClient.fetchFilteredMedia(
                        MediaPageKey(),
                        MediaStore.getPickImagesMaxLimit(),
                        activeContentResolver.value,
                        _availableProviders.value,
                        config.value,
                        uris,
                    )
                }
            }
        }
    }

    /**
     * Sends a refresh media notification to the data source. This signal tells the data source to
     * refresh its cache.
     *
     * @param providers The list of currently available providers.
     */
    private fun refreshMedia(availableProviders: List<Provider>) {
        if (availableProviders.isNotEmpty()) {
            mediaProviderClient.refreshMedia(
                availableProviders,
                activeContentResolver.value,
                config.value,
            )
        } else {
            Log.w(DataService.TAG, "Cannot refresh media when there are no providers available")
        }
    }

    /**
     * Fetch available providers from the data source and return it. If the [CloudMediaFeature] is
     * turned off, the available list of providers received from the data source will filter out all
     * providers that serve [MediaSource.Remote] items.
     */
    private fun fetchAvailableProviders(): List<Provider> {
        var availableProviders =
            mediaProviderClient.fetchAvailableProviders(activeContentResolver.value)
        if (!featureManager.isFeatureEnabled(CloudMediaFeature::class.java)) {
            availableProviders = availableProviders.filter { it.mediaSource != MediaSource.REMOTE }
            Log.i(
                DataService.TAG,
                "Cloud media feature is not enabled, available providers are " +
                    "updated to  $availableProviders",
            )
        }
        return availableProviders
    }
}
