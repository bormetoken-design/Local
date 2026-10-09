package com.local.deploy.model

/**
 * Runtime package metadata (Node, PHP, Python, Caddy, MariaDB).
 */
data class RuntimePackage(
    val id: String,
    val name: String,
    val type: RuntimeType,
    val version: String,
    val abi: String, // e.g. aarch64, x86_64
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val dependencies: List<String> = emptyList(),
    val isInstalled: Boolean = false,
    val installedVersion: String? = null,
    val binaryPath: String? = null,
    val isUpdateAvailable: Boolean = false
) {
    val sizeFormatted: String
        get() = String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0))
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val isStderr: Boolean = false,
    val message: String
)

data class ProcessMetric(
    val timestamp: Long = System.currentTimeMillis(),
    val pid: Int,
    val cpuPercent: Double,
    val memoryRssBytes: Long
)

enum class HealthFixAction {
    OPEN_BATTERY_SETTINGS,
    OPEN_AUTOSTART_SETTINGS,
    REQUEST_NOTIFICATION_PERMISSION,
    RUN_ADB_PAIRING,
    CLEAN_DISK_SPACE,
    NONE
}

data class SystemHealthItem(
    val id: String,
    val title: String,
    val description: String,
    val isPassed: Boolean,
    val severity: String = "HIGH", // HIGH, MEDIUM, LOW
    val fixAction: HealthFixAction = HealthFixAction.NONE
)

data class SystemHealthReport(
    val score: Int, // 0 - 100
    val totalChecks: Int,
    val passedChecks: Int,
    val items: List<SystemHealthItem>,
    val recentIncidents: List<String> = emptyList()
)
