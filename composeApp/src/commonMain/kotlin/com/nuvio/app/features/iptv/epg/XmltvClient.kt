package com.nuvio.app.features.iptv.epg

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpStreamLines
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.iptv.content.EpgProgrammeRow
import com.nuvio.app.features.epg.EpgTelemetry
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches + parses an XMLTV guide for an M3U (or custom-EPG) playlist and serves now/next from it.
 *
 * Source resolution (in order): the account's explicit [XtreamAccount.epgUrl], else the M3U
 * `url-tvg` / `x-tvg-url` header captured into ingest_meta during the catalog ingest. The guide is
 * commonly a 50-100 MB `.xml`/`.xml.gz`, so it is streamed line-by-line through the bounded-memory
 * [XmltvStreamingParser] (which itself holds only one element) and chunk-inserted into
 * `epg_programmes`. Crucially the parse is FILTERED to the tvg-ids the playlist actually has (queried
 * up front) so a guide covering thousands of channels only stores rows for ours.
 *
 * now/next is then a tiny indexed range read via [nowNext]; [M3UClient.shortEpg] delegates here so the
 * hub live guide shows real programmes for M3U live instead of the empty list P2a shipped.
 */
object XmltvClient {

    private val log = Logger.withTag("XmltvClient")

    private const val CHUNK = 5_000
    /** EPG is refreshed on ingest and then roughly twice a day; older than this and a browse re-fetches. */
    private const val REFRESH_TTL_MS = 12L * 60 * 60 * 1000

    private val fetchLock = Mutex()
    private val fetching = mutableSetOf<String>()

    /**
     * Ensures a fresh-enough EPG for [acc] is stored, fetching + parsing the guide when none exists or
     * the stored copy is stale. No-ops (and de-dupes concurrent callers) when a fresh guide is present
     * or the playlist has no resolvable EPG source. Returns true when programmes are available after.
     */
    suspend fun ensureEpg(acc: XtreamAccount, force: Boolean = false): Boolean {
        val meta = IptvContentDb.epgMeta(acc.id)
        if (!force && meta != null && !isStale(meta.builtAtMs)) return meta.programmeCount > 0
        val source = resolveSource(acc) ?: return (meta?.programmeCount ?: 0) > 0
        val shouldRun = fetchLock.withLock {
            if (acc.id in fetching) false else { fetching.add(acc.id); true }
        }
        if (!shouldRun) return (IptvContentDb.epgMeta(acc.id)?.programmeCount ?: 0) > 0
        return try {
            refresh(acc, source).getOrDefault(0) > 0
        } finally {
            fetchLock.withLock { fetching.remove(acc.id) }
        }
    }

    /**
     * Streams the guide, filters programmes to this playlist's channels, chunk-inserts them, then
     * writes the EPG meta row LAST. Memory stays flat: at most one parsed chunk + the parser's single
     * open element are ever held; the 50-100 MB body is never fully in RAM.
     */
    internal suspend fun refresh(acc: XtreamAccount, source: EpgSource): Result<Int> = runCatching {
        val startedAtMs = TraktPlatformClock.nowEpochMs()
        // The allow-set: normalized tvg-ids of THIS playlist's channels. If the playlist has none,
        // there is nothing an EPG could attach to, so skip the (expensive) download entirely.
        val allow = IptvContentDb.distinctTvgIds(acc.id).map { normalizeChannelId(it) }.toHashSet()
        if (allow.isEmpty()) {
            IptvContentDb.beginEpg(acc.id)
            IptvContentDb.finishEpg(acc.id, 0)
            // SKIPPED, not ERROR: "this playlist has no tvg-ids" is a different support answer
            // from "the guide failed to download", and on screen they look identical.
            EpgTelemetry.ingestFinished(
                source = EpgTelemetry.Source.PLAYLIST_XMLTV,
                outcome = EpgTelemetry.Outcome.SKIPPED,
                durationMs = TraktPlatformClock.nowEpochMs() - startedAtMs,
            )
            return@runCatching 0
        }

        IptvContentDb.beginEpg(acc.id)
        val collector = EpgCollector(acc.id)
        // Bounded on the way IN ([XmltvIngestWindow]), not cleaned up afterwards: a feed carrying a
        // week of schedule for thousands of channels must never reach the disk in the first place
        // on a 1 GB box. The parse is streaming, so a refused row costs nothing beyond the parse
        // it already did.
        val nowMs = TraktPlatformClock.nowEpochMs()
        val parser = XmltvStreamingParser(
            keepChannelIds = allow,
            onProgramme = { p ->
                if (XmltvIngestWindow.keeps(p.startMs, p.endMs, nowMs)) collector.add(p)
            },
        )
        streamGuideLines(source, acc.userAgent(), acc.dnsProvider) { line ->
            parser.feed(line)
            parser.feed("\n")
        }
        parser.finish()
        collector.finish()
        IptvContentDb.finishEpg(acc.id, collector.count)
        log.i { "XMLTV ingest done acc=${acc.id} src=${source.kind} programmes=${collector.count} channels=${allow.size}" }
        EpgTelemetry.ingestFinished(
            source = EpgTelemetry.Source.PLAYLIST_XMLTV,
            outcome = if (collector.count > 0) EpgTelemetry.Outcome.OK else EpgTelemetry.Outcome.EMPTY,
            programmes = collector.count,
            channels = allow.size,
            channelsCovered = collector.channelsCovered,
            durationMs = TraktPlatformClock.nowEpochMs() - startedAtMs,
        )
        // A guide just landed, so every "this channel had nothing" verdict taken before it is
        // stale. Observed on the emulator (2026-08-18): two tiles asked 1s apart on a cold
        // playlist — the later one joined the in-flight ingest and got its programmes, the earlier
        // one checked an empty table, answered n=0, and the cooldown then pinned it on "No
        // information" for a further minute with the data already on disk beside it.
        com.nuvio.app.features.iptv.XtreamHubRepository.onGuideDataChanged()
        collector.count
    }.onFailure {
        log.w(it) { "XMLTV ingest failed for ${acc.id}" }
        EpgTelemetry.ingestFinished(
            source = EpgTelemetry.Source.PLAYLIST_XMLTV,
            outcome = EpgTelemetry.Outcome.ERROR,
            // Class only — a panel's message routinely quotes the request URL, credentials included.
            errorClass = it::class.simpleName,
        )
    }

