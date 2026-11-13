package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.firefox.FirefoxBridge
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.SubscriptionUpdateMessage
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.service.RealPingBatchPlan
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.OmniPingStatistics
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

object CoreServiceManager {

    private const val AETHER_WARM_UP_MS = 30_000L

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null

    /** The Aether core the running configuration depends on, null when it has no Aether outbound. */
    private var currentAether: AetherCore? = null

    /** The official MasterDnsVPN process the running configuration depends on, if any. */
    private var currentMasterDns: MasterDnsCore? = null

    /** The selected DNSTT/NoizDNS process the running configuration depends on, if any. */
    private var currentDnstt: DnsttCore? = null

    /** The guts client process the running configuration depends on, if any. */
    private var currentGuts: GutsCore? = null

    /** The QUIC-family sidecar (sing-box / shadowquic) the running configuration depends on, if any. */
    private var currentQuic: QuicCore? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null

    /** Written on the main thread and read by the reload thread, which tells a stop by it. */
    @Volatile
    private var networkMonitor: NetworkMonitor? = null
    /**
     * Owns the delay measurement the status bar asks for.
     *
     * The handler is what keeps a failure inside one measurement inside that measurement: without it, a
     * throw the body did not catch travels out of the root scope to the thread's uncaught handler and
     * takes the daemon down, which is a dead tunnel for a question about latency. With it, the failure is
     * logged and the completion handler below tells the screen the test ended.
     */
    private val connectionTestScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: delay measurement failed", throwable)
        }
    )

    /** Owns the Aether warm-up wait; cancelled on every start and stop so a stale wait cannot report. */
    private val aetherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var aetherWarmUpJob: Job? = null

    /** Set once an Aether exit has stopped the service, so a second report of the same exit is a no-op. */
    @Volatile
    private var aetherExitHandled = false

    private val masterDnsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var masterDnsWarmUpJob: Job? = null

    @Volatile
    private var masterDnsExitHandled = false

    private val dnsttScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dnsttWarmUpJob: Job? = null

    @Volatile
    private var dnsttExitHandled = false

    private val gutsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gutsWarmUpJob: Job? = null

    @Volatile
    private var gutsExitHandled = false

    private val quicScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var quicWarmUpJob: Job? = null

    @Volatile
    private var quicExitHandled = false

    @Volatile
    private var isReloading = false

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Whether the service is up; see [ReloadOutcome.serviceRuns]. [isRunning] is Xray alone, which a
     * reload has stopped for a moment. What answers a state query, or decides between a start and a
     * stop, goes by this one.
     */
    fun isServiceRunning() = ReloadOutcome.serviceRuns(
        coreRunning = isRunning(),
        reloading = isReloading,
        stoppedMeanwhile = networkMonitor == null,
    )

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            doStartCoreLoop(service, vpnInterface)
            return true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, userFacingReason(e))
            NotificationManager.cancelNotification()
            return false
        }
    }

    /**
     * A start or reload failure whose message is a localized resource string, meant for the main
     * screen. Every other failure reaches the UI without a reason: its message is technical and
     * belongs in the log, and the UI shows only resource text.
     */
    private class StartFailure(message: String) : RuntimeException(message)

    private fun userFacingReason(e: Exception): String = if (e is StartFailure) e.message.orEmpty() else ""

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        startNetworkMonitor(service)
    }

    /**
     * Builds the configuration and starts Xray and the Aether core on it. A reload passes [goesOn], which says
     * whether it still goes on; it is asked once the configuration is built and again once the ports of its
     * Aether session were checked, the two steps that can take seconds.
     *
     * @return false when [goesOn] said no; nothing of this attempt was started then.
     */
    @Throws(Exception::class)
    private fun launchCore(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean = false,
        goesOn: () -> Boolean = { true },
    ): Boolean {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        var content = result.content
        LogUtil.d(AppConfig.TAG, content)
        if (!result.status) {
            if (result.localizedError) throw StartFailure(result.errorMessage)
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }
        // The build can take seconds, resolving server names: a stop, or a stop and a new start, that came
        // meanwhile owns the cores, which a reload must leave alone from here on.
        if (!goesOn()) return false

        cancelAetherWarmUp()
        cancelMasterDnsWarmUp()
        cancelDnsttWarmUp()
        cancelGutsWarmUp()
        cancelQuicWarmUp()
        // Starting a configuration ends the config tests, whatever the configuration: that is what a start
        // means to the user, and the tests spawn Aether cores of their own, which a session's core must not
        // come up beside, on the same key or with Psiphon on the same datastore. The same goes for the test
        // phase of subscription updates, whose downloads go on. A reload keeps the session's place and
        // leaves the tests alone.
        if (!isReload) {
            MessageHelper.sendMsg2TestService(service, TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL))
            MessageHelper.sendMsg2SubscriptionService(
                service,
                SubscriptionUpdateMessage(AppConfig.MSG_SUB_UPDATE_CANCEL_TEST, forcedUpdate = false)
            )
        }
        // One core serves every Aether outbound of the configuration: the selected profile itself, a hop
        // of its chain, a routing target, a policy-group member, or the SOCKS outbounds of a custom
        // configuration that asks for it with aetherCommand. It listens on the port its arguments name.
        val aether = result.aetherCore
        if (aether != null) {
            if (!AetherCoreManager.isSupported(service)) {
                throw StartFailure(service.getString(R.string.aether_unsupported_abi))
            }
            // A command the core could not run as told, or a port Xray would take first: see refusalOf, which
            // checks them in the editor's order.
            AetherCoreManager.refusalOf(aether, result.content)?.let { refusal ->
                refuseAether(service, guid, isReload, refusal.reason) { service.getString(refusal.message, refusal.argument) }
            }
        }
        val masterDns = result.masterDnsCore
        if (masterDns != null) {
            if (!MasterDnsCoreManager.isSupported(service, masterDns.engine)) {
                throw StartFailure(service.getString(R.string.masterdns_unsupported_abi))
            }
            if (AetherDependency.inboundListensOn(result.content, masterDns.port)) {
                throw StartFailure(service.getString(R.string.masterdns_port_taken, masterDns.port))
            }
        }
        var dnstt = result.dnsttCore
        if (dnstt != null && !DnsttCoreManager.isSupported(service, dnstt.engine)) {
            throw StartFailure(service.getString(R.string.dnstt_unsupported_abi))
        }
        val guts = result.gutsCore
        if (guts != null && !GutsCoreManager.isSupported(service)) {
            throw StartFailure(service.getString(R.string.guts_unsupported_abi))
        }
        val quic = result.quicCore
        if (quic != null && !QuicCoreManager.isSupported(service, quic.engine)) {
            throw StartFailure(service.getString(R.string.quic_unsupported_abi))
        }
        // A reload still has the previous session's cores; they end before Xray starts again. New
        // external cores start once Xray listens, see launchNativeCore.
        AetherCoreManager.stop()
        MasterDnsCoreManager.stop()
        DnsttCoreManager.stop()
        GutsCoreManager.stop()
        QuicCoreManager.stop()
        FirefoxCoreManager.stop()
        if (aether != null) {
            checkAetherPorts(service, guid, aether, result.content, isReload)
        }
        if (masterDns != null) {
            checkMasterDnsPort(service, guid, masterDns, isReload)
        }
        if (dnstt != null) {
            val reservedPorts = buildSet {
                aether?.ports?.let(::addAll)
                masterDns?.port?.let(::add)
            }
            val prepared = DnsttCoreManager.prepareSession(service, dnstt, content, reservedPorts)
                ?: throw StartFailure(service.getString(R.string.dnstt_port_taken, dnstt.port))
            dnstt = prepared.core
            content = prepared.content
            if (AetherDependency.inboundListensOn(content, dnstt.port)) {
                throw StartFailure(service.getString(R.string.dnstt_port_taken, dnstt.port))
            }
            checkDnsttPort(service, guid, dnstt, isReload)
        }
        if (guts != null) {
            if (AetherDependency.inboundListensOn(content, guts.port)) {
                throw StartFailure(service.getString(R.string.guts_port_taken, guts.port))
            }
            checkGutsPort(service, guid, guts, isReload)
        }
        if (quic != null) {
            if (AetherDependency.inboundListensOn(content, quic.port)) {
                throw StartFailure(service.getString(R.string.quic_port_taken, quic.port))
            }
            checkQuicPort(service, guid, quic, isReload)
        }
        if ((aether != null || masterDns != null || dnstt != null || guts != null || quic != null) && !goesOn()) return false

        try {
            launchNativeCore(service, guid, config, aether, masterDns, dnstt, guts, quic, content, vpnInterface, isReload)
        } catch (e: Exception) {
            // Setup failed after this attempt spawned an external process; release both kinds.
            AetherCoreManager.stop()
            MasterDnsCoreManager.stop()
            DnsttCoreManager.stop()
            GutsCoreManager.stop()
            QuicCoreManager.stop()
            FirefoxCoreManager.stop()
            throw e
        }
        return true
    }

    /**
     * Refuses the session, for [reason] in the log and with [message] on the main screen. A reload still has the
     * previous session's core, which without Xray serves nothing; it ends too.
     */
    private fun refuseAether(service: Service, guid: String, isReload: Boolean, reason: String, message: () -> String): Nothing {
        LogUtil.w(
            AppConfig.TAG,
            "StartCore-Manager: ${service.javaClass.simpleName} ${if (isReload) "reload" else "start"} refused, $reason, guid=$guid"
        )
        AetherCoreManager.stop()
        MasterDnsCoreManager.stop()
        DnsttCoreManager.stop()
        GutsCoreManager.stop()
        QuicCoreManager.stop()
        FirefoxCoreManager.stop()
        throw StartFailure(message())
    }

    /**
     * Refuses the session when something else holds a loopback port it takes; see
     * AetherCoreManager.checkSessionPorts. Nothing of the session runs yet, and a reload has stopped Xray
     * and the previous core already, so a refusal leaves nothing running.
     */
    @Throws(Exception::class)
    private fun checkAetherPorts(service: Service, guid: String, aether: AetherCore, content: String, isReload: Boolean) {
        val attempt = "${service.javaClass.simpleName} ${if (isReload) "reload" else "start"}"
        val ports = AetherCoreManager.sessionPorts(aether, content)
        // Leak prevention: Xray's Aether outbounds dial the core's port as soon as Xray starts, before the core
        // binds it, so a program listening there would take that traffic. The ports are therefore checked before
        // Xray starts, and the start waits for the check, on the main thread of onStartCommand as well, at most
        // PORT_CHECK_WAIT_MS: the check runs on the core's executor, behind the stop of the previous core.
        val check = AetherCoreManager.checkSessionPorts(
            service,
            aether,
            ports,
            afterProbes = !isReload,
            timeoutMs = AetherCoreManager.PORT_CHECK_WAIT_MS,
        )
        when (check) {
            AetherCoreManager.PortCheck.Free -> Unit
            is AetherCoreManager.PortCheck.Taken -> {
                LogUtil.w(AppConfig.TAG, "StartCore-Manager: $attempt refused, port ${check.port} of the Aether session is taken, guid=$guid")
                throw StartFailure(service.getString(AetherCoreManager.takenMessage(check), check.port))
            }

            is AetherCoreManager.PortCheck.Unchecked -> {
                val refused = "StartCore-Manager: $attempt refused, the ports of the Aether session could not be checked"
                val cause = check.cause
                if (cause != null) {
                    LogUtil.w(AppConfig.TAG, "$refused, guid=$guid", cause)
                } else {
                    LogUtil.w(AppConfig.TAG, "$refused within ${AetherCoreManager.PORT_CHECK_WAIT_MS} ms, guid=$guid")
                }
                throw IllegalStateException("The ports of the Aether session could not be checked", cause)
            }
        }
    }

    /**
     * The same pre-Xray leak check for MasterDnsVPN's loopback SOCKS listener. Its bounded main-
     * thread wait prevents Xray from ever sending this profile to an unrelated existing listener.
     */
    @Throws(Exception::class)
    private fun checkMasterDnsPort(service: Service, guid: String, core: MasterDnsCore, isReload: Boolean) {
        val attempt = "${service.javaClass.simpleName} ${if (isReload) "reload" else "start"}"
        when (val check = MasterDnsCoreManager.checkSessionPort(core.port)) {
            MasterDnsCoreManager.PortCheck.Free -> Unit
            MasterDnsCoreManager.PortCheck.Taken -> {
                LogUtil.w(AppConfig.TAG, "StartCore-Manager: $attempt refused, MasterDNS port ${core.port} is taken, guid=$guid")
                throw StartFailure(service.getString(R.string.masterdns_port_taken, core.port))
            }
            is MasterDnsCoreManager.PortCheck.Unchecked -> {
                val message = "StartCore-Manager: $attempt could not check the MasterDNS port, guid=$guid"
                check.cause?.let { LogUtil.w(AppConfig.TAG, message, it) } ?: LogUtil.w(AppConfig.TAG, message)
                throw IllegalStateException("The MasterDNS session port could not be checked", check.cause)
            }
        }
    }

    @Throws(Exception::class)
    private fun checkDnsttPort(service: Service, guid: String, core: DnsttCore, isReload: Boolean) {
        val attempt = "${service.javaClass.simpleName} ${if (isReload) "reload" else "start"}"
        val ports = core.sessionPorts
        ports.forEach { port ->
            when (val check = DnsttCoreManager.checkSessionPort(port)) {
                DnsttCoreManager.PortCheck.Free -> Unit
                DnsttCoreManager.PortCheck.Taken -> {
                    LogUtil.w(AppConfig.TAG, "StartCore-Manager: $attempt refused, DNSTT port $port is taken, guid=$guid")
                    throw StartFailure(service.getString(R.string.dnstt_port_taken, port))
                }
                is DnsttCoreManager.PortCheck.Unchecked -> {
                    val message = "StartCore-Manager: $attempt could not check the DNSTT port, guid=$guid"
                    check.cause?.let { LogUtil.w(AppConfig.TAG, message, it) } ?: LogUtil.w(AppConfig.TAG, message)
                    throw IllegalStateException("The DNSTT session port could not be checked", check.cause)
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun checkGutsPort(service: Service, guid: String, core: GutsCore, isReload: Boolean) {
        val attempt = "${service.javaClass.simpleName} ${if (isReload) "reload" else "start"}"
        when (val check = GutsCoreManager.checkSessionPort(core.port)) {
            GutsCoreManager.PortCheck.Free -> Unit
            GutsCoreManager.PortCheck.Taken -> {
                LogUtil.w(AppConfig.TAG, "StartCore-Manager: $attempt refused, GUTS port ${core.port} is taken, guid=$guid")
                throw StartFailure(service.getString(R.string.guts_port_taken, core.port))
            }
            is GutsCoreManager.PortCheck.Unchecked -> {
                val message = "StartCore-Manager: $attempt could not check the GUTS port, guid=$guid"
                check.cause?.let { LogUtil.w(AppConfig.TAG, message, it) } ?: LogUtil.w(AppConfig.TAG, message)
                throw IllegalStateException("The GUTS session port could not be checked", check.cause)
            }
        }
    }

    /** The same pre-Xray leak check for the QUIC sidecar's loopback SOCKS listener. */
    @Throws(Exception::class)
    private fun checkQuicPort(service: Service, guid: String, core: QuicCore, isReload: Boolean) {
        val attempt = "${service.javaClass.simpleName} ${if (isReload) "reload" else "start"}"
        when (val check = QuicCoreManager.checkSessionPort(core.port)) {
            QuicCoreManager.PortCheck.Free -> Unit
            QuicCoreManager.PortCheck.Taken -> {
                LogUtil.w(AppConfig.TAG, "StartCore-Manager: $attempt refused, QUIC port ${core.port} is taken, guid=$guid")
                throw StartFailure(service.getString(R.string.quic_port_taken, core.port))
            }
            is QuicCoreManager.PortCheck.Unchecked -> {
                val message = "StartCore-Manager: $attempt could not check the QUIC port, guid=$guid"
                check.cause?.let { LogUtil.w(AppConfig.TAG, message, it) } ?: LogUtil.w(AppConfig.TAG, message)
                throw IllegalStateException("The QUIC session port could not be checked", check.cause)
            }
        }
    }

    /**
     * Starts the account engine the "Firefox" row depends on, before Xray can dial it.
     *
     * The row's configuration is one SOCKS outbound to the engine's loopback port, so a start without
     * the engine would be a row that answers nothing — and a fallback to a direct outbound would be
     * worse: it would send the person's traffic out in the clear while the screen said it was carried.
     * So every way this can fail is refused here, with the reason on the screen, and the session is
     * not started.
     */
    @Throws(Exception::class)
    private fun startFirefoxEngine(service: Service, guid: String) {
        val port = FirefoxBridge.port()
        if (!FirefoxCoreManager.isSupported(service)) {
            throw StartFailure(service.getString(R.string.firefox_engine_missing))
        }
        if (!FirefoxCoreManager.start(service, socksPort = port, apiPort = FirefoxBridge.DEFAULT_API_PORT)) {
            FirefoxCoreManager.stop()
            throw StartFailure(service.getString(R.string.firefox_engine_not_started))
        }
        if (!FirefoxCoreManager.awaitListeningBlocking(port, AetherDelayTester.TEST_BUDGET_MS)) {
            FirefoxCoreManager.stop()
            throw StartFailure(service.getString(R.string.firefox_engine_not_listening, port))
        }
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: firefox account engine listening on $port, guid=$guid")
    }

    @Throws(Exception::class)
    private fun launchNativeCore(
        service: Service,
        guid: String,
        config: ProfileItem,
        aether: AetherCore?,
        masterDns: MasterDnsCore?,
        dnstt: DnsttCore?,
        guts: GutsCore?,
        quic: QuicCore?,
        content: String,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean,
    ) {
        currentConfig = config
        currentAether = aether
        currentMasterDns = masterDns
        currentDnstt = dnstt
        currentGuts = guts
        currentQuic = quic
        // The account row carries traffic through the engine on this phone and not through an Xray
        // outbound, so the engine is started here, before Xray can dial it.
        if (FirefoxBridge.isFirefoxRow(guid)) startFirefoxEngine(service, guid)
        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        coreController.startLoop(content, tunFd)

        if (!isRunning()) {
            error("Core failed to start")
        }

        // The Aether core dials out through an inbound of Xray, which listens once the start returns, so
        // the core starts after it rather than spending its first dials on a port nobody listens on yet.
        if (aether != null) {
            // The tests were told to stop as this start began; the session's core waits for their cores to be gone.
            aetherExitHandled = false
            AetherCoreManager.start(service, aether, afterProbes = !isReload) { onAetherExit(guid) }
        }
        if (masterDns != null) {
            masterDnsExitHandled = false
            val protect: ((Int) -> Boolean)? = if (vpnInterface == null) null else { fd ->
                // CoreVpnService remains the owner of Android socket protection; proxy and root modes
                // pass no VPN descriptor and therefore no protect endpoint to the Go client.
                serviceControl?.get()?.vpnProtect(fd) == true
            }
            MasterDnsCoreManager.start(service, masterDns, protect) { onMasterDnsExit(guid) }
        }
        if (dnstt != null) {
            dnsttExitHandled = false
            val protect: ((Int) -> Boolean)? = if (vpnInterface == null) null else { fd ->
                serviceControl?.get()?.vpnProtect(fd) == true
            }
            DnsttCoreManager.start(service, dnstt, protect) { onDnsttExit(guid) }
        }
        if (guts != null) {
            gutsExitHandled = false
            val protect: ((Int) -> Boolean)? = if (vpnInterface == null) null else { fd ->
                // The client dials a Google edge address, which inside the VPN is our own tun
                // device; protecting every socket is what keeps the tunnel out of itself.
                serviceControl?.get()?.vpnProtect(fd) == true
            }
            GutsCoreManager.start(service, guts, protect) { onGutsExit(guid) }
        }
        if (quic != null) {
            quicExitHandled = false
            val protect: ((Int) -> Boolean)? = if (vpnInterface == null) null else { fd ->
                // The client dials its own server, which inside the VPN is our own tun device;
                // protecting every socket is what keeps the tunnel out of itself.
                serviceControl?.get()?.vpnProtect(fd) == true
            }
            QuicCoreManager.start(service, quic, protect) { onQuicExit(guid) }
        }

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> {}
        }

        if (aether != null) {
            announceAetherWarmUp(service, guid, isReload)
        } else if (masterDns != null) {
            announceMasterDnsWarmUp(service, guid, isReload)
        } else if (dnstt != null) {
            announceDnsttWarmUp(service, guid, isReload)
        } else if (guts != null) {
            announceGutsWarmUp(service, guid, isReload)
        } else if (quic != null) {
            announceQuicWarmUp(service, guid, isReload)
        } else if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        }
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Xray is up as soon as it starts, but an Aether profile carries no traffic until the Aether
     * process has scanned and connected, which can take minutes. Clients are told the service is
     * running right away, the main screen and the notification show the connecting state, and the
     * start-success signal follows once the Aether SOCKS listener accepts connections.
     */
    private fun announceAetherWarmUp(service: Service, guid: String, isReload: Boolean) {
        val connecting = service.getString(R.string.aether_core_connecting)
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_CONNECTING, connecting)
        NotificationManager.setStatusLine(connecting)
        aetherWarmUpJob = aetherScope.launch {
            var listening = false
            while (isActive && !listening && AetherCoreManager.isRunning) {
                listening = AetherCoreManager.awaitListening(AETHER_WARM_UP_MS)
            }
            when (AetherCoreManager.warmUpOutcome(listening, isActive, isRunning())) {
                AetherCoreManager.WarmUpOutcome.ABANDONED -> Unit
                // The core's exit callback reports it as well; the service stops on whichever comes first.
                AetherCoreManager.WarmUpOutcome.CORE_EXITED -> onAetherExit(guid)
                AetherCoreManager.WarmUpOutcome.LISTENING -> {
                    NotificationManager.setStatusLine(null)
                    val ready = if (isReload) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_START_SUCCESS
                    MessageHelper.sendMsg2UI(service, ready, "")
                }
            }
        }
    }

    private fun isAetherWarmingUp(): Boolean = aetherWarmUpJob?.isActive == true

    private fun cancelAetherWarmUp() {
        aetherWarmUpJob?.cancel()
        aetherWarmUpJob = null
        NotificationManager.setStatusLine(null)
    }

    /** Reports ready only after real HTTP succeeds through this exact native core and Xray outbound. */
    private fun announceMasterDnsWarmUp(service: Service, guid: String, isReload: Boolean) {
        val connecting = service.getString(R.string.masterdns_core_connecting)
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_CONNECTING, connecting)
        NotificationManager.setStatusLine(connecting)
        masterDnsWarmUpJob = masterDnsScope.launch {
            var listening = false
            var reachable = false
            while (isActive && !reachable && MasterDnsCoreManager.isRunning) {
                if (!listening) listening = MasterDnsCoreManager.awaitListening(AETHER_WARM_UP_MS)
                if (listening) {
                    reachable = confirmNativePath()
                    if (!reachable) kotlinx.coroutines.delay(1_000L)
                }
            }
            when (AetherCoreManager.warmUpOutcome(reachable, isActive, isRunning())) {
                AetherCoreManager.WarmUpOutcome.ABANDONED -> Unit
                AetherCoreManager.WarmUpOutcome.CORE_EXITED -> onMasterDnsExit(guid)
                AetherCoreManager.WarmUpOutcome.LISTENING -> {
                    NotificationManager.setStatusLine(null)
                    val ready = if (isReload) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_START_SUCCESS
                    MessageHelper.sendMsg2UI(service, ready, "")
                }
            }
        }
    }

    private fun cancelMasterDnsWarmUp() {
        masterDnsWarmUpJob?.cancel()
        masterDnsWarmUpJob = null
        NotificationManager.setStatusLine(null)
    }

    private fun onMasterDnsExit(guid: String) {
        val control = serviceControl?.get() ?: return
        val service = control.getService()
        ContextCompat.getMainExecutor(service).execute {
            if (masterDnsExitHandled || MasterDnsCoreManager.isRunning || !isRunning() || serviceControl?.get() !== control) return@execute
            masterDnsExitHandled = true
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: MasterDNS core exited while running, stopping ${service.javaClass.simpleName}, guid=$guid")
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, service.getString(R.string.masterdns_core_stopped))
            control.stopService()
        }
    }

    /** Reports DNSTT ready only after real HTTP succeeds through this exact native core and Xray outbound. */
    private fun announceDnsttWarmUp(service: Service, guid: String, isReload: Boolean) {
        val connecting = service.getString(R.string.dnstt_core_connecting)
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_CONNECTING, connecting)
        NotificationManager.setStatusLine(connecting)
        val startupBudget = if (currentDnstt?.ssh != null) DnsttDelayTester.SSH_STARTUP_BUDGET_MS else AETHER_WARM_UP_MS
        dnsttWarmUpJob = dnsttScope.launch {
            var listening = false
            var reachable = false
            while (isActive && !reachable && DnsttCoreManager.isRunning) {
                if (!listening) listening = DnsttCoreManager.awaitListening(startupBudget)
                if (listening) {
                    reachable = confirmNativePath()
                    if (!reachable) kotlinx.coroutines.delay(1_000L)
                }
            }
            when (AetherCoreManager.warmUpOutcome(reachable, isActive, isRunning())) {
                AetherCoreManager.WarmUpOutcome.ABANDONED -> Unit
                AetherCoreManager.WarmUpOutcome.CORE_EXITED -> onDnsttExit(guid)
                AetherCoreManager.WarmUpOutcome.LISTENING -> {
                    NotificationManager.setStatusLine(null)
                    val ready = if (isReload) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_START_SUCCESS
                    MessageHelper.sendMsg2UI(service, ready, "")
                }
            }
        }
    }

    private fun cancelDnsttWarmUp() {
        dnsttWarmUpJob?.cancel()
        dnsttWarmUpJob = null
        NotificationManager.setStatusLine(null)
    }

    /** Reports GUTS ready only after real HTTP succeeds through the client and the Xray outbound. */
    private fun announceGutsWarmUp(service: Service, guid: String, isReload: Boolean) {
        val connecting = service.getString(R.string.guts_core_connecting)
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_CONNECTING, connecting)
        NotificationManager.setStatusLine(connecting)
        gutsWarmUpJob = gutsScope.launch {
            var listening = false
            var reachable = false
            while (isActive && !reachable && GutsCoreManager.isRunning) {
                if (!listening) listening = GutsCoreManager.awaitListening(AETHER_WARM_UP_MS)
                if (listening) {
                    reachable = confirmNativePath(
                        if (currentGuts?.mode == GutsCore.Mode.FETCH) {
                            listOf(GutsCore.FETCH_TEST_URL)
                        } else {
                            null
                        }
                    )
                    if (!reachable) kotlinx.coroutines.delay(1_000L)
                }
            }
            when (AetherCoreManager.warmUpOutcome(reachable, isActive, isRunning())) {
                AetherCoreManager.WarmUpOutcome.ABANDONED -> Unit
                AetherCoreManager.WarmUpOutcome.CORE_EXITED -> onGutsExit(guid)
                AetherCoreManager.WarmUpOutcome.LISTENING -> {
                    NotificationManager.setStatusLine(null)
                    val ready = if (isReload) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_START_SUCCESS
                    MessageHelper.sendMsg2UI(service, ready, "")
                }
            }
        }
    }

    private fun cancelGutsWarmUp() {
        gutsWarmUpJob?.cancel()
        gutsWarmUpJob = null
        NotificationManager.setStatusLine(null)
    }

    private fun onGutsExit(guid: String) {
        val control = serviceControl?.get() ?: return
        val service = control.getService()
        ContextCompat.getMainExecutor(service).execute {
            if (gutsExitHandled || GutsCoreManager.isRunning || !isRunning() || serviceControl?.get() !== control) return@execute
            gutsExitHandled = true
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: GUTS client exited while running, stopping ${service.javaClass.simpleName}, guid=$guid")
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, service.getString(R.string.guts_core_stopped))
            control.stopService()
        }
    }

    private fun announceQuicWarmUp(service: Service, guid: String, isReload: Boolean) {
        val connecting = service.getString(R.string.quic_core_connecting)
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_CONNECTING, connecting)
        NotificationManager.setStatusLine(connecting)
        val expectedIdentity = QuicCoreManager.currentIdentity
        quicWarmUpJob = quicScope.launch {
            val deadline = android.os.SystemClock.elapsedRealtime() + 90_000L
            var listening = false
            var reachable = false
            while (isActive && !reachable && QuicCoreManager.isRunning && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!listening) listening = QuicCoreManager.awaitListening(AETHER_WARM_UP_MS)
                if (listening) {
                    reachable = confirmNativePath()
                    if (!reachable) kotlinx.coroutines.delay(1_000L)
                }
            }
            if (isActive && !reachable && QuicCoreManager.isRunning) {
                LogUtil.w(AppConfig.TAG, "QUIC readiness timed out: ${QuicCoreManager.protectionDiagnostics()}")
                val control = serviceControl?.get()
                ContextCompat.getMainExecutor(service).execute {
                    if (serviceControl?.get() === control && control != null && isRunning() &&
                        QuicCoreManager.currentIdentity == expectedIdentity) {
                        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE,
                            service.getString(R.string.quic_core_timeout))
                        control.stopService()
                    }
                }
                return@launch
            }
            when (AetherCoreManager.warmUpOutcome(reachable, isActive, isRunning())) {
                AetherCoreManager.WarmUpOutcome.ABANDONED -> Unit
                AetherCoreManager.WarmUpOutcome.CORE_EXITED -> onQuicExit(guid)
                AetherCoreManager.WarmUpOutcome.LISTENING -> {
                    NotificationManager.setStatusLine(null)
                    val ready = if (isReload) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_START_SUCCESS
                    MessageHelper.sendMsg2UI(service, ready, "")
                }
            }
        }
    }

    private fun cancelQuicWarmUp() {
        quicWarmUpJob?.cancel()
        quicWarmUpJob = null
        NotificationManager.setStatusLine(null)
    }

    private fun onQuicExit(guid: String) {
        val control = serviceControl?.get() ?: return
        val service = control.getService()
        ContextCompat.getMainExecutor(service).execute {
            if (quicExitHandled || QuicCoreManager.isRunning || !isRunning() || serviceControl?.get() !== control) return@execute
            quicExitHandled = true
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: QUIC sidecar exited while running, stopping ${service.javaClass.simpleName}, guid=$guid")
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, service.getString(R.string.quic_core_stopped))
            control.stopService()
        }
    }

    private suspend fun confirmNativePath(probeUrls: List<String>? = null): Boolean {
        val urls = probeUrls ?: listOf(false, true).map { SettingsManager.getDelayTestUrl(it) }
        for (url in urls) {
            try {
                if (coreController.measureDelay(url) > 0L) return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.w(AppConfig.TAG, "Native DNS readiness HTTP probe failed", error)
            }
        }
        return false
    }

    private fun onDnsttExit(guid: String) {
        val control = serviceControl?.get() ?: return
        val service = control.getService()
        ContextCompat.getMainExecutor(service).execute {
            if (dnsttExitHandled || DnsttCoreManager.isRunning || !isRunning() || serviceControl?.get() !== control) return@execute
            dnsttExitHandled = true
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: DNSTT core exited while running, stopping ${service.javaClass.simpleName}, guid=$guid")
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, service.getString(R.string.dnstt_core_stopped))
            control.stopService()
        }
    }

    /**
     * Stops the service once the Aether core is gone while Xray still runs. Reached from the
     * core's exit callback and from the warm-up wait, so the stop happens once per session.
     */
    private fun onAetherExit(guid: String) {
        val control = serviceControl?.get() ?: return
        val service = control.getService()
        ContextCompat.getMainExecutor(service).execute {
            if (aetherExitHandled || AetherCoreManager.isRunning || !isRunning() || serviceControl?.get() !== control) return@execute
            aetherExitHandled = true
            LogUtil.e(
                AppConfig.TAG,
                "StartCore-Manager: Aether core exited while running, stopping ${service.javaClass.simpleName}, guid=$guid"
            )
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, AetherCoreManager.stoppedMessage(service))
            control.stopService()
        }
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        connectionTestScope.coroutineContext.cancelChildren()
        val service = getService() ?: return false

        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null
        // First, so that nothing further down posts the notification again: the proxy-only and the
        // root service tear down in onDestroy, out of the foreground already, where a post outlives them.
        NotificationManager.cancelNotification()
        cancelAetherWarmUp()
        cancelMasterDnsWarmUp()
        cancelDnsttWarmUp()
        cancelGutsWarmUp()
        cancelQuicWarmUp()
        AetherCoreManager.stop()
        MasterDnsCoreManager.stop()
        DnsttCoreManager.stop()
        GutsCoreManager.stop()
        QuicCoreManager.stop()

        if (isRunning()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // PattNG: the name alone, so that a monitor left registered does not keep the service alive.
        val runMode = service.javaClass.simpleName
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            claimHandover = { monitor -> claimReload(askedBy = monitor) },
            onHandover = { monitor -> reloadCore(askedBy = monitor) },
            describe = { "$runMode, guid=${MmkvManager.getSelectServer()}" },
        ).also { it.register() }
    }

    /**
     * Claims the reload a handover seen by [askedBy] asks for, and marks it under way, so that a second
     * handover does not begin one as well: only while [askedBy] is the service's monitor still, Xray runs
     * and no reload is under way. The handover rule of NetworkMonitor: it calls this under the gate its
     * unregister() closes first, so no reload is claimed once that has returned, and it has to be short. A
     * stop unregisters and clears the monitor and a new start brings its own, so a handover of an earlier
     * monitor reloads nothing; a stop that comes during the reload is told by goesOn and afterwards.
     */
    private fun claimReload(askedBy: NetworkMonitor): Boolean =
        ReloadOutcome.handoverReloads(askedBy, current = networkMonitor, coreRunning = isRunning(), reloading = isReloading)
            .also { if (it) isReloading = true }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible. PattNG: it runs once
     * [claimReload] has claimed the reload, and ends it.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    private fun reloadCore(askedBy: NetworkMonitor): Boolean {
        val service = getService() ?: run {
            isReloading = false
            return false
        }

        try {
            val tunFd = currentVpnInterface

            connectionTestScope.coroutineContext.cancelChildren()
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")

            coreController.stopLoop()
            if (launchCore(service, tunFd, isReload = true, goesOn = { ReloadOutcome.goesOn(askedBy = askedBy, current = networkMonitor) })) {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            } else {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload given up, the service was stopped while the configuration was built or the Aether ports were checked")
            }
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(
                AppConfig.TAG,
                "StartCore-Manager: ${service.javaClass.simpleName} reload failed: $message, guid=${MmkvManager.getSelectServer()}",
                e
            )
            // After a stop the screen has been told of it, and after a new start its state is that start's to report.
            if (networkMonitor === askedBy) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, userFacingReason(e))
            }
        } finally {
            isReloading = false
        }

        // No monitor at all means stopped and not started again; a new one belongs to a new start, which owns the cores.
        return when (ReloadOutcome.of(coreRunning = isRunning(), stoppedMeanwhile = networkMonitor == null)) {
            ReloadOutcome.KEEP_RUNNING -> true
            ReloadOutcome.STOP_SERVICE -> {
                stopServiceAfterFailedReload(askedBy)
                false
            }

            ReloadOutcome.RELEASE_CORES -> {
                releaseAfterStoppedReload(service)
                false
            }
        }
    }

    /**
     * Stops the service once a reload has left Xray stopped; see [ReloadOutcome.STOP_SERVICE]. The
     * reload runs on a background thread, the stop goes through the service on the main thread.
     */
    private fun stopServiceAfterFailedReload(monitor: NetworkMonitor) {
        val control = serviceControl?.get() ?: return
        val service = control.getService()
        ContextCompat.getMainExecutor(service).execute {
            // A stop or a new start that arrived meanwhile has taken over.
            if (isRunning() || networkMonitor !== monitor || serviceControl?.get() !== control) return@execute
            LogUtil.e(
                AppConfig.TAG,
                "StartCore-Manager: reload left no core running, stopping ${service.javaClass.simpleName}, guid=${MmkvManager.getSelectServer()}"
            )
            control.stopService()
        }
    }

    /**
     * Releases what a reload started for a service that was stopped while it ran; see
     * [ReloadOutcome.RELEASE_CORES]. The teardown of that service has done the rest already.
     *
     * It runs on the main thread, where a service starts as well: a start that followed the stop,
     * such as the second half of a restart, is then either over, and what runs is its own, or has
     * not begun, and finds nothing left running. For the same reason Xray is stopped in place here
     * rather than in the background: a start that begins next must not meet the core of the reload.
     */
    private fun releaseAfterStoppedReload(service: Service) {
        ContextCompat.getMainExecutor(service).execute {
            if (networkMonitor != null) return@execute
            LogUtil.w(
                AppConfig.TAG,
                "StartCore-Manager: ${service.javaClass.simpleName} was stopped during a reload, releasing the cores the reload started, " +
                    "guid=${MmkvManager.getSelectServer()}"
            )
            NotificationManager.cancelNotification()
            cancelAetherWarmUp()
            cancelMasterDnsWarmUp()
            cancelDnsttWarmUp()
            cancelGutsWarmUp()
            cancelQuicWarmUp()
            AetherCoreManager.stop()
            MasterDnsCoreManager.stop()
            DnsttCoreManager.stop()
            GutsCoreManager.stop()
            QuicCoreManager.stop()
            try {
                coreController.stopLoop()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop the core a stopped reload started", e)
            }
            CoreNativeManager.reconcileBrowserDialer("")
            browserDialer?.stop()
            browserDialer = null
            // The reload may have announced a running or connecting service after the stop was reported.
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        // The stats manager is gone once the core stops, querying it then reaches into freed state.
        if (!isRunning()) return emptyList()

        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay(requestId: String) {
        val service = getService() ?: return
        if (!isRunning() || isReloading) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            return
        }

        connectionTestScope.coroutineContext.cancelChildren()
        connectionTestScope.launch {
            // The same budget every other profile's probe gets; a tunnel still scanning past it is reported, not waited for.
            if (currentAether != null && !AetherCoreManager.awaitListening(AetherDelayTester.TEST_BUDGET_MS)) {
                val reason = if (AetherCoreManager.isRunning) R.string.aether_core_connecting else R.string.aether_core_stopped
                val stalled = ConnectionTestResult(delayMillis = -1L, errorMessage = service.getString(reason))
                withContext(Dispatchers.Main.immediate) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, stalled, requestId)
                }
                return@launch
            }
            if (currentMasterDns != null && !MasterDnsCoreManager.awaitListening(AetherDelayTester.TEST_BUDGET_MS)) {
                val reason = if (MasterDnsCoreManager.isRunning) R.string.masterdns_core_connecting else R.string.masterdns_core_stopped
                val stalled = ConnectionTestResult(delayMillis = -1L, errorMessage = service.getString(reason))
                withContext(Dispatchers.Main.immediate) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, stalled, requestId)
                }
                return@launch
            }
            if (currentDnstt != null && !DnsttCoreManager.awaitListening(AetherDelayTester.TEST_BUDGET_MS)) {
                val reason = if (DnsttCoreManager.isRunning) R.string.dnstt_core_connecting else R.string.dnstt_core_stopped
                val stalled = ConnectionTestResult(delayMillis = -1L, errorMessage = service.getString(reason))
                withContext(Dispatchers.Main.immediate) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, stalled, requestId)
                }
                return@launch
            }
            if (currentGuts != null && !GutsCoreManager.awaitListening(AetherDelayTester.TEST_BUDGET_MS)) {
                val reason = if (GutsCoreManager.isRunning) R.string.guts_core_connecting else R.string.guts_core_stopped
                val stalled = ConnectionTestResult(delayMillis = -1L, errorMessage = service.getString(reason))
                withContext(Dispatchers.Main.immediate) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, stalled, requestId)
                }
                return@launch
            }
            if (currentQuic != null && !QuicCoreManager.awaitListening(AetherDelayTester.TEST_BUDGET_MS)) {
                val reason = if (QuicCoreManager.isRunning) R.string.quic_core_connecting else R.string.quic_core_stopped
                val stalled = ConnectionTestResult(delayMillis = -1L, errorMessage = service.getString(reason))
                withContext(Dispatchers.Main.immediate) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, stalled, requestId)
                }
                return@launch
            }

            val errorRef = AtomicReference("")
            val fetchMode = currentGuts?.mode == GutsCore.Mode.FETCH
            val primaryUrl = if (fetchMode) GutsCore.FETCH_TEST_URL else SettingsManager.getDelayTestUrl()
            val alternateUrl = if (fetchMode) GutsCore.FETCH_TEST_URL else SettingsManager.getDelayTestUrl(true)

            suspend fun measureOnce(): Long {
                var value = -1L
                try {
                    value = coreController.measureDelay(primaryUrl)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorRef.compareAndSet("", e.message?.substringAfter("\":").orEmpty())
                }
                if (value == -1L) {
                    ensureActive()
                    try {
                        value = coreController.measureDelay(alternateUrl)
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                        errorRef.compareAndSet("", e.message?.substringAfter("\":").orEmpty())
                    }
                }
                return value
            }

            // The samples of one profile run one after the other, on the one tunnel that carries them:
            // that is the connection the user has, and overlapping them made each one queue behind the
            // last and report that queue as latency. Each sample is a real response, and the summary
            // reports the fastest one next to the typical and the slowest.
            val sampleCount = RealPingBatchPlan.samples(SettingsManager.getRealPingSamples())
            val samples = ArrayList<Long>(sampleCount)
            repeat(sampleCount) {
                ensureActive()
                samples += measureOnce()
            }
            val errorStr = errorRef.get()
            val stats = OmniPingStatistics.summarize(samples, OmniPingStatistics.KIND_HTTP)
            ensureActive()
            val endpoint = if (stats.delayMillis > 0) SpeedtestManager.getRemoteIPInfo() else null
            val result = ConnectionTestResult(
                delayMillis = stats.delayMillis,
                errorMessage = errorStr,
                country = endpoint?.country,
                ipAddress = endpoint?.ipAddress,
                sampleCount = stats.sampleCount,
                successfulSamples = stats.successfulSamples,
                jitterMillis = stats.jitterMillis,
                kind = stats.kind,
                medianMillis = stats.medianMillis,
                maxMillis = stats.maxMillis,
                partial = stats.partial,
            )
            withContext(Dispatchers.Main.immediate) {
                if (isRunning()) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result, requestId)
                } else {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
                }
            }
        }.invokeOnCompletion { cause ->
            // Whatever ends the measurement, the screen has to leave "Testing…": a person who asked for a
            // number is owed either the number or the reason there is not one, never a bar that waits.
            if (cause != null) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    // A client that gets no acknowledgement takes the service for gone: the daemon
                    // cannot report its own death, so silence is the only sign of it.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    if (isServiceRunning()) {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                        if (isAetherWarmingUp()) {
                            val service = serviceControl.getService()
                            MessageHelper.sendMsg2UI(
                                service,
                                AppConfig.MSG_STATE_CONNECTING,
                                service.getString(R.string.aether_core_connecting)
                            )
                        }
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            serviceControl.stopService()
                            delay(500L)
                            LauncherManager.startService(serviceControl.getService())
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    measureV2rayDelay(intent.getStringExtra("content").orEmpty())
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
