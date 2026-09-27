package com.nuvio.app.features.streams

/**
 * The streams sheet's sources in the order the list draws them: groups as given, then each group's
 * sources alphabetically (case-insensitive), streams in their own order. Shared by the list and by
 * [ResumeStreamPick] so the Resume action always plays the stream shown first.
 */
internal fun AddonStreamGroup.streamsBySourceInDisplayOrder(): List<Pair<String, List<StreamItem>>> {
    val bySource = streams.groupBy { stream -> stream.sourceName?.takeIf { it.isNotBlank() } ?: stream.addonName }
    return bySource.keys.sortedBy { it.lowercase() }.map { it to bySource.getValue(it) }
}

/**
 * Which stream the "Resume from …" action plays: the first one the list shows that can actually be
 * played. Null when nothing playable has loaded yet, in which case the banner is a caption, not a
 * button (B59a: it used to be a caption styled like a disabled button).
 */
internal object ResumeStreamPick {
    fun firstPlayable(groups: List<AddonStreamGroup>, isSelectable: (StreamItem) -> Boolean): StreamItem? =
        groups.asSequence()
            .flatMap { group -> group.streamsBySourceInDisplayOrder().asSequence().flatMap { it.second.asSequence() } }
            .firstOrNull(isSelectable)
}
