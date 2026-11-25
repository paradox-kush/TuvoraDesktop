package com.nuvio.app.core.telemetry.api

/**
 * The privacy rules of the playback telemetry contract as code, so the tests can build every payload and fail on a
 * violation. PostHog's own layer redacts properties named `code`, `state`, `url` and `token`; the contract also bans
 * `host`, `user`, `password`, `mac` and `key`. Every string property must be a value from a closed enum defined in code:
 * no URLs, hosts, IPs, paths, channel/playlist/provider names, credentials or error message text.
 */
object PlaybackTelemetryPrivacy {
    val FORBIDDEN_KEYS: Set<String> = setOf(
        "code", "state", "url", "token", "host", "user", "password", "mac", "key",
        "uri", "href", "secret", "cookie", "authorization", "api_key", "apikey", "ip", "path",
    )

    /** Closed value set per string property the playback telemetry adds. */
    val ENUMS: Map<String, Set<String>> = mapOf(
        "kind" to PlaybackTelemetryKeys.KINDS,
        "prev_kind" to PlaybackTelemetryKeys.KINDS,
        "engine" to PlaybackTelemetryKeys.ENGINES,
        "prev_engine" to PlaybackTelemetryKeys.ENGINES,
        "from_engine" to PlaybackTelemetryKeys.ENGINES,
        "to_engine" to PlaybackTelemetryKeys.ENGINES,
        "trigger" to PlaybackTelemetryKeys.FALLBACK_TRIGGERS,
        "outcome" to (PlaybackTelemetryKeys.STALL_OUTCOMES + PlaybackTelemetryKeys.TEARDOWN_OUTCOMES),
        "end_reason" to PlaybackTelemetryKeys.END_REASONS,
        "ext_class" to PlaybackTelemetryKeys.EXT_CLASSES,
        "content_type_class" to PlaybackTelemetryKeys.CONTENT_TYPE_CLASSES,
        "source_type" to PlaybackTelemetryKeys.SOURCE_TYPES,
        "res_class" to PlaybackTelemetryBuckets.RES_CLASSES.toSet(),
        "http_class" to PlaybackTelemetryKeys.HTTP_CLASSES,
        "ram_class" to PlaybackTelemetryBuckets.RAM_CLASSES.toSet(),
        "duration_s_bucket" to PlaybackTelemetryBuckets.DURATION_S.toSet(),
    )

    private val urlLike = Regex("""(?i)([a-z][a-z0-9+.\-]*://|www\.|\b[a-z0-9\-]+(\.[a-z0-9\-]+)+\.[a-z]{2,}\b|\b\d{1,3}(\.\d{1,3}){3}\b)""")

    fun isForbiddenKey(key: String): Boolean {
        val k = key.lowercase().replace('-', '_')
        return k in FORBIDDEN_KEYS || k.endsWith("_url") || k.endsWith("_uri") || k.endsWith("_host") || k.endsWith("_token") ||
            k.endsWith("_password") || k.endsWith("_key")
    }

    /**
     * Everything wrong with [props], as readable lines; empty means clean. A `n_<count key>` property must be a valid
     * count key with an Int/Long value. Any other string value must be in its enum ([ENUMS]); a string property with no
     * enum is a violation unless its name is in [freeStringKeys] (ids and versions the caller vouches for).
     */
    fun violations(props: Map<String, Any?>, freeStringKeys: Set<String> = emptySet()): List<String> = buildList {
        for ((key, value) in props) {
            if (isForbiddenKey(key)) add("forbidden property name: $key")
            if (key.startsWith("n_")) {
                if (!PlaybackTelemetryKeys.isValid(key.removePrefix("n_"))) {
                    // Counts that predate this contract (`n_playback_started_exo_live`) use `_` joins and no `:`; only
                    // colon keys are in this contract, so a colon key that does not validate is the violation.
                    if (':' in key) add("count key outside the closed vocabulary: $key")
                }
                if (value !is Int && value !is Long) add("count $key is not a number")
                continue
            }
            if (value is String) {
                val allowed = ENUMS[key]
                when {
                    allowed != null -> if (value !in allowed) add("$key=$value is not in its enum")
                    key in freeStringKeys -> Unit
                    else -> add("string property $key has no closed enum")
                }
                if (urlLike.containsMatchIn(value) && key !in freeStringKeys) add("$key looks like a URL, host or IP")
            }
        }
    }
}
