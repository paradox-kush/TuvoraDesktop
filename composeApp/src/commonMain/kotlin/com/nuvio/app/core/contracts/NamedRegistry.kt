package com.nuvio.app.core.contracts

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * Registration-ordered, copy-on-write registry that REFUSES duplicate names - the precedent set by
 * [SyncParticipantRegistry] / [LocalStateCleanerRegistry], shared by every plural source port
 * (stream, meta, search, classifier, own-source predicates, home sections, session reporters).
 *
 * Registration is PROCESS-INIT state (FeatureContributions / FeatureWiring), so a duplicate is a
 * wiring bug and fails loudly at startup rather than silently shadowing a source. Reads are
 * lock-free and allocation-free: every registration publishes a new immutable snapshot, so a hot
 * path (a classifier asked per list item) never copies anything.
 */
class NamedRegistry<T : Any>(private val kind: String) {
    private class Snapshot<T>(val names: List<String>, val items: List<T>)

    private val snapshot = atomic(Snapshot<T>(emptyList(), emptyList()))

    fun register(name: String, item: T) {
        require(name.isNotBlank()) { "$kind name must not be blank" }
        snapshot.update { current ->
            require(name !in current.names) { "duplicate $kind: $name" }
            Snapshot(current.names + name, current.items + item)
        }
    }

    /** Entries in registration order. */
    val all: List<T> get() = snapshot.value.items

    val names: List<String> get() = snapshot.value.names

    val isEmpty: Boolean get() = snapshot.value.items.isEmpty()

    /** Tests put the process back as they found it. */
    internal fun resetForTest() {
        snapshot.value = Snapshot(emptyList(), emptyList())
    }
}
