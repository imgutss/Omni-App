package com.v2ray.ang.core

/**
 * Android's `VpnService.protect` for whoever needs it, while the VPN runs.
 *
 * A VpnService may only be reached through its own process, and the account engine is started from two
 * places — the screen, before any connection exists, and the daemon, while one does. Both must protect
 * the sockets the engine opens, so both ask here rather than each holding half of the `ServiceControl`.
 *
 * Nothing is registered while no VPN runs (proxy mode, or the app not connected): then there is no tunnel
 * for the engine's own traffic to fall into, and a socket needs no protection. A callback that throws is
 * treated as a refusal, because a socket the VPN did not protect must not be handed back to the engine as
 * if it had been.
 */
object CoreVpnProtect {

    /** The current owner of the VpnService, set while it runs; null when nothing is carrying traffic. */
    @Volatile
    private var protector: ((Int) -> Boolean)? = null

    /** Installs the VpnService's protect for as long as it runs. */
    fun attach(protect: ((Int) -> Boolean)?) {
        protector = protect
    }

    /** Ends protection: the VPN stopped, so nothing needs taking out of it any more. */
    fun detach() {
        protector = null
    }

    /** Whether a VPN is carrying this app's traffic right now. */
    fun active(): Boolean = protector != null

    /** The callback the engines are started with, or null when there is no VPN to protect a socket from. */
    fun current(): ((Int) -> Boolean)? = protector?.let { protect ->
        { descriptor ->
            runCatching { protect(descriptor) }.getOrDefault(false)
        }
    }
}
