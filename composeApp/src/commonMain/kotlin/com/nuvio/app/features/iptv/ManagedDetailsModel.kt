package com.nuvio.app.features.iptv

/** Channel / movie / series counts from local data only (null = not known for this source type). */
data class DetailsCounts(val channels: Int? = null, val movies: Int? = null, val series: Int? = null) {
    val isEmpty: Boolean get() = channels == null && movies == null && series == null

    /**
     * What the screen shows: a count is shown only when it is positive. A zero is "the index has not been built
     * yet" as often as "none", and "0 Movies / 0 Series" on a fresh playlist is wrong either way, so a zero
     * (or negative / unknown) count stays hidden, and the whole row with it when nothing is left.
     */
    fun hidingZeros(): DetailsCounts = DetailsCounts(
        channels = channels?.takeIf { it > 0 }, movies = movies?.takeIf { it > 0 }, series = series?.takeIf { it > 0 },
    )
}

/** What the header says about when the subscription ends. */
sealed interface ExpiryDisplay {
    /**
     * [days] whole days left (1 = ends within a day). [fraction] fills the thin bar and is non-null only in the last
     * [ManagedDetailsModel.BAR_WINDOW_DAYS] days (days / 30, so 30 days is a full bar); beyond that there is NO bar,
     * only the "N days left" text.
     */
    data class DaysLeft(val days: Int, val fraction: Float?) : ExpiryDisplay
    data object Expired : ExpiryDisplay
    /** The panel reports no end date (exp_date 0). No bar. */
    data object NeverExpires : ExpiryDisplay
    /** A portal that reports expiry as free text only (Stalker): shown as text, no days, no bar. */
    data class Text(val text: String) : ExpiryDisplay
    /** The provider's panel answered and reported no expiry ("Expiry not reported by this provider"). No bar. */
    data object NotReported : ExpiryDisplay
    /** The panel could not be asked (unreachable, refused): we do not know, and must not say the provider has none. */
    data object CheckFailed : ExpiryDisplay
}

/** "N of M connections" — only when the panel reports a maximum. */
data class ConnectionsDisplay(val active: Int, val max: Int)

/** Everything an action can be in the details screen, across platforms. */
enum class DetailsAction { CONTACT, CONTENT_CATEGORIES, HIDDEN, REMATCH, EDIT, TOGGLE_ENABLED, DETACH, REMOVE }

enum class DetailsGroupKind { PROVIDER, LIBRARY, REMOVE }

/** A labelled run of actions (TV/Apple TV shelves; the phone and desktop group the same actions their own way). */
data class DetailsGroup(val kind: DetailsGroupKind, val title: String, val actions: List<DetailsAction>)

