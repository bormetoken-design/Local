package com.local.deploy.supervisor

import com.local.deploy.model.HealthCheckType
import com.local.deploy.model.HealthConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class HealthChecker {

    suspend fun checkHealth(
        config: HealthConfig,
        port: Int,
        lastLogTimestamp: Long = 0L,
        heartbeatFile: File? = null
    ): Boolean = withContext(Dispatchers.IO) {
        when (config.type) {
            HealthCheckType.NONE -> true

            HealthCheckType.HTTP -> {
                try {
                    val endpoint = if (config.endpointOrPort.startsWith("/")) config.endpointOrPort else "/${config.endpointOrPort}"
                    val url = URL("http://127.0.0.1:$port$endpoint")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = config.timeoutSeconds * 1000
                    conn.readTimeout = config.timeoutSeconds * 1000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    code in 200..399
                } catch (e: Exception) {
                    false
                }
            }

            HealthCheckType.TCP -> {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), config.timeoutSeconds * 1000)
                        socket.isConnected
                    }
                } catch (e: Exception) {
                    false
                }
            }

            HealthCheckType.HEARTBEAT_LOG -> {
                val quietDurationSec = (System.currentTimeMillis() - lastLogTimestamp) / 1000
                quietDurationSec <= config.heartbeatMaxQuietSeconds
            }

            HealthCheckType.HEARTBEAT_FILE -> {
                if (heartbeatFile != null && heartbeatFile.exists()) {
                    val quietDurationSec = (System.currentTimeMillis() - heartbeatFile.lastModified()) / 1000
                    quietDurationSec <= config.heartbeatMaxQuietSeconds
                } else {
                    false
                }
            }
        }
    }
}
