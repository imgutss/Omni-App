package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The «national network ⇄ normal» switch is a decision, so it is tested as one: no network, no
 * Android, no probe. What the tests pin down is exactly what the user would feel if it were wrong —
 * a tunnel refused on a network that works, or a connect that hangs on one that cannot.
 */
class NetworkEpochTest {

    private fun blocked(times: Int, from: EpochState = NetworkEpochSwitch.initial): EpochState =
        (1..times).fold(from) { state, _ -> NetworkEpochSwitch.observe(state, EdgeVerdict.BLOCKED) }

    private fun reachable(times: Int, from: EpochState = NetworkEpochSwitch.initial): EpochState =
        (1..times).fold(from) { state, _ -> NetworkEpochSwitch.observe(state, EdgeVerdict.REACHABLE) }

    @Test
    fun oneLostHandshakeDoesNotStrandThePhone() {
        val once = blocked(1)
        assertEquals(NetworkEpoch.UNKNOWN, once.epoch)
        assertEquals(TunnelDecision.START, once.plan.decision)
        assertEquals(1, once.blockedInARow)
    }

    @Test
    fun twoBlockedAnswersSwitchToTheNationalEpoch() {
        val twice = blocked(2)
        assertEquals(NetworkEpoch.NATIONAL, twice.epoch)
        assertEquals(TunnelDecision.STAY_DIRECT, twice.plan.decision)
        // Not zero: a national network is looked at again on its own schedule, not left alone.
        assertEquals(NetworkEpochSwitch.RECHECK_WHILE_NATIONAL_MS, twice.plan.recheckMs)
        assertTrue(NetworkEpochSwitch.shouldRecheck(twice))
    }

    @Test
    fun aNationalNetworkStaysNationalWhileEveryAnswerIsBlocked() {
        val many = blocked(5)
        assertEquals(NetworkEpoch.NATIONAL, many.epoch)
        assertEquals(5, many.blockedInARow)
        assertEquals(TunnelDecision.STAY_DIRECT, many.plan.decision)
    }

    @Test
    fun oneReachableAnswerBringsTheTunnelBack() {
        val back = reachable(1, from = blocked(3))
        assertEquals(NetworkEpoch.NORMAL, back.epoch)
        assertEquals(TunnelDecision.START, back.plan.decision)
        assertEquals(0, back.blockedInARow)
        assertEquals(0L, back.plan.recheckMs)
        assertFalse(NetworkEpochSwitch.shouldRecheck(back))
    }

    @Test
    fun anInconclusiveProbeChangesNothing() {
        val before = blocked(1)
        val after = NetworkEpochSwitch.observe(before, EdgeVerdict.INCONCLUSIVE)
        assertEquals(before, after)

        val national = blocked(2)
        assertEquals(national, NetworkEpochSwitch.observe(national, EdgeVerdict.INCONCLUSIVE))
    }

    @Test
    fun aPhoneWithNoRouteIsWaitedForNotCalledNational() {
        val state = NetworkEpochSwitch.observe(NetworkEpochSwitch.initial, EdgeVerdict.NO_NETWORK)
        assertEquals(NetworkEpoch.UNKNOWN, state.epoch)
        val plan = NetworkEpochSwitch.plan(state, noNetwork = true)
        assertEquals(TunnelDecision.WAIT_FOR_NETWORK, plan.decision)
        assertEquals(NetworkEpochSwitch.RECHECK_WHILE_NATIONAL_MS, plan.recheckMs)
    }

    @Test
    fun aNetworkThatCameBackIsNormalNotMerelyUnknown() {
        val back = reachable(1, from = blocked(4))
        assertEquals(NetworkEpoch.NORMAL, back.epoch)
        assertEquals(1, back.reachableInARow)
        assertFalse(NetworkEpochSwitch.shouldRecheck(back))
    }

    @Test
    fun onlyTheTwoDocumentedExitCodesCountAsAnAnswer() {
        assertEquals(EdgeVerdict.REACHABLE, EdgeVerdict.classify(0, timedOut = false))
        assertEquals(EdgeVerdict.BLOCKED, EdgeVerdict.classify(3, timedOut = false))
        // A killed client, a missing binary, a crash: no opinion about the network.
        listOf(1, 2, 127, 137, -1).forEach { code ->
            assertEquals(EdgeVerdict.INCONCLUSIVE, EdgeVerdict.classify(code, timedOut = false))
        }
        assertEquals(EdgeVerdict.INCONCLUSIVE, EdgeVerdict.classify(0, timedOut = true))
    }
}
