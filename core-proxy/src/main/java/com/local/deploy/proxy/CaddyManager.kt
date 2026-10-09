package com.local.deploy.proxy

import com.local.deploy.model.Project
import com.local.deploy.model.RuntimeType
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ProxyRoute(
    val projectName: String,
    val internalPort: Int,
    val pathPrefix: String,
    val allowLan: Boolean = false
)

object CaddyManager {

    /**
     * Builds Caddyfile content from active running web projects.
     */
    fun generateCaddyfile(
        activeProjects: List<Project>,
        globalPort: Int = 8080,
        enableLan: Boolean = false
    ): String {
        val listenHost = if (enableLan) "0.0.0.0" else "127.0.0.1"
        val sb = StringBuilder()

        // Global options block
        sb.append("{\n")
        sb.append("    admin 127.0.0.1:2019\n")
        sb.append("    auto_https off\n")
        sb.append("}\n\n")

        // Main HTTP server block
        sb.append("$listenHost:$globalPort {\n")

        val webProjects = activeProjects.filter {
            it.config.type != RuntimeType.CUSTOM || it.config.port > 0
        }

        if (webProjects.isEmpty()) {
            sb.append("    respond \"Android Local Deploy Manager: No web services currently active.\" 200\n")
        } else {
            webProjects.forEach { proj ->
                val safePath = proj.name.lowercase().replace("[^a-z0-9_-]".toRegex(), "")
                val port = proj.config.port
                sb.append("    # Route for project: ${proj.name}\n")
                sb.append("    handle_path /$safePath* {\n")
                sb.append("        reverse_proxy 127.0.0.1:$port\n")
                sb.append("    }\n\n")
            }

            sb.append("    handle {\n")
            sb.append("        respond \"Android Local Deploy Manager is online.\" 200\n")
            sb.append("    }\n")
        }

        sb.append("}\n")
        return sb.toString()
    }

    /**
     * Saves generated Caddyfile to services/caddy/Caddyfile
     */
    fun saveCaddyfile(caddyfile: File, content: String) {
        caddyfile.parentFile?.mkdirs()
        caddyfile.writeText(content)
    }

    /**
     * Triggers zero-downtime hot-reload using Caddy Admin API.
     */
    fun reloadViaApi(adminApiUrl: String = "http://127.0.0.1:2019/load", caddyfileContent: String): Boolean {
        return try {
            val url = URL(adminApiUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/caddyfile")
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 5000

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(caddyfileContent)
                writer.flush()
            }

            val responseCode = conn.responseCode
            responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if Caddy admin API is responsive.
     */
    fun isApiRunning(adminApiUrl: String = "http://127.0.0.1:2019/config/"): Boolean {
        return try {
            val url = URL(adminApiUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Starts the Caddy server process in the background.
     */
    fun startCaddyProcess(
        caddyBinary: File,
        caddyfile: File,
        workingDir: File,
        env: Map<String, String> = emptyMap()
    ): Process {
        workingDir.mkdirs()
        val pb = ProcessBuilder(
            caddyBinary.absolutePath,
            "run",
            "--config",
            caddyfile.absolutePath,
            "--adapter",
            "caddyfile"
        )
        pb.directory(workingDir)
        pb.redirectErrorStream(true)
        val processEnv = pb.environment()
        env.forEach { (k, v) -> processEnv[k] = v }
        return pb.start()
    }

    /**
     * Reloads Caddy configuration using CLI command as a fallback.
     */
    fun reloadViaCli(
        caddyBinary: File,
        caddyfile: File,
        env: Map<String, String> = emptyMap()
    ): Boolean {
        if (!caddyBinary.exists() || !caddyfile.exists()) return false
        return try {
            val pb = ProcessBuilder(
                caddyBinary.absolutePath,
                "reload",
                "--config",
                caddyfile.absolutePath,
                "--adapter",
                "caddyfile"
            )
            pb.directory(caddyfile.parentFile ?: caddyBinary.parentFile)
            pb.redirectErrorStream(true)
            val processEnv = pb.environment()
            env.forEach { (k, v) -> processEnv[k] = v }
            val process = pb.start()
            val completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            completed && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Stops the Caddy process cleanly.
     */
    fun stopCaddyProcess(caddyProcess: Process?) {
        try {
            caddyProcess?.destroy()
        } catch (_: Exception) {}
        try {
            val url = URL("http://127.0.0.1:2019/stop")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
