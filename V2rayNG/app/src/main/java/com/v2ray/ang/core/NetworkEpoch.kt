package com.v2ray.ang.core

/**
 * The automatic «national network ⇄ normal» switch, as a pure decision.
 *
 * On a national network (the state where the country's international gateway is cut) the Google
 * edge that carries every GUTS call is simply not there: the tunnel cannot work, no matter how many
 * times it is started. Fighting that state costs the user a hanging connect, an app that looks
 * broken, and a log full of failures that mean nothing.
 *
 * So the phone asks one cheap question before it tries — does this network reach the Google edge at
 * all — and the answer decides what to do:
 *
 * - reachable → [NetworkEpoch.NORMAL]: open the tunnel exactly as before;
 * - blocked → [NetworkEpoch.NATIONAL]: stay on the plain network (where domestic services still
 *   work) and look again every [RECHECK_WHILE_NATIONAL_MS] instead of hammering a dead door;
 * - no route, no DNS → [EdgeVerdict.INCONCLUSIVE]/[EdgeVerdict.NO_NETWORK]: change nothing, because
 *   a phone without a network says nothing about the edge.
 *
 * One blocked answer is not enough to switch: a single lost handshake would otherwise strand the
 * user on the plain network. [BLOCKED_ROUNDS_TO_SWITCH] blocked answers in a row switch; one
 * reachable answer switches back, because a working tunnel is never the worse state.
 */
enum class EdgeVerdict {
    /** The Google edge carried a fronted call: the network reaches Google. */
    REACHABLE,

    /** Every address was tried and none answered: the gateway is cut, or Google is blocked. */
    BLOCKED,

    /** The phone has no usable route at all. Says nothing about Google. */
    NO_NETWORK,

    /** The probe could not finish (crash, timeout, unreadable answer). Change nothing. */
    INCONCLUSIVE,
    ;

    companion object {
        /** Exit code of `gutsclient -probe` when an edge answered. */
        const val EXIT_REACHABLE = 0

        /** Exit code of `gutsclient -probe` when no edge answered. */
        const val EXIT_BLOCKED = 3

        /**
         * Maps a finished probe to a verdict. Only the two codes the client documents count as an
         * answer; anything else — a killed process, a missing binary, a timeout — is inconclusive
         * rather than a guess about the network.
         */
        fun classify(exitCode: Int, timedOut: Boolean): EdgeVerdict = when {
            timedOut -> INCONCLUSIVE
            exitCode == EXIT_REACHABLE -> REACHABLE
            exitCode == EXIT_BLOCKED -> BLOCKED
            else -> INCONCLUSIVE
        }
    }
}

/** Which network the phone is on, as far as this tunnel is concerned. */
enum class NetworkEpoch {
    /** No answer yet: try the tunnel, the client itself will say what is wrong. */
    UNKNOWN,

    /** The Google edge answers: the tunnel is worth opening. */
    NORMAL,

    /** The international gateway is cut: open nothing, keep the phone on the plain network. */
    NATIONAL,
}

/** What the phone should do with the next connect attempt. */
enum class TunnelDecision {
    /** Open the tunnel. */
    START,

    /** Do not open the tunnel: on this network it cannot carry a single byte. */
    STAY_DIRECT,

    /** Do not open the tunnel and say that the phone has no network at all. */
    WAIT_FOR_NETWORK,
}

/** A decision plus how long to wait before asking the network again. */
data class EpochPlan(val decision: TunnelDecision, val recheckMs: Long)

/**
 * The counters behind [NetworkEpoch]: what the last answers said, and in which streak.
 *
 * Immutable so a caller can keep it in a view model or in shared preferences without worrying about
 * who is reading it, and so the tests can state a history in one line.
 */
data class EpochState(
    val epoch: NetworkEpoch = NetworkEpoch.UNKNOWN,
    val blockedInARow: Int = 0,
    val reachableInARow: Int = 0,
) {
    val plan: EpochPlan
        get() = when (epoch) {
            NetworkEpoch.NORMAL -> EpochPlan(TunnelDecision.START, 0L)
            NetworkEpoch.NATIONAL -> EpochPlan(TunnelDecision.STAY_DIRECT, NetworkEpochSwitch.RECHECK_WHILE_NATIONAL_MS)
            NetworkEpoch.UNKNOWN -> EpochPlan(TunnelDecision.START, 0L)
        }
}

object NetworkEpochSwitch {
    /** How many blocked answers in a row it takes to call the network national. */
    const val BLOCKED_ROUNDS_TO_SWITCH = 2

    /** How many reachable answers in a row it takes to go back to normal. */
    const val REACHABLE_ROUNDS_TO_RETURN = 1

    /** How often to look again while the phone waits for the gateway to come back. */
    const val RECHECK_WHILE_NATIONAL_MS = 60_000L

    val initial: EpochState = EpochState()

    /**
     * The next state after one answer.
     *
     * A blocked streak that has not reached [BLOCKED_ROUNDS_TO_SWITCH] leaves the epoch alone: the
     * second-blocked-round rule has to be able to survive one bad round in between, or a busy
     * network would keep the user switching back and forth.
     */
    fun observe(state: EpochState, verdict: EdgeVerdict): EpochState = when (verdict) {
        EdgeVerdict.REACHABLE -> {
            val streak = state.reachableInARow + 1
            if (state.epoch == NetworkEpoch.NORMAL || streak >= REACHABLE_ROUNDS_TO_RETURN) {
                EpochState(NetworkEpoch.NORMAL, 0, streak)
            } else {
                state.copy(reachableInARow = streak, blockedInARow = 0)
            }
        }

        EdgeVerdict.BLOCKED -> {
            val streak = state.blockedInARow + 1
            if (state.epoch == NetworkEpoch.NATIONAL || streak >= BLOCKED_ROUNDS_TO_SWITCH) {
                EpochState(NetworkEpoch.NATIONAL, streak, 0)
            } else {
                state.copy(blockedInARow = streak, reachableInARow = 0)
            }
        }

        // A phone with no route says nothing about the gateway, and a probe that could not finish
        // says nothing at all: neither may count towards a switch in either direction.
        EdgeVerdict.NO_NETWORK -> state.copy(reachableInARow = 0)
        EdgeVerdict.INCONCLUSIVE -> state
    }

    /**
     * What to do right now, given the state and whether the phone has a network at all.
     *
     * [noNetwork] comes from the platform, so a phone in flight mode reads as "wait" instead of
     * "the gateway is cut".
     */
    fun plan(state: EpochState, noNetwork: Boolean): EpochPlan =
        if (noNetwork) EpochPlan(TunnelDecision.WAIT_FOR_NETWORK, RECHECK_WHILE_NATIONAL_MS) else state.plan

    /**
     * Whether the app should look at the network again on its own: while the phone is national, or
     * while it is unknown and a previous answer was blocked.
     */
    fun shouldRecheck(state: EpochState): Boolean =
        state.epoch == NetworkEpoch.NATIONAL ||
            (state.epoch == NetworkEpoch.UNKNOWN && state.blockedInARow > 0)
}
