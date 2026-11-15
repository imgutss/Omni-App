package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class NaiveSocketConnectorTest {
    @Test
    fun protectsBeforeConnectAndKeepsRequestedPort() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket().use { client ->
                var protected = false
                NaiveSocketConnector.connect(client, InetAddress.getLoopbackAddress(), server.localPort) {
                    assertFalse(it.isConnected)
                    assertTrue(it.isBound)
                    protected = true
                    true
                }
                assertTrue(protected)
                server.accept().use { peer ->
                    client.getOutputStream().write(42)
                    assertEquals(42, peer.getInputStream().read())
                }
            }
        }
    }
    @Test
    fun protectionFailureNeverConnectsAndClosesDescriptor() {
        val socket = Socket()
        assertThrows(IllegalStateException::class.java) {
            NaiveSocketConnector.connect(socket, InetAddress.getLoopbackAddress(), 9) { false }
        }
        assertFalse(socket.isConnected)
        assertTrue(socket.isClosed)
    }
}
