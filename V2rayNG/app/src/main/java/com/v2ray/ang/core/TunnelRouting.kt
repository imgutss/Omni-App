package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig

/**
 * Keeps a TCP-only tunnel out of traffic it can never carry.
 *
 * GUTS, DNSTT and MasterDNS carry TCP through a relay that speaks HTTP: there is no UDP path at
 * all, so an application that starts with QUIC or STUN waits for a handshake that cannot arrive.
 * Left alone, the browser then spends seconds per site on QUIC before falling back, and every
 * attempt costs a door call. The rules this object adds refuse that traffic immediately, which is
 * what makes an application fall back to TCP at once — the opposite of a limitation: it is the
 * difference between "slow and unusable" and "works".
 *
 * The rules are added first, so a user's own routing rules cannot send QUIC into a tunnel that
 * would drop it. Port 53 stays open: DNS is answered by Xray's own DNS section, and refusing it
 * would break name resolution instead of fixing anything.
 */
object TunnelRouting {

    /** UDP ports that carry QUIC, STUN, and the VPN protocols an application falls back from. */
    internal const val UNSUPPORTED_UDP_PORTS = "443,3478,5349,500,4500,1194,19302-19309"

    /**
     * Hosts that answer DNS over HTTPS. Blocking them sends the lookup back to the system resolver,
     * which is Xray's DNS inside the VPN, so the tunnel's own DNS path is used instead of a DoH
     * connection the tunnel would have to carry and could not resolve through.
     */
    internal val DNS_OVER_HTTPS_HOSTS = listOf(
        "domain:cloudflare-dns.com",
        "domain:mozilla.cloudflare-dns.com",
        "domain:chrome.cloudflare-dns.com",
        "domain:dns.google",
        "domain:dns64.dns.google",
        "domain:dns.quad9.net",
        "domain:doh.opendns.com",
        "domain:doh.cleanbrowsing.org",
        "domain:dns.nextdns.io",
        "domain:firefox.dns.nextdns.io",
        "domain:doh.adguard.com",
        "domain:dns.adguard-dns.com",
    )

    /** True when the profile runs on a core with no UDP path at all. */
    fun carriesTcpOnly(guts: GutsCore?, dnstt: DnsttCore?, masterDns: MasterDnsCore?): Boolean =
        guts != null || dnstt != null || masterDns != null

    /**
     * Puts the refusal rules ahead of every other rule of [config]. Called once the configuration
     * is known to run on a TCP-only core; nothing is added for any other profile.
     */
    fun blockUnsupportedTransports(config: V2rayConfig) {
        val rules = config.routing.rules
        val block = AppConfig.TAG_BLOCKED
        // Ahead of the user's rules, and of the balancer's, in an order of its own.
        val added = buildList {
            add(
                V2rayConfig.RoutingBean.RulesBean(
                    network = "udp",
                    port = UNSUPPORTED_UDP_PORTS,
                    outboundTag = block,
                )
            )
            add(
                V2rayConfig.RoutingBean.RulesBean(
                    domain = ArrayList(DNS_OVER_HTTPS_HOSTS),
                    port = "443",
                    outboundTag = block,
                )
            )
        }
        rules.addAll(0, added.filter { rule -> rules.none { it.describesSameRefusal(rule) } })
    }

    /** Idempotent: a configuration built twice, as a reload does, keeps one copy of each rule. */
    private fun V2rayConfig.RoutingBean.RulesBean.describesSameRefusal(other: V2rayConfig.RoutingBean.RulesBean): Boolean =
        outboundTag == other.outboundTag && network == other.network && port == other.port && domain == other.domain

    /**
     * Answers every name from FakeDNS, so no lookup ever crosses the tunnel.
     *
     * Through a door whose round-trip is a second or more, resolving a page's hostnames the
     * ordinary way — a DoH connection of its own, through the tunnel, per uncached name — costs
     * more than the page itself, and a browser opening a dozen hosts pays it a dozen times
     * before the first byte. A fake answer is instant, Xray hands the domain itself to the
     * outbound (the inbounds already carry the fakedns destOverride), and the exit node at the
     * far end of the tunnel resolves it on a fast network for free. The ordinary configuration
     * already fakes some domains this way; a TCP-only tunnel needs all of them.
     */
    fun resolveNamesAtTheExit(config: V2rayConfig) {
        val servers = config.dns?.servers ?: return
        val fake = servers.filterIsInstance<V2rayConfig.DnsBean.ServersBean>()
            .firstOrNull { it.address == "fakedns" }
        if (fake != null) {
            // No domain list: this server now answers every name, ahead of the real resolvers.
            fake.domains = null
            servers.remove(fake)
            servers.add(0, fake)
        } else if (servers.none { it == "fakedns" }) {
            servers.add(0, V2rayConfig.DnsBean.ServersBean(address = "fakedns"))
        }
    }
}
