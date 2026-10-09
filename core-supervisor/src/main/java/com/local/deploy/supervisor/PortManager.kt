package com.local.deploy.supervisor

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

class PortManager(
    private val startPort: Int = 8000,
    private val endPort: Int = 8999
) {
    private val projectPortMap = ConcurrentHashMap<String, Int>()

    @Synchronized
    fun allocatePortForProject(projectId: String, preferredPort: Int? = null): Int {
        // 1. Check if project already has a remembered port and it's available
        projectPortMap[projectId]?.let { existingPort ->
            if (isPortAvailable(existingPort)) {
                return existingPort
            }
        }

        // 2. Check if preferred port is available
        if (preferredPort != null && preferredPort in startPort..endPort) {
            if (isPortAvailable(preferredPort)) {
                projectPortMap[projectId] = preferredPort
                return preferredPort
            }
        }

        // 3. Scan for first free port in range
        for (candidate in startPort..endPort) {
            if (!projectPortMap.containsValue(candidate) && isPortAvailable(candidate)) {
                projectPortMap[projectId] = candidate
                return candidate
            }
        }

        throw IllegalStateException("No available ports in range $startPort..$endPort")
    }

    fun releasePortForProject(projectId: String) {
        projectPortMap.remove(projectId)
    }

    fun getPort(projectId: String): Int? = projectPortMap[projectId]

    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: IOException) {
            false
        }
    }
}
