package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamCategory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A category reply that was not a JSON array: a broken reply, never "this section is empty". */
internal class StalkerMalformedReplyException(message: String) : IllegalStateException(message)

internal object StalkerCategoryReply {
    /** An array parses as before (an empty array is a genuine empty list); anything else throws [StalkerMalformedReplyException]. */
    fun parse(reply: JsonElement): List<XtreamCategory> {
        val arr = reply as? JsonArray
            ?: throw StalkerMalformedReplyException("Stalker categories reply was not a JSON array")
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { obj ->
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (id == "*") return@mapNotNull null   // "*" = All; the hub adds its own "All"
            val title = ((obj["title"] as? JsonPrimitive) ?: (obj["name"] as? JsonPrimitive))?.contentOrNull.orEmpty()
            XtreamCategory(id, title)
        }
    }
}
