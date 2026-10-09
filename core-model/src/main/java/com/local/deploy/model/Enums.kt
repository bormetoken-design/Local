package com.local.deploy.model

/**
 * Project lifecycle states as defined in the PM2/cPanel spec.
 */
enum class ProjectStatus(val label: String, val colorHex: String) {
    STOPPED("Stopped", "#757575"),      // Gray
    STARTING("Starting", "#2196F3"),    // Blue
    RUNNING("Running", "#4CAF50"),      // Green
    DEGRADED("Degraded", "#FFC107"),    // Yellow
    RECOVERING("Recovering", "#FF9800"),// Orange
    FAILED("Failed", "#F44336")         // Red
}

enum class RuntimeType(val displayName: String, val defaultExecutable: String) {
    NODEJS("Node.js", "node"),
    PHP("PHP", "php"),
    PYTHON("Python", "python3"),
    CADDY("Caddy", "caddy"),
    MARIADB("MariaDB", "mariadbd"),
    STATIC("Static HTML", "caddy"),
    CUSTOM("Custom", "sh")
}

enum class RestartPolicy {
    ALWAYS,
    ON_FAILURE,
    NEVER
}

enum class HealthCheckType {
    NONE,
    HTTP,
    TCP,
    HEARTBEAT_LOG,
    HEARTBEAT_FILE
}
