package com.local.deploy.model

/**
 * Domain model representing an application managed by Local Deploy.
 */
data class Project(
    val id: String,
    val name: String,
    val status: ProjectStatus = ProjectStatus.STOPPED,
    val config: ProjectConfig,
    val pid: Int? = null,
    val cpuPercent: Double = 0.0,
    val memoryRssBytes: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val restartCount: Int = 0,
    val lastCrashReason: String? = null,
    val projectDirPath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isRunning: Boolean
        get() = status == ProjectStatus.RUNNING || status == ProjectStatus.STARTING || status == ProjectStatus.DEGRADED

    val memoryFormatted: String
        get() {
            val mb = memoryRssBytes / (1024.0 * 1024.0)
            return if (mb < 1.0 && mb > 0) String.format("%.1f KB", memoryRssBytes / 1024.0)
            else String.format("%.1f MB", mb)
        }
}
