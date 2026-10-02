package com.nuvio.app.features.iptv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the right-hand pane of the desktop IPTV settings shows. */
internal enum class PaneMode { Detail, Add }

/** Within [PaneMode.Add]: the code field plus manual routes, or the preview of the held code. */
internal enum class AddStage { Entry, Preview }

/** Where a Detach/Remove popover hangs: off the list row, or off the details page's own button. */
internal enum class PopoverOrigin { Row, Detail }

/** A Detach/Remove awaiting its typed word. */
internal data class PendingDestructive(val action: DestructiveAction, val playlistKey: String, val origin: PopoverOrigin)

/** The playlist-list selection, the right pane's mode and any open confirmation (survives page navigation). */
internal data class IptvPaneState(
    val selectedKey: String? = null,
    val mode: PaneMode = PaneMode.Detail,
    val addStage: AddStage = AddStage.Entry,
    val pending: PendingDestructive? = null,
    /** The details page's name is being edited inline. */
    val renamingKey: String? = null,
)

/**
 * The desktop IPTV settings page's UI-session state: which row is selected, what the right pane shows, which
 * popover is open. The playlists themselves stay in [XtreamRepository]; the code flow in [SetupCodeController].
 * Pure state transitions, so they test without Compose.
 */
internal object IptvSettingsPane {
    private val _state = MutableStateFlow(IptvPaneState())
    val state: StateFlow<IptvPaneState> = _state.asStateFlow()

    fun select(playlistKey: String?) =
        _state.update { it.copy(selectedKey = playlistKey, mode = PaneMode.Detail, pending = null, renamingKey = null) }

    /** Opens the add pane on its code field. */
    fun openAdd() = _state.update { it.copy(mode = PaneMode.Add, addStage = AddStage.Entry, pending = null, renamingKey = null) }

    /** Opens the add pane straight on the preview of the held code (a link, a paste, back from sign-in). */
    fun openPreview() = _state.update { it.copy(mode = PaneMode.Add, addStage = AddStage.Preview, pending = null, renamingKey = null) }

    fun backToEntry() = _state.update { it.copy(addStage = AddStage.Entry) }

    fun closeAdd() = _state.update { it.copy(mode = PaneMode.Detail, addStage = AddStage.Entry) }

    fun askDestructive(action: DestructiveAction, playlistKey: String, origin: PopoverOrigin) =
        _state.update { it.copy(pending = PendingDestructive(action, playlistKey, origin)) }

    fun dismissDestructive() = _state.update { it.copy(pending = null) }

    fun startRename(playlistKey: String) =
        _state.update { it.copy(selectedKey = playlistKey, mode = PaneMode.Detail, renamingKey = playlistKey) }

    fun stopRename() = _state.update { it.copy(renamingKey = null) }

    /** Which row the selection rests on given the current list (valid while playlists come and go). */
    fun resolveSelection(accounts: List<XtreamAccount>, selectedKey: String?): String? = when {
        accounts.isEmpty() -> null
        selectedKey != null && accounts.any { it.id == selectedKey } -> selectedKey
        else -> accounts.first().id
    }

    /** The row to select after [removedKey] goes: the next one, else the previous, else none. */
    fun selectionAfterRemoval(accounts: List<XtreamAccount>, removedKey: String): String? {
        val index = accounts.indexOfFirst { it.id == removedKey }
        val rest = accounts.filterNot { it.id == removedKey }
        return (rest.getOrNull(index.coerceAtLeast(0)) ?: rest.lastOrNull())?.id
    }

    /** The key one step [delta] from [selectedKey] in [keys], stopping at the ends (arrow-key navigation). */
    fun step(keys: List<String>, selectedKey: String?, delta: Int): String? {
        if (keys.isEmpty()) return null
        val index = keys.indexOf(selectedKey)
        if (index < 0) return keys.first()
        return keys[(index + delta).coerceIn(0, keys.lastIndex)]
    }

    internal fun resetForTest() {
        _state.value = IptvPaneState()
    }
}
