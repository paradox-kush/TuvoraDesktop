package com.nuvio.app.features.watched

/** B64 — the pure server side of a watched-marks re-key ([WatchedRepository.rekeyItems]). */
internal object WatchedRekey {

    /**
     * The old marks to delete on the server once [moved] are pushed — not one whose server row (content
     * id + season + episode) a moved mark still is: an episode renamed only by its video id. That delete
     * raced the push of the same row and could land second, dropping the mark everywhere.
     */
    fun serverDeletes(old: List<WatchedItem>, moved: List<WatchedItem>): List<WatchedItem> {
        val kept = moved.mapTo(HashSet()) { Triple(it.id, it.season, it.episode) }
        return old.filterNot { Triple(it.id, it.season, it.episode) in kept }
    }
}
