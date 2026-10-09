package com.local.deploy.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortManagerTest {

    @Test
    fun testPortAllocation() {
        val portManager = PortManager(startPort = 9100, endPort = 9110)
        val port1 = portManager.allocatePortForProject("proj-1")
        assertTrue(port1 in 9100..9110)

        val port2 = portManager.allocatePortForProject("proj-2")
        assertTrue(port2 in 9100..9110)
        assertTrue(port1 != port2)

        // Same project gets same remembered port
        val port1Again = portManager.allocatePortForProject("proj-1")
        assertEquals(port1, port1Again)

        portManager.releasePortForProject("proj-1")
    }
}
