package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import android.os.SystemClock
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SpeedtestManager {

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        return happyEyeballsConnectTime(url, port, timeoutMs)
    }

    /**
     * How long a dual-stack connect may wait for one family before the other is tried as well.
     *
     * 250 ms is the usual value: long enough that a healthy first family wins the race on its own, short
     * enough that a broken one costs a quarter of a second instead of the whole timeout.
     */
    private const val HAPPY_EYEBALLS_DELAY_MS = 250L

    /**
     * Connects to `url:port` the way a browser does: every resolved address is tried, the first family
     * starts the race and the other follows after [HAPPY_EYEBALLS_DELAY_MS], so the quickest working path
     * wins.
     *
     * The previous implementation opened one socket to whatever address DNS returned first. On a mobile
     * network whose IPv6 route is dead or NAT64-broken — which is the normal state of the Cloudflare edge
     * addresses this app's profiles use — that single socket timed out while IPv4 was fine, and the profile
     * was reported as unreachable. This gate now answers the question it was written to answer: can this
     * endpoint be reached *at all*, and how fast.
     *
     * @return the connection time of the winner in milliseconds, or -1 when no address answered.
     */
    private fun happyEyeballsConnectTime(url: String, port: Int, timeoutMs: Int): Long {
        val addresses = try {
            InetAddress.getAllByName(url)
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
            return -1L
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve $url", e)
            return -1L
        }
        if (addresses.isEmpty()) return -1L

        // IPv6 first when the name has it, as a dual-stack resolver does, then alternating families.
        val ordered = addresses.sortedBy { if (it is Inet6Address) 0 else 1 }
        if (ordered.size == 1) {
            return connectTime(ordered.first(), port, timeoutMs)
        }

        val winner = AtomicReference<Long?>(null)
        val latch = CountDownLatch(1)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val sockets = Collections.synchronizedList(mutableListOf<Socket>())

        fun finish(value: Long) {
            if (winner.compareAndSet(null, value)) latch.countDown()
        }

        val threads = ordered.mapIndexed { index, address ->
            Thread {
                // Stagger the families instead of starting them all at once: the delay is what makes this
                // Happy Eyeballs rather than a flood of parallel connects.
                val wait = index * HAPPY_EYEBALLS_DELAY_MS.toLong()
                if (wait > 0L && latch.await(wait, TimeUnit.MILLISECONDS)) return@Thread
                if (winner.get() != null) return@Thread
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0L) return@Thread
                val started = SystemClock.elapsedRealtime()
                var socket: Socket? = null
                try {
                    socket = Socket()
                    sockets += socket
                    socket.connect(InetSocketAddress(address, port), left.toInt())
                    if (winner.get() == null) finish(SystemClock.elapsedRealtime() - started)
                } catch (_: IOException) {
                    // this address did not answer; another one may
                } catch (_: Exception) {
                    // as above
                } finally {
                    if (winner.get() != null) closeQuietly(socket)
                }
            }.apply { isDaemon = true; name = "omni-happy-eyeballs-$index"; start() }
        }

        latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        threads.forEach { it.interrupt() }
        sockets.forEach { closeQuietly(it) }
        return winner.get() ?: -1L
    }

    private fun connectTime(address: InetAddress, port: Int, timeoutMs: Int): Long {
        var socket: Socket? = null
        val started = SystemClock.elapsedRealtime()
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            SystemClock.elapsedRealtime() - started
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException to $address:$port: ${e.message}")
            -1L
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $address:$port", e)
            -1L
        } finally {
            closeQuietly(socket)
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            if (socket != null && !socket.isClosed) socket.close()
        } catch (_: IOException) {
            // ignore
        }
    }

    fun getRemoteIPInfo(): RemoteEndpointInfo? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return null
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return RemoteEndpointInfo(
            country = country,
            ipAddress = ip,
        )
    }
}
