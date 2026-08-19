package com.nuvio.app.core.contracts

/**
 * A replayable programme on a matched sports channel — the bounds the docked Live TV screen turns
 * into a catch-up session (the SAME session the guide's replays create: flag on, gates inherited,
 * dialect walk + winner memory instead of one hardcoded timeshift URL shape).
 *
 * Pure data with no fork dependency, so it lives in core/contracts and crosses the firewall freely:
 * RadarChannelMatcher (fork) produces it; App.kt / HomeScreen (shared) consume it as a navigation
 * payload without importing the radar subsystem.
 *
 * [contentId] stays the LIVE channel's id — the replay is a flag beside the live identity, never a
 * synthetic registry item wearing a single-dialect URL.
 */
data class SportsReplay(
    val contentId: String,
    val channelName: String,
    val logo: String?,
    val programmeTitle: String,
    val programmeStartMs: Long,
    val programmeEndMs: Long,
)
