package com.nuvio.app.core.contracts

/**
 * A feature that participates in profile sync (seam: sync firewall). Fork surfaces (IPTV accounts,
 * Radar follows) register here instead of the shared SyncManager naming them — upstream's sync
 * pipeline stays fork-clean. Registry refuses duplicate names (unambiguous), preserves registration
 * order (LinkedHashMap).
 */
interface SyncParticipant {
    val name: String
    suspend fun pullFromServer(profileId: Int)
}

object SyncParticipantRegistry {
    private val byName = LinkedHashMap<String, SyncParticipant>()
    fun register(participant: SyncParticipant) {
        require(byName.put(participant.name, participant) == null) {
            "duplicate SyncParticipant: ${participant.name}"
        }
    }
    val all: List<SyncParticipant> get() = byName.values.toList()
}
