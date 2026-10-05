package com.nuvio.app.core.contracts

/**
 * A feature that participates in profile sync (seam: sync firewall). Fork surfaces (IPTV accounts,
 * Radar follows) register here instead of the shared SyncManager naming them — upstream's sync
 * pipeline stays fork-clean. Registry refuses duplicate names (unambiguous), preserves registration
 * order (LinkedHashMap).
 */
interface SyncParticipant {
    val name: String

    /**
     * The `sync_invalidations.surface` values (Realtime) this participant pulls on. The shared
     * SyncManager routes any surface it doesn't know by name to the participants declaring it, so a
     * website edit to a fork surface lands live instead of waiting for the next full profile sync.
     */
    val realtimeSurfaces: Set<String> get() = emptySet()

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

    /** The participants that pull on Realtime [surface] (pure; [participants] defaults to the registry). */
    fun participantsForRealtimeSurface(
        surface: String,
        participants: List<SyncParticipant> = all,
    ): List<SyncParticipant> = participants.filter { surface in it.realtimeSurfaces }
}
