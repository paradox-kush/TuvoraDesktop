package com.nuvio.app.features.iptv.epg

import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.XtreamKind
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.channelNameRules
import com.nuvio.app.features.iptv.content.EpgGuideChannelRow
import com.nuvio.app.features.iptv.content.IptvContentDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * F14 — "Choose guide channel": the state holder behind the picker. The long-press menu of a live
 * channel calls [open]; [GuideChannelPickerHost] (placed once per screen that shows the menu)
 * renders the dialog. All I/O lives here, the composable only renders [State].
 */
internal object GuideChannelPickerController {

    data class Request(val account: XtreamAccount, val streamId: Int, val channelName: String)

    /** [current] = the guide id the channel reads now, and whether the user picked it. */
    data class State(
        val entityId: String?,
        val currentGuideId: String?,
        val currentIsManual: Boolean,
        val options: List<EpgGuideChannelRow>,
        /** False when the playlist has no guide channels on this device yet (no source / not ingested). */
        val hasGuide: Boolean,
    )

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    /** True for a live IPTV channel whose playlist is on this device — when the menu offers the row. */
    fun canOpen(contentId: String): Boolean {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return false
        return parsed.kind == XtreamKind.LIVE &&
            XtreamRepository.uiState.value.accounts.any { it.id == parsed.accountId }
    }

    fun open(contentId: String, channelName: String) {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return
        if (parsed.kind != XtreamKind.LIVE) return
        val sid = parsed.id.toIntOrNull() ?: return
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == parsed.accountId } ?: return
        _request.value = Request(account, sid, channelName)
    }

    fun close() { _request.value = null }

    suspend fun load(req: Request, query: String): State {
        val acc = req.account
        val partition = XmltvClient.partitionOf(acc)
        val entity = runCatching { XmltvClient.entityIdFor(acc, req.streamId) }.getOrNull()
        val manual = entity?.let { EpgOverrides.forPlaylist(acc.id)[it] }
        val autoKey = runCatching { IptvContentDb.epgGuideKey(partition, req.streamId) }.getOrNull()
        val q = query.ifBlank { GuidePickerQuery.seed(req.channelName, acc) }
        val options = runCatching { XmltvClient.guideChannels(acc, q) }.getOrDefault(emptyList())
        val hasGuide = options.isNotEmpty() ||
            runCatching { XmltvClient.guideChannels(acc, "", limit = 1).isNotEmpty() }.getOrDefault(false)
        return State(
            entityId = entity,
            currentGuideId = manual ?: autoKey?.let { STORED_PREFIX.replace(it, "") },
            currentIsManual = manual != null,
            options = options,
            hasGuide = hasGuide,
        )
    }

    /** [EpgSourcePlan.storedKey]'s `sN:` prefix for sources after the first. */
    private val STORED_PREFIX = Regex("^s\\d+:")

    /** [row] null = back to automatic matching. */
    fun pick(req: Request, entityId: String, row: EpgGuideChannelRow?) {
        EpgOverrides.set(req.account, entityId, row?.guideId, row?.name)
        close()
    }
}

/** The picker's first search: the channel's own cleaned name, so the likely answer is on screen. */
internal object GuidePickerQuery {
    fun seed(channelName: String, acc: XtreamAccount): String {
        val cleaned = com.nuvio.app.features.epg.ChannelNameCleaner.clean(channelName, acc.channelNameRules())
        // The first word narrows a 30k-channel guide to a screenful without hiding the answer
        // behind exact spelling ("Sky Sports F1" -> "Sky").
        return cleaned.substringBefore(' ').takeIf { it.length >= 2 } ?: cleaned
    }
}

