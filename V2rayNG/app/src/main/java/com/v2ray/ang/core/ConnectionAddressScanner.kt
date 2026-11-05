package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Full-profile HTTP measurements. Neither the origin nor any transport/TLS field is rewritten. */
internal class ConnectionAddressScanner {
    private val batch = "address-scan-${UUID.randomUUID()}"
    private val cancelled = AtomicBoolean(false)
    data class Result(val ip: String, val delay: Long)

    fun cancel() {
        cancelled.set(true)
        CoreNativeManager.cancelOutboundDelays(batch)
    }

    suspend fun scan(context: Context, profile: ProfileItem, input: String,
                     progress: (Int, Int) -> Unit): Result? = withContext(Dispatchers.IO) {
        require(profile.configType == EConfigType.VLESS || profile.configType == EConfigType.TROJAN)
        check(!CoreServiceManager.isRunning()) { "Stop VPN before scanning" }
        val supplied = input.split(',', ';', '\n', ' ', '\t').map { it.trim().removeSurrounding("[", "]") }
            .filter { Utils.isPureIpAddress(it) }
        val origins = (listOfNotNull(profile.server, profile.sni) + profile.host.orEmpty().split(','))
            .map { it.trim() }.filter { it.isNotEmpty() && it.none { ch -> ch.isWhitespace() || ch in "/?#@" } }
            .distinct().take(4)
        val resolved = origins.flatMap { origin ->
            ensureActive()
            runCatching {
                if (Utils.isPureIpAddress(origin)) listOf(origin)
                else InetAddress.getAllByName(origin).mapNotNull { it.hostAddress }
            }.getOrDefault(emptyList())
        }
        val candidates = (supplied + resolved).distinct().take(32)
        if (candidates.isEmpty()) return@withContext null
        CoreNativeManager.initCoreEnv(context)
        val done = AtomicInteger()
        val permits = Semaphore(4)
        try {
            coroutineScope {
                candidates.map { ip -> async {
                    permits.withPermit {
                        ensureActive()
                        if (cancelled.get()) return@withPermit null
                        val candidate = candidate(profile, ip)
                        val config = CoreConfigManager.getV2rayConfig4Speedtest(context, batch, candidate)
                        val delay = if (config.status && config.quicCore == null && config.aetherCore == null &&
                            config.masterDnsCore == null && config.dnsttCore == null && config.gutsCore == null) {
                            CoreNativeManager.measureOutboundDelay(config.content, SettingsManager.getDelayTestUrl(), batch)
                        } else -1L
                        progress(done.incrementAndGet(), candidates.size)
                        if (delay > 0L && !cancelled.get()) Result(ip, delay) else null
                    }
                } }.awaitAll().filterNotNull().minByOrNull { it.delay }
            }
        } finally {
            cancel()
        }
    }

    companion object {
        /** Exact one-field copy, preserving even implicit SNI derived from the original domain. */
        internal fun candidate(profile: ProfileItem, ip: String): ProfileItem {
            require(Utils.isPureIpAddress(ip))
            return profile.copy(dialAddress = ip)
        }
    }
}
