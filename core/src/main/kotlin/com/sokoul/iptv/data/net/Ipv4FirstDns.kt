package com.sokoul.iptv.data.net

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * IPv4 addresses first, IPv6 ones after. On the user's Google TV, IPv6 gets no answer while the names resolve to two
 * IPv6 addresses first: each new connection waited 10 s per address before reaching IPv4 (22 s for Torrentio, measured
 * on 2026-11-08). IPv6 still serves when it is the only way.
 */
object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = order(Dns.SYSTEM.lookup(hostname))

    fun order(addresses: List<InetAddress>): List<InetAddress> = addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
}