/** What the details screen's header and groups show, with no UI in it. */
data class ManagedDetailsModel(
    val name: String,
    /** The address line under the name (a host, not a URL with credentials); null when none. */
    val addressLine: String?,
    /** "Managed by X" for a managed playlist, null otherwise (no ribbon). */
    val managedBy: String?,
    /** The provider's name, for "Contact X" / "Detach from X". */
    val providerName: String?,
    /** "updated <date>" source for the ribbon (ISO text), only when the server reported one. */
    val serviceUpdatedAt: String?,
    val expiry: ExpiryDisplay,
    val connections: ConnectionsDisplay?,
    val counts: DetailsCounts,
    val statusText: String?,
    /** Only the contacts the provider set. */
    val contacts: List<ContactLink>,
    val lockedServerLogin: Boolean,
    val enabled: Boolean,
    val groups: List<DetailsGroup>,
) {
    val isManaged: Boolean get() = managedBy != null

    companion object {
        /** The thin bar shows only the last 30 days of a subscription, shrinking to empty at the end date. */
        const val BAR_WINDOW_DAYS = 30
        private const val SECONDS_PER_DAY = 86_400L

        /**
         * [info] is non-null for a managed playlist. [accountInfo] is the live panel answer (null while
         * loading / unreachable / M3U). [nowEpochSec] is injected so the model tests without a clock.
         * [allowEdit] is false on TV and Apple TV (rename is dropped there; typing is costly).
         */
        fun build(
            account: XtreamAccount,
            info: ManagedInfo?,
            accountInfo: XtreamAccountInfo?,
            counts: DetailsCounts,
            nowEpochSec: Long,
            addressLine: String? = null,
            allowEdit: Boolean = true,
            /** The panel was asked and did not answer. Distinct from an answer that carries no expiry. */
            panelCheckFailed: Boolean = false,
        ): ManagedDetailsModel {
            val managed = info != null
            val contacts = info?.support?.links().orEmpty()
            val groups = buildList {
                if (managed && contacts.isNotEmpty()) {
                    add(DetailsGroup(DetailsGroupKind.PROVIDER, info!!.providerName.uppercase(), listOf(DetailsAction.CONTACT)))
                }
                add(
                    DetailsGroup(
                        DetailsGroupKind.LIBRARY,
                        "YOUR LIBRARY",
                        buildList {
                            add(DetailsAction.CONTENT_CATEGORIES)
                            add(DetailsAction.HIDDEN)
                            if (account.sourceType == SOURCE_TYPE_XTREAM) add(DetailsAction.REMATCH)
                            if (allowEdit) add(DetailsAction.EDIT)
                            add(DetailsAction.TOGGLE_ENABLED)
                        },
                    ),
                )
                add(
                    DetailsGroup(
                        DetailsGroupKind.REMOVE,
                        "REMOVE",
                        buildList {
                            if (ManagedPlaylistPolicy.showDetach(managed)) add(DetailsAction.DETACH)
                            add(DetailsAction.REMOVE)
                        },
                    ),
                )
            }
            return ManagedDetailsModel(
                name = PlaylistAddress.displayName(account.name),
                addressLine = addressLine,
                managedBy = info?.let(ManagedPlaylistPolicy::ownerLabel),
                providerName = info?.providerName,
                serviceUpdatedAt = info?.serviceUpdatedAt,
                expiry = expiryOf(accountInfo, nowEpochSec, panelCheckFailed),
                connections = accountInfo?.maxConnections?.takeIf { it > 0 }
                    ?.let { ConnectionsDisplay((accountInfo.activeConnections ?: 0).coerceAtLeast(0), it) },
                counts = counts.hidingZeros(),
                statusText = accountInfo?.status?.replaceFirstChar { it.uppercase() }
                    ?.let { if (accountInfo.isTrial) "$it · Trial" else it },
                contacts = contacts,
                lockedServerLogin = !ManagedPlaylistPolicy.showEditServerLogin(managed),
                enabled = account.enabled,
                groups = groups,
            )
        }

        /**
         * [panelCheckFailed]: the panel was asked and did not answer. With no [info] that is [ExpiryDisplay.CheckFailed]
         * ("Couldn't check expiry"), never "Expiry not reported by this provider", which is the provider's own answer
         * and only ever follows a SUCCESSFUL call. No [info] and no failure (still loading, or no panel to ask)
         * stays [ExpiryDisplay.NotReported].
         */
        fun expiryOf(info: XtreamAccountInfo?, nowEpochSec: Long, panelCheckFailed: Boolean = false): ExpiryDisplay {
            if (info == null) return if (panelCheckFailed) ExpiryDisplay.CheckFailed else ExpiryDisplay.NotReported
            info.expiresText?.takeIf { it.isNotBlank() }?.let { return ExpiryDisplay.Text(it) }
            val at = info.expiresAtEpochSec ?: return ExpiryDisplay.NotReported
            if (at == 0L) return ExpiryDisplay.NeverExpires
            val remaining = at - nowEpochSec
            if (remaining <= 0L) return ExpiryDisplay.Expired
            // Whole days, rounded up: 3 hours left reads "1 day left", never "0 days left" while it works.
            val days = ((remaining + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY).toInt()
            return ExpiryDisplay.DaysLeft(days, if (days > BAR_WINDOW_DAYS) null else days.toFloat() / BAR_WINDOW_DAYS)
        }
    }
}
