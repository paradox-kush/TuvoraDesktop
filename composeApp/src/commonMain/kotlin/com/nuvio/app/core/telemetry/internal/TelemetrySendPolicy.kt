package com.nuvio.app.core.telemetry.internal

/** What happens to an analytics event: sent now, kept in the local log only, or counted for `session_health`. */
internal enum class SendDecision { SEND, RECORD_ONLY, COUNT }

internal object TelemetrySendPolicy {
    private val recordOnlyEvents = setOf(
        "clean_playback_diagnostic", "\$screen", "\$rageclick", "\$autocapture",
    )
    private val countedWhenOk = setOf("iptv_index_build", "epg_resolve", "epg_ingest", "epg_mapping", "iptv_load")
    private val countedAlways = setOf("playback_started")
    private val okOutcomes = setOf("ok", "loaded", "empty", "success", "succeeded")

    /** Exit reasons that are an abnormal end the user could feel, sent individually. */
    private val individualExitReasons = setOf(
        "crash", "native_crash", "anr", "unexpected_process_exit", "initialization_failure",
        "signaled", "dependent_package_died", "user_requested", "foreground_kill",
    )

    fun decide(event: String, props: Map<String, Any>): SendDecision = when {
        event in recordOnlyEvents -> SendDecision.RECORD_ONLY
        event in countedAlways -> SendDecision.COUNT
        event in countedWhenOk ->
            if (props["outcome"]?.toString()?.lowercase() in okOutcomes) SendDecision.COUNT else SendDecision.SEND
        event == "app_exit" -> {
            val reason = props["reason"]?.toString()?.lowercase()
            val cachedKill = props["importance"]?.toString()?.lowercase() in setOf("cached", "background")
            if (reason != null && reason in individualExitReasons && !cachedKill) SendDecision.SEND
            else if (cachedKill || reason == "low_memory") SendDecision.COUNT
            else SendDecision.SEND
        }
        else -> SendDecision.SEND
    }

    /** Key under which a counted event is tallied in `session_health`. */
    fun healthKey(event: String, props: Map<String, Any>): String {
        val outcome = props["outcome"]?.toString()?.let(DiagTrailPolicy::cleanCode)
        val surface = (props["surface"] ?: props["source"])?.toString()?.let(DiagTrailPolicy::cleanCode)
        return listOfNotNull(event, surface, outcome).joinToString(".")
    }
}