    /**
     * now/next for one channel: the currently-airing programme + the following one, read from the
     * stored guide. Returns [] when the channel has no programmes (or the playlist has no EPG). This is
     * what [M3UClient.shortEpg] returns, so the hub's ensureEpg surfaces it exactly like Xtream's.
     */
    suspend fun nowNext(acc: XtreamAccount, tvgId: String, limit: Int = 4): List<XtreamProgram> {
        val key = normalizeChannelId(tvgId)
        if (key.isEmpty()) return emptyList()
        val now = TraktPlatformClock.nowEpochMs()
        val rows = IptvContentDb.epgAround(acc.id, key, now, limit)
        return selectNowNext(rows, now)
    }

    /** Resolve the EPG source for a playlist: explicit epgUrl wins, else the captured M3U url-tvg. */
    internal suspend fun resolveSource(acc: XtreamAccount): EpgSource? {
        acc.epgUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { return EpgSource(it, EpgSourceKind.EXPLICIT) }
        val tvg = IptvContentDb.ingestMeta(acc.id)?.epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        return tvg?.let { EpgSource(it, EpgSourceKind.URL_TVG) }
    }

    suspend fun clear(acc: XtreamAccount) = IptvContentDb.beginEpg(acc.id).also { IptvContentDb.finishEpg(acc.id, 0) }

    // --- internals ---------------------------------------------------------------

    /** Streams the guide's lines. Network only in P2 — a `file://` EPG source is a later upgrade. */
    private suspend fun streamGuideLines(source: EpgSource, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit) {
        httpStreamLines(source.url, userAgent, dnsProvider, onLine = onLine)
    }

    private class EpgCollector(private val playlistId: String) {
        private val buf = ArrayList<EpgProgrammeRow>(CHUNK)
        private val covered = HashSet<String>()
        var count = 0; private set

        /** Distinct channels that actually got rows — coverage, which every EPG report is about. */
        val channelsCovered: Int get() = covered.size

        fun add(p: XmltvProgramme) {
            covered.add(normalizeChannelId(p.channelId))
            // Store the NORMALIZED channel id so the now/next lookup (which normalizes the M3U tvg-id)
            // matches regardless of the two sources' casing/spacing.
            buf.add(EpgProgrammeRow(normalizeChannelId(p.channelId), p.startMs, p.endMs, p.title, p.desc))
            count++
            if (buf.size >= CHUNK) flush()
        }

        fun finish() = flush()

        private fun flush() {
            if (buf.isEmpty()) return
            runBlocking { IptvContentDb.insertEpgChunk(playlistId, buf) }
            buf.clear()
        }
    }

    private fun isStale(builtAtMs: Long): Boolean {
        if (builtAtMs <= 0) return false
        return TraktPlatformClock.nowEpochMs() - builtAtMs > REFRESH_TTL_MS
    }

    private const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
    private fun XtreamAccount.userAgent(): String = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
}

enum class EpgSourceKind { EXPLICIT, URL_TVG }

/** A resolved EPG source: the URL plus where it came from (for logging/UX). */
data class EpgSource(val url: String, val kind: EpgSourceKind)

/**
 * Maps EPG rows (start-ordered, all with end > now) to the now/next [XtreamProgram] list. Pure so the
 * now/next selection is unit-tested without the DB. The first row is "now-playing" only when the
 * current instant actually falls inside its window (start <= now < end) — otherwise the channel is
 * between programmes and the earliest upcoming one is next, with nothing marked now-playing.
 */
internal fun selectNowNext(rows: List<EpgProgrammeRow>, nowMs: Long): List<XtreamProgram> =
    rows.mapIndexed { index, r ->
        XtreamProgram(
            title = r.title,
            description = r.desc.orEmpty(),
            startMs = r.startMs,
            endMs = r.endMs,
            nowPlaying = index == 0 && r.startMs <= nowMs && nowMs < r.endMs,
        )
    }
