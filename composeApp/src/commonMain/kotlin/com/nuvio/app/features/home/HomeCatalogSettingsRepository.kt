package com.nuvio.app.features.home

import com.nuvio.app.features.addons.AddonRepository
import androidx.compose.ui.text.intl.Locale
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

data class HomeCatalogSettingsItem(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
    val isCollection: Boolean = false,
    val collectionId: String? = null,
    val isPinnedToTop: Boolean = false,
) {
    val displayTitle: String
        get() = customTitle.ifBlank { defaultTitle }
}

data class HomeCatalogSettingsUiState(
    val heroEnabled: Boolean = true,
    val showCatalogType: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    // Recently-watched live channels on the home screen (inside Continue Watching). Live channels have no
    // resume position, so mobile/desktop surface them as a "recents" row; off keeps home VOD-only (TV
    // never shows them). Default true = current behaviour.
    val showLiveOnHome: Boolean = true,
    val items: List<HomeCatalogSettingsItem> = emptyList(),
) {
    val signature: String
        get() = buildString {
            append(heroEnabled)
            append('|')
            append(showCatalogType)
            append('|')
            append(hideUnreleasedContent)
            append('|')
            append(showLiveOnHome)
            append('|')
            append(
                items.joinToString(separator = "|") { item ->
                    "${item.key}:${item.order}:${item.enabled}:${item.heroSourceEnabled}:${item.customTitle}"
                }
            )
        }
}

internal data class HomeCatalogPreference(
    val customTitle: String,
    val enabled: Boolean,
    val heroSourceEnabled: Boolean,
    val order: Int,
)

internal data class HomeCatalogSettingsSnapshot(
    val heroEnabled: Boolean,
    val showCatalogType: Boolean,
    val hideUnreleasedContent: Boolean,
    val preferences: Map<String, HomeCatalogPreference>,
)

@Serializable
private data class StoredHomeCatalogPreference(
    val key: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
)

@Serializable
private data class StoredHomeCatalogSettingsPayload(
    val heroEnabled: Boolean = true,
    val showCatalogType: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val showLiveOnHome: Boolean = true,
    val items: List<StoredHomeCatalogPreference> = emptyList(),
)

object HomeCatalogSettingsRepository {
    const val HERO_SOURCE_SELECTION_LIMIT = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(HomeCatalogSettingsUiState())
    val uiState: StateFlow<HomeCatalogSettingsUiState> = _uiState.asStateFlow()

    private val emptySnapshot = HomeCatalogSettingsSnapshot(
        heroEnabled = true,
        showCatalogType = true,
        hideUnreleasedContent = false,
        preferences = emptyMap(),
    )
    private val snapshotRef = atomic(emptySnapshot)
    private val syncMutex = Mutex()
    private var hasLoaded = false
    private var lastPersistedPayload: String? = null
    private var definitions: List<HomeCatalogDefinition> = emptyList()
    private var collectionDefinitions: List<CollectionCatalogDefinition> = emptyList()
    private var lastCatalogSync: Triple<List<ManagedAddon>, List<Collection>, String>? = null
    private var lastCollectionSync: Pair<List<Collection>, String>? = null
    private val preferencesRef = atomic<Map<String, StoredHomeCatalogPreference>>(emptyMap())
    private var preferences: Map<String, StoredHomeCatalogPreference>
        get() = preferencesRef.value
        set(value) {
            preferencesRef.value = value
        }
    private var heroEnabled = true
    private var showCatalogType = true
    private var hideUnreleasedContent = false
    private var showLiveOnHome = true

