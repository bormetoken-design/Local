package com.local.deploy.supervisor

import com.local.deploy.model.ProjectStatus
import com.local.deploy.model.RestartPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

data class RestartAttempt(
    val consecutiveFailures: Int = 0,
    val lastCrashTime: Long = 0L,
    val nextScheduledRestartTime: Long = 0L,
    val backoffDelayMs: Long = 2000L
)

class Watchdog(
    private val maxRestarts: Int = 10,
    private val initialBackoffMs: Long = 2000L,     // 2s
    private val maxBackoffMs: Long = 300000L,       // 5m
    private val stableResetThresholdMs: Long = 600000L // 10m
) {
    private val projectAttempts = ConcurrentHashMap<String, RestartAttempt>()

    /**
     * Determines whether project should be restarted based on exit code and restart policy.
     * Returns the backoff delay in milliseconds, or -1 if restart should not occur.
     */
    fun shouldRestart(
        projectId: String,
        exitCode: Int,
        policy: RestartPolicy,
        lastStartTime: Long
    ): Long {
        if (policy == RestartPolicy.NEVER) return -1L
        if (policy == RestartPolicy.ON_FAILURE && exitCode == 0) return -1L

        val now = System.currentTimeMillis()
        val current = projectAttempts[projectId] ?: RestartAttempt()

        // If the process was stable for > stableResetThresholdMs, reset consecutive count
        val isStable = (now - lastStartTime) >= stableResetThresholdMs
        val failureCount = if (isStable) 1 else current.consecutiveFailures + 1

        if (failureCount > maxRestarts) {
            // Exceeded maximum crash count -> Mark FAILED
            return -1L
        }

        // Calculate exponential backoff: 2s, 4s, 8s, 16s, 32s...
        val backoff = min(
            maxBackoffMs,
            (initialBackoffMs * 2.0.pow((failureCount - 1).toDouble())).toLong()
        )

        projectAttempts[projectId] = RestartAttempt(
            consecutiveFailures = failureCount,
            lastCrashTime = now,
            nextScheduledRestartTime = now + backoff,
            backoffDelayMs = backoff
        )

        return backoff
    }

    fun recordSuccess(projectId: String) {
        projectAttempts.remove(projectId)
    }

    fun getFailureCount(projectId: String): Int =
        projectAttempts[projectId]?.consecutiveFailures ?: 0
}
