package com.local.deploy.supervisor

import java.io.File

class ProcStatReader {

    private var lastTotalTime: Long = 0L
    private var lastProcessTime: Long = 0L

    /**
     * Reads RSS (Resident Set Size) RAM in bytes for given PID from /proc/<pid>/status
     */
    fun getMemoryRssBytes(pid: Int): Long {
        val statusFile = File("/proc/$pid/status")
        if (!statusFile.exists() || !statusFile.canRead()) return 0L

        return try {
            statusFile.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("VmRSS:")) {
                        // Example: "VmRSS:     12345 kB"
                        val parts = line.split("\\s+".toRegex())
                        val kb = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                        return@useLines kb * 1024L
                    }
                }
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Estimates CPU usage percentage for given PID using /proc/<pid>/stat and /proc/stat
     */
    fun getCpuPercent(pid: Int): Double {
        val pidStatFile = File("/proc/$pid/stat")
        val sysStatFile = File("/proc/stat")

        if (!pidStatFile.exists() || !sysStatFile.exists()) return 0.0

        return try {
            val pidStatParts = pidStatFile.readText().trim().split("\\s+".toRegex())
            // Field 14 is utime, field 15 is stime (0-indexed: 13, 14)
            val utime = pidStatParts.getOrNull(13)?.toLongOrNull() ?: 0L
            val stime = pidStatParts.getOrNull(14)?.toLongOrNull() ?: 0L
            val currentProcessTime = utime + stime

            val sysLine = sysStatFile.readLines().firstOrNull() ?: return 0.0
            val sysParts = sysLine.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
            val currentTotalTime = sysParts.sum()

            var cpuUsage = 0.0
            if (lastTotalTime > 0L && currentTotalTime > lastTotalTime) {
                val totalDelta = currentTotalTime - lastTotalTime
                val processDelta = currentProcessTime - lastProcessTime
                val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                cpuUsage = (processDelta.toDouble() / totalDelta.toDouble()) * 100.0 * numCores
            }

            lastTotalTime = currentTotalTime
            lastProcessTime = currentProcessTime

            cpuUsage.coerceIn(0.0, 100.0)
        } catch (e: Exception) {
            0.0
        }
    }
}
