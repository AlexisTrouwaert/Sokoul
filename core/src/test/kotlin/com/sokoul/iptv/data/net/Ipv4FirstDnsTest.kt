package com.sokoul.iptv.data.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class Ipv4FirstDnsTest {
    @Test fun `IPv4 comes before IPv6, and IPv6 still serves`() {
        // What the TV's resolver returned for torrentio.strem.fun: two IPv6 addresses first.
        val v6a = InetAddress.getByName("2606:4700:3033::6815:afe")
        val v6b = InetAddress.getByName("2606:4700:3033::ac43:a4dc")
        val v4a = InetAddress.getByName("104.21.10.254")
        val v4b = InetAddress.getByName("172.67.164.220")
        assertEquals(listOf(v4a, v4b, v6a, v6b), Ipv4FirstDns.order(listOf(v6a, v6b, v4a, v4b)))
        assertEquals(listOf(v6a), Ipv4FirstDns.order(listOf(v6a)))
    }
}
