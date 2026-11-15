package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The switch itself: it keeps the last [EpochState] and answers, for one connect attempt, whether a
 * GUTS tunnel is worth opening on this network.
 *
 * It is deliberately a gate rather than a rewrite of the routing: when the phone is on a national
 * network the tunnel is not opened at all, so the phone stays on the plain network, where domestic
 * services still work, and nothing pretends to be connected to something that cannot carry a byte.
 *
 * The state is process-wide because the question is about the phone's network, not about one
 * profile; it is re-read by every connect attempt and by [EdgeProbe]'s own rechecks.
 **/
object NetworkGate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** Half an hour of one-minute looks: long enough to outlive an outage, short enough to forget. */
    private const val MAX_RECHECKS = 30

    @Volatile
    private var state: EpochState = NetworkEpochSwitch.initial

    /** The last known state, for the UI to explain itself with. */
    fun state(): EpochState = state

    /**
     * Asks the network and calls [onDecided] with the plan, off the caller's thread.
     *
     * The callback runs on the main thread because every caller of this gate is about to start or
     * refuse a service, which is a main-thread decision.
     */
    fun begin(
        context: Context,
        core: GutsCore,
        onDecided: (EpochPlan) -> Unit,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            // A gate that fails must fail open: a bug here may not be the reason a working tunnel
            // stays closed.
            val plan = runCatching { decide(appContext, core) }
                .getOrElse { error ->
                    LogUtil.w(AppConfig.TAG, "NetworkGate: the gate itself failed: ${error.message}")
                    EpochPlan(TunnelDecision.START, 0L)
                }
            LogUtil.i(
                AppConfig.TAG,
                "NetworkGate: ${plan.decision.name.lowercase()} on a ${state.epoch.name.lowercase()} network",
            )
            withContext(Dispatchers.Main.immediate) { onDecided(plan) }
        }
    }

    /**
     * One look at the network, folded into the state.
     *
     * A phone with no route at all is answered without a probe: there is nothing to ask, and the
     * tunnel is not the thing that is missing.
     */
    suspend fun decide(context: Context, core: GutsCore): EpochPlan {
        if (!hasNetwork(context)) {
            mutex.withLock { state = state.copy(reachableInARow = 0) }
            return NetworkEpochSwitch.plan(state, noNetwork = true)
        }
        val verdict = EdgeProbe.probe(context, core)
        val next = mutex.withLock {
            state = NetworkEpochSwitch.observe(state, verdict)
            state
        }
        return NetworkEpochSwitch.plan(next, noNetwork = false)
    }

    /** Folds an answer the caller obtained elsewhere (a recheck, a test) into the state. */
    suspend fun observe(verdict: EdgeVerdict): EpochState = mutex.withLock {
        state = NetworkEpochSwitch.observe(state, verdict)
        state
    }

    /**
     * Looks again on this phone's own schedule while it waits for the gateway, and calls
     * [onReachable] once the Google edge answers again.
     *
     * This is the «normal» half of the switch: the user asked for a tunnel, the network refused it,
     * so the app keeps asking instead of making them tap connect every minute. It stops on its own
     * after [MAX_RECHECKS] looks, because a phone that has been on a national network for hours is
     * not going to be helped by a background loop nobody remembers.
     */
    fun watch(context: Context, core: GutsCore, everyMs: Long, onReachable: () -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            var round = 0
            while (round < MAX_RECHECKS) {
                round++
                delay(if (everyMs > 0) everyMs else NetworkEpochSwitch.RECHECK_WHILE_NATIONAL_MS)
                val plan = decide(appContext, core)
                if (plan.decision == TunnelDecision.START) {
                    LogUtil.i(AppConfig.TAG, "NetworkGate: the edge answers again after $round looks")
                    withContext(Dispatchers.Main.immediate) { onReachable() }
                    return@launch
                }
            }
            LogUtil.i(AppConfig.TAG, "NetworkGate: stopped looking after $round rounds")
        }
    }

    /** Whether the phone has any network at all; it says nothing about whether Google is reachable. */
    private fun hasNetwork(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        manager.activeNetwork != null
    }.getOrDefault(true)
}
