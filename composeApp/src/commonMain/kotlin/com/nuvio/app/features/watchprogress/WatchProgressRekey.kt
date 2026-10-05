package com.nuvio.app.features.watchprogress

/**
 * B64 — the pure write set of a progress re-key ([WatchProgressRepository.rekeyEntries]): which local
 * entries go, which moved entries are stored (their storage key re-derived from the new ids), and
 * which old rows are deleted on the server.
 */
internal object WatchProgressRekey {

    class Plan(
        /** Local entries the re-key moves away (removed by their storage key). */
        val removed: List<WatchProgressEntry>,
        /** Moved entries to store and push, in order. */
        val upserts: List<WatchProgressEntry>,
        /** Old rows to delete on the server. */
        val serverDeletes: List<WatchProgressEntry>,
    ) {
        val isEmpty: Boolean get() = removed.isEmpty()
    }

    /**
     * Device pass T1: a re-key can meet the moved entry already stored — an old-id row a remote pull
     * brought back after the first re-key. One entry per storage key survives, the FRESHER one (the old
     * copy used to overwrite newer progress). And a row whose storage key the re-key keeps (an episode
     * renamed under the same `{series}_s{S}e{E}`) is not deleted on the server: that delete raced the
     * push of the very same row and could land second.
     */
    fun plan(local: Collection<WatchProgressEntry>, rewrite: (WatchProgressEntry) -> WatchProgressEntry?): Plan {
        val changes = local.mapNotNull { entry ->
            rewrite(entry)?.let { entry to it.copy(lastSourceUrl = null, progressKey = null).withResolvedProgressKey() }
        }
        if (changes.isEmpty()) return Plan(emptyList(), emptyList(), emptyList())
        val removedKeys = changes.mapTo(HashSet()) { it.first.resolvedProgressKey() }
        // What each storage key holds afterwards: the entries the re-key leaves alone, then each moved one
        // where it is fresher than what is already there.
        val held = HashMap<String, WatchProgressEntry>()
        local.forEach { entry -> entry.resolvedProgressKey().takeIf { it !in removedKeys }?.let { held[it] = entry } }
        val upserts = LinkedHashMap<String, WatchProgressEntry>()
        changes.forEach { (_, moved) ->
            val key = moved.resolvedProgressKey()
            val current = held[key]
            if (current == null || moved.isFresherThan(current)) {
                held[key] = moved
                upserts[key] = moved
            }
        }
        return Plan(
            removed = changes.map { it.first },
            upserts = upserts.values.toList(),
            serverDeletes = changes.map { it.first }.filter { it.resolvedProgressKey() !in held },
        )
    }
}