    fun onProfileChanged() {
        hasLoaded = false
        preferences = emptyMap()
        heroEnabled = true
        showCatalogType = true
        hideUnreleasedContent = false
        showLiveOnHome = true
        definitions = emptyList()
        collectionDefinitions = emptyList()
        snapshotRef.value = emptySnapshot
        lastPersistedPayload = null
        lastCatalogSync = null
        lastCollectionSync = null
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun clearLocalState() {
        hasLoaded = false
        definitions = emptyList()
        collectionDefinitions = emptyList()
        lastCatalogSync = null
        lastCollectionSync = null
        preferences = emptyMap()
        heroEnabled = true
        showCatalogType = true
        hideUnreleasedContent = false
        showLiveOnHome = true
        snapshotRef.value = emptySnapshot
        lastPersistedPayload = null
        _uiState.value = HomeCatalogSettingsUiState()
    }

    suspend fun syncCatalogs(addons: List<ManagedAddon>) = withContext(Dispatchers.Default) {
        syncMutex.withLock {
            syncCatalogsInternal(addons)
        }
    }

    private fun syncCatalogsInternal(addons: List<ManagedAddon>) {
        ensureLoaded()
        val collections = CollectionRepository.collections.value
        val syncInput = Triple(addons, collections, Locale.current.toLanguageTag())
        if (lastCatalogSync == syncInput) return
        definitions = buildHomeCatalogDefinitions(addons)
        collectionDefinitions = buildCollectionDefinitions(collections)
        lastCatalogSync = syncInput
        lastCollectionSync = lastCollectionSync?.takeIf {
            it.first == collections && it.second == syncInput.third
        }
        if (definitions.isEmpty() && collectionDefinitions.isEmpty()) {
            publish()
            return
        }
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
    }

    suspend fun syncCollections(
        collections: List<Collection>,
        addons: List<ManagedAddon> = AddonRepository.uiState.value.addons.enabledAddons(),
    ) = withContext(Dispatchers.Default) {
        syncMutex.withLock {
            syncCollectionsInternal(collections = collections, addons = addons)
        }
    }

    private fun syncCollectionsInternal(
        collections: List<Collection>,
        addons: List<ManagedAddon>,
    ) {
        ensureLoaded()
        val syncInput = collections to Locale.current.toLanguageTag()
        if (lastCollectionSync == syncInput) return
        if (definitions.isEmpty()) definitions = buildHomeCatalogDefinitions(addons)
        collectionDefinitions = buildCollectionDefinitions(collections)
        lastCatalogSync = lastCatalogSync?.takeIf {
            it.second == collections && it.third == syncInput.second
        }
        lastCollectionSync = syncInput
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    internal fun snapshot(): HomeCatalogSettingsSnapshot {
        ensureLoaded()
        return snapshotRef.value
    }

    fun setHeroEnabled(enabled: Boolean) {
        ensureLoaded()
        if (heroEnabled == enabled) return
        heroEnabled = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun setShowCatalogType(enabled: Boolean) {
        ensureLoaded()
        if (showCatalogType == enabled) return
        showCatalogType = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setHideUnreleasedContent(enabled: Boolean) {
        ensureLoaded()
        if (hideUnreleasedContent == enabled) return
        hideUnreleasedContent = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setShowLiveOnHome(enabled: Boolean) {
        ensureLoaded()
        if (showLiveOnHome == enabled) return
        showLiveOnHome = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setHeroSourceEnabled(key: String, enabled: Boolean) {
        updatePreference(key, pushRemote = false) { preference ->
            if (!enabled) {
                preference.copy(heroSourceEnabled = false)
            } else if (selectedHeroSourceCount(excludingKey = key) >= HERO_SOURCE_SELECTION_LIMIT) {
                preference
            } else {
                preference.copy(heroSourceEnabled = true)
            }
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        updatePreference(key) { preference ->
            preference.copy(enabled = enabled)
        }
    }

    fun setCustomTitle(key: String, title: String) {
        updatePreference(key) { preference ->
            preference.copy(customTitle = title)
        }
    }

    fun resetToDefaults() {
        ensureLoaded()
        heroEnabled = true
        showCatalogType = true
        hideUnreleasedContent = false
        showLiveOnHome = true
        preferences = emptyMap()
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun moveUp(key: String) {
        move(key = key, direction = -1)
    }

    fun moveDown(key: String) {
        move(key = key, direction = 1)
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val allKeys = allOrderedKeys()
        if (allKeys.isEmpty()) return
        if (fromIndex !in allKeys.indices || toIndex !in allKeys.indices) return
        if (fromIndex == toIndex) return
        val orderedKeys = allKeys.toMutableList()
        orderedKeys.add(toIndex, orderedKeys.removeAt(fromIndex))
        val updatedPreferences = preferences.toMutableMap()
        orderedKeys.forEachIndexed { index, itemKey ->
            val current = updatedPreferences[itemKey] ?: return@forEachIndexed
            updatedPreferences[itemKey] = current.copy(order = index)
        }
        preferences = updatedPreferences
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    private fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = HomeCatalogSettingsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val parsedPayload = runCatching {
            json.decodeFromString<StoredHomeCatalogSettingsPayload>(payload)
        }.getOrNull()

        if (parsedPayload != null) {
            lastPersistedPayload = payload
            heroEnabled = parsedPayload.heroEnabled
            showCatalogType = parsedPayload.showCatalogType
            hideUnreleasedContent = parsedPayload.hideUnreleasedContent
            showLiveOnHome = parsedPayload.showLiveOnHome
            preferences = parsedPayload.items.associateBy { it.key }
            publish()
            return
        }

        val legacyItems = runCatching {
            json.decodeFromString<List<StoredHomeCatalogPreference>>(payload)
        }.getOrDefault(emptyList())

        preferences = legacyItems.associateBy { it.key }
        publish()
    }

    private fun normalizePreferences() {
        val current = preferences
        data class UnifiedEntry(val key: String, val isCollection: Boolean)
        val catalogEntries = definitions.map { UnifiedEntry(it.key, false) }
        val collectionEntries = collectionDefinitions.map { UnifiedEntry(it.key, true) }
        val allEntries = catalogEntries + collectionEntries
        val knownKeys = allEntries.mapTo(linkedSetOf(), UnifiedEntry::key)
        var nextOrder = (current.values.maxOfOrNull(StoredHomeCatalogPreference::order) ?: -1) + 1

        val orderedEntries = allEntries.mapIndexed { defaultIndex, entry ->
            Triple(
                entry,
                current[entry.key]?.order ?: (nextOrder + defaultIndex),
                defaultIndex,
            )
        }.sortedWith(
            compareBy<Triple<UnifiedEntry, Int, Int>>(
                { it.second },
                { it.third },
            ),
        ).map { it.first }

        val normalized = current
            .filterKeys { it !in knownKeys }
            .toMutableMap()
        var enabledHeroSourceCount = 0
        orderedEntries.forEach { entry ->
            val stored = current[entry.key]
            val heroSourceEnabled = if (entry.isCollection) {
                false
            } else {
                (stored?.heroSourceEnabled ?: true) &&
                    enabledHeroSourceCount < HERO_SOURCE_SELECTION_LIMIT
            }
            if (heroSourceEnabled) {
                enabledHeroSourceCount += 1
            }
            normalized[entry.key] = StoredHomeCatalogPreference(
                key = entry.key,
                customTitle = stored?.customTitle.orEmpty(),
                enabled = stored?.enabled ?: true,
                heroSourceEnabled = heroSourceEnabled,
                order = stored?.order ?: nextOrder++,
            )
        }
        preferences = normalized.toMap()
    }

    private fun publish() {
        snapshotRef.value = HomeCatalogSettingsSnapshot(
            heroEnabled = heroEnabled,
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            preferences = preferences.mapValues { (_, value) ->
                HomeCatalogPreference(
                    customTitle = value.customTitle,
                    enabled = value.enabled,
                    heroSourceEnabled = value.heroSourceEnabled,
                    order = value.order,
                )
            },
        )
        val catalogItems = definitions
            .map { definition ->
                val preference = preferences[definition.key]
                HomeCatalogSettingsItem(
                    key = definition.key,
                    defaultTitle = definition.defaultTitle,
                    addonName = definition.addonName,
                    customTitle = preference?.customTitle.orEmpty(),
                    enabled = preference?.enabled ?: true,
                    heroSourceEnabled = preference?.heroSourceEnabled ?: true,
                    order = preference?.order ?: 0,
                )
            }

        val collectionItems = collectionDefinitions.map { colDef ->
            val preference = preferences[colDef.key]
            HomeCatalogSettingsItem(
                key = colDef.key,
                defaultTitle = colDef.title,
                addonName = colDef.subtitle,
                customTitle = preference?.customTitle.orEmpty(),
                enabled = preference?.enabled ?: true,
                heroSourceEnabled = false,
                order = preference?.order ?: 0,
                isCollection = true,
                collectionId = colDef.collectionId,
                isPinnedToTop = colDef.isPinnedToTop,
            )
        }

        val rowIndex = allOrderedKeys().withIndex().associate { (index, key) -> key to index }
        val items = (catalogItems + collectionItems)
            .sortedBy { rowIndex[it.key] ?: Int.MAX_VALUE }

        val nextState = HomeCatalogSettingsUiState(
            heroEnabled = heroEnabled,
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            showLiveOnHome = showLiveOnHome,
            items = items,
        )
        if (_uiState.value != nextState) _uiState.value = nextState
    }

    private fun persist() {
        val payload = json.encodeToString(
            StoredHomeCatalogSettingsPayload(
                heroEnabled = heroEnabled,
                showCatalogType = showCatalogType,
                hideUnreleasedContent = hideUnreleasedContent,
                showLiveOnHome = showLiveOnHome,
                items = preferences.values.sortedBy { it.order },
            ),
        )
        if (payload == lastPersistedPayload) return
        HomeCatalogSettingsStorage.savePayload(payload)
        lastPersistedPayload = payload
    }

    private fun updatePreference(
        key: String,
        pushRemote: Boolean = true,
        transform: (StoredHomeCatalogPreference) -> StoredHomeCatalogPreference,
    ) {
        ensureLoaded()
        val current = preferences[key] ?: defaultPreferenceForMissingKey(key) ?: return
        val updated = transform(current)
        if (updated == current) return
        preferences = preferences + (key to updated)
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        if (pushRemote) {
            HomeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun selectedHeroSourceCount(excludingKey: String? = null): Int {
        val catalogKeys = definitions.mapTo(mutableSetOf()) { it.key }
        return preferences.count { (itemKey, preference) ->
            itemKey != excludingKey && itemKey in catalogKeys && preference.heroSourceEnabled
        }
    }

    private fun move(
        key: String,
        direction: Int,
    ) {
        ensureLoaded()
        val orderedKeys = allOrderedKeys().toMutableList()
        if (orderedKeys.isEmpty()) return

        val currentIndex = orderedKeys.indexOf(key)
        if (currentIndex == -1) return

        val targetIndex = currentIndex + direction
        if (targetIndex !in orderedKeys.indices) return

        val movingKey = orderedKeys.removeAt(currentIndex)
        orderedKeys.add(targetIndex, movingKey)

        val updatedPreferences = preferences.toMutableMap()
        orderedKeys.forEachIndexed { index, itemKey ->
            val current = updatedPreferences[itemKey] ?: return@forEachIndexed
            updatedPreferences[itemKey] = current.copy(order = index)
        }
        preferences = updatedPreferences

        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun exportToSyncPayload(): SyncHomeCatalogPayload {
        ensureLoaded()
        val catalogDefinitionsByKey = definitions.associateBy { it.key }
        val collectionDefinitionsByKey = collectionDefinitions.associateBy { it.key }
        val items = preferences.values.sortedBy { it.order }.map { pref ->
            val catalogDefinition = catalogDefinitionsByKey[pref.key]
            val collectionDefinition = collectionDefinitionsByKey[pref.key]
            val isCollection = collectionDefinition != null || pref.key.startsWith("collection_")
            if (isCollection) {
                SyncCatalogItem(
                    addonId = "",
                    type = "",
                    catalogId = "",
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    isCollection = true,
                    collectionId = collectionDefinition?.collectionId ?: pref.key.removePrefix("collection_"),
                    key = pref.key,
                )
            } else {
                val legacyParts = pref.key.split(':', limit = 3)
                SyncCatalogItem(
                    addonId = catalogDefinition?.addonIdForSync() ?: legacyParts.getOrElse(0) { "" },
                    type = catalogDefinition?.type ?: legacyParts.getOrElse(1) { "" },
                    catalogId = catalogDefinition?.catalogId ?: legacyParts.getOrElse(2) { "" },
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    isCollection = false,
                    key = pref.key,
                )
            }
        }
        return SyncHomeCatalogPayload(
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            showLiveOnHome = showLiveOnHome,
            items = items,
        )
    }

    fun applyFromRemote(payload: SyncHomeCatalogPayload) {
        ensureLoaded()
        showCatalogType = payload.showCatalogType
        hideUnreleasedContent = payload.hideUnreleasedContent
        showLiveOnHome = payload.showLiveOnHome
        if (payload.items.isNotEmpty()) {
            val existingHeroState = preferences.mapValues { it.value.heroSourceEnabled }
            val remotePreferences = payload.items.associate { item ->
                val key = item.preferenceKey()
                key to StoredHomeCatalogPreference(
                    key = key,
                    customTitle = item.customTitle,
                    enabled = item.enabled,
                    heroSourceEnabled = existingHeroState[key] ?: true,
                    order = item.order,
                )
            }
            val remoteKeys = remotePreferences.keys
            val knownKeys = knownPreferenceKeys()
            val preservedPreferences = preferences.filterKeys { key ->
                key !in remoteKeys && (key in knownKeys || key.requiresExplicitSyncKey())
            }
            preferences = preservedPreferences + remotePreferences
            normalizePreferences()
            enforcePinnedCollectionsAtTop()
        }
        hasLoaded = true
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    /** Rows in display order: stored order with pinned collections first (B81, [HomeRowOrderPolicy]). */
    private fun allOrderedKeys(): List<String> =
        HomeRowOrderPolicy.orderedKeys(
            catalogKeys = definitions.map { it.key },
            collectionKeys = collectionDefinitions.map { it.key },
            pinnedCollectionKeys = collectionDefinitions.filter { it.isPinnedToTop }.mapTo(mutableSetOf()) { it.key },
            orderOf = { key -> preferences[key]?.order },
        )

    /**
     * Rewrites the stored order to match the display order, so what is persisted and pushed keeps
     * pinned collections first too. Display already applies the pin on every read (B81); this only
     * keeps the stored numbers honest after any path that rewrote them.
     */
    private fun enforcePinnedCollectionsAtTop() {
        if (collectionDefinitions.none { it.isPinnedToTop }) return
        val displayKeys = allOrderedKeys()
        val storedKeys = (definitions.map { it.key } + collectionDefinitions.map { it.key })
            .sortedBy { key -> preferences[key]?.order ?: Int.MAX_VALUE }
        if (displayKeys == storedKeys) return

        val updatedPreferences = preferences.toMutableMap()
        displayKeys.forEachIndexed { index, itemKey ->
            val current = updatedPreferences[itemKey] ?: return@forEachIndexed
            updatedPreferences[itemKey] = current.copy(order = index)
        }
        preferences = updatedPreferences
    }

    private fun defaultPreferenceForMissingKey(key: String): StoredHomeCatalogPreference? {
        val isCollection = collectionDefinitions.any { it.key == key }
        val isCatalog = definitions.any { it.key == key }
        if (!isCollection && !isCatalog) return null

        return StoredHomeCatalogPreference(
            key = key,
            enabled = true,
            heroSourceEnabled = isCatalog &&
                selectedHeroSourceCount(excludingKey = key) < HERO_SOURCE_SELECTION_LIMIT,
            order = _uiState.value.items.firstOrNull { it.key == key }?.order
                ?: ((preferences.values.maxOfOrNull { it.order } ?: -1) + 1),
        )
    }

    private fun knownPreferenceKeys(): Set<String> =
        definitions.mapTo(mutableSetOf()) { it.key }.also { keys ->
            keys.addAll(collectionDefinitions.map { it.key })
        }

    private fun HomeCatalogDefinition.addonIdForSync(): String {
        val suffix = ":$type:$catalogId"
        return key.removeSuffix(suffix)
    }

    private fun SyncCatalogItem.preferenceKey(): String =
        key.ifBlank {
            if (isCollection) {
                "collection_$collectionId"
            } else {
                "$addonId:$type:$catalogId"
            }
        }

    private fun String.requiresExplicitSyncKey(): Boolean =
        !startsWith("collection_") && count { it == ':' } > 2
}

internal data class CollectionCatalogDefinition(
    val key: String,
    val collectionId: String,
    val title: String,
    val subtitle: String,
    val isPinnedToTop: Boolean,
)

internal fun visibleCollectionsWithUniqueIds(collections: List<Collection>): List<Collection> =
    collections
        .filter { collection -> collection.folders.isNotEmpty() }
        .distinctBy(Collection::id)

internal fun buildCollectionDefinitions(collections: List<Collection>): List<CollectionCatalogDefinition> =
    visibleCollectionsWithUniqueIds(collections).map { collection ->
        CollectionCatalogDefinition(
            key = "collection_${collection.id}",
            collectionId = collection.id,
            title = collection.title,
            subtitle = runBlocking { getString(Res.string.collections_folder_count, collection.folders.size) },
            isPinnedToTop = collection.pinToTop,
        )
    }
