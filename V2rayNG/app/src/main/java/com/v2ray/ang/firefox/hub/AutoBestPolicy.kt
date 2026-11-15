package com.v2ray.ang.firefox.hub

/**
 * Auto Best Location — the decision of whether to move the exit to a better country, and to which.
 *
 * The rule the person asked for is the rule here: it never moves mid-download or mid-stream, it
 * respects a country lock and the favourites, and it only moves on a real problem (a disconnect or a
 * severe drop), not because another country is a few milliseconds faster. A cooldown keeps it from
 * hunting. It chooses a location; it never spends quota or switches accounts — that is the quota
 * guard's and the Hybrid Shield's job.
 *
 * Pure and side-effect-free so the decision can be tested without a phone or an engine.
 */
object AutoBestPolicy {

    /** A location the engine measured. */
    data class Candidate(val code: String, val pingMs: Long, val ok: Boolean)

    /** The person's standing preferences. */
    data class Config(
        val enabled: Boolean = false,
        /** When set, only this country may be used; Auto Best never leaves it. */
        val lockedCountry: String? = null,
        /** When non-empty, Auto Best prefers these countries. */
        val favorites: Set<String> = emptySet(),
        val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    )

    /** A snapshot of the situation the decision is made from. */
    data class Situation(
        /** The country in use ("" recommended, "fastest" the race winner). */
        val current: String,
        val currentOk: Boolean,
        val currentPingMs: Long,
        val candidates: List<Candidate>,
        /** True while a download or stream is actively using the tunnel. */
        val busy: Boolean,
        val sinceLastSwitchMs: Long,
        val quotaExhausted: Boolean,
    )

    const val DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L

    /** A drop is severe when the current ping is at least this much worse than the best (ms). */
    const val SEVERE_MARGIN_MS = 150L

    /** …and the current ping is at least this high (ms) — a fast link is never "bad". */
    const val SEVERE_FLOOR_MS = 400L

    /**
     * The country to move to, or null to stay where we are.
     *
     * Order of the guards matters: a busy tunnel, a cooldown, or a spent quota each veto a move before
     * any candidate is considered, so a good download is never interrupted by a "better" ping.
     */
    fun decide(config: Config, situation: Situation): String? {
        if (!config.enabled) return null
        // Quota is not a location problem: another country spends the same allowance. The quota guard
        // and the Hybrid Shield handle a spent account; Auto Best must not churn locations over it.
        if (situation.quotaExhausted) return null
        // Never move while the person is downloading or streaming.
        if (situation.busy) return null
        if (situation.sinceLastSwitchMs < config.cooldownMs) return null

        val pool = pool(config, situation.candidates).filter { it.ok && it.pingMs > 0 }
        if (pool.isEmpty()) return null
        val best = pool.minByOrNull { it.pingMs } ?: return null

        val currentCode = normalize(situation.current)
        // A disconnect is the clearest reason to move: take the best reachable candidate.
        if (!situation.currentOk) {
            return if (best.code != currentCode) best.code else null
        }
        // Otherwise only a severe drop justifies a move, and only to a different country.
        if (best.code == currentCode) return null
        val severe = situation.currentPingMs >= SEVERE_FLOOR_MS &&
            situation.currentPingMs - best.pingMs >= SEVERE_MARGIN_MS
        return if (severe) best.code else null
    }

    /** The candidates Auto Best may choose from: the lock, else the favourites, else all. */
    fun pool(config: Config, candidates: List<Candidate>): List<Candidate> {
        val lock = config.lockedCountry?.let { normalize(it) }
        if (!lock.isNullOrEmpty()) {
            return candidates.filter { normalize(it.code) == lock }
        }
        if (config.favorites.isNotEmpty()) {
            val favs = config.favorites.map { normalize(it) }.toSet()
            val preferred = candidates.filter { normalize(it.code) in favs }
            // Favourites guide the choice, but if none is reachable the rest are still allowed rather
            // than leaving the person stuck on a dead favourite.
            if (preferred.any { it.ok }) return preferred
        }
        return candidates
    }

    private fun normalize(code: String): String = code.trim().uppercase()
}
