package com.local.deploy.model

/**
 * Configuration definition stored in project.json for each deployed app.
 */
data class HealthConfig(
    val type: HealthCheckType = HealthCheckType.HTTP,
    val endpointOrPort: String = "/",
    val intervalSeconds: Int = 30,
    val timeoutSeconds: Int = 5,
    val maxRetriesBeforeRestart: Int = 3,
    val heartbeatMaxQuietSeconds: Int = 120
)

data class ProjectConfig(
    val name: String,
    val type: RuntimeType,
    val runtimeVersion: String = "default",
    val startCommand: String,
    val workDir: String = "app",
    val port: Int = 8080,
    val bindHost: String = "127.0.0.1",
    val autostart: Boolean = false,
    val restartPolicy: RestartPolicy = RestartPolicy.ALWAYS,
    val maxRestarts: Int = 10,
    val healthConfig: HealthConfig = HealthConfig(),
    val env: Map<String, String> = emptyMap(),
    val secretKeys: List<String> = emptyList(),
    val discordWebhookUrl: String? = null
)
