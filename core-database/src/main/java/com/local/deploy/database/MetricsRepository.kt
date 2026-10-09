package com.local.deploy.database

import com.local.deploy.model.ProcessMetric
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

data class SystemEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val severity: String, // INFO, WARN, ERROR
    val message: String
)

class MetricsRepository(
    private val maxMetricsPerProject: Int = 100
) {
    private val projectMetrics = ConcurrentHashMap<String, ConcurrentLinkedDeque<ProcessMetric>>()

    fun recordMetric(projectId: String, metric: ProcessMetric) {
        val deque = projectMetrics.getOrPut(projectId) { ConcurrentLinkedDeque() }
        deque.addLast(metric)
        while (deque.size > maxMetricsPerProject) {
            deque.pollFirst()
        }
    }

    fun getMetrics(projectId: String): List<ProcessMetric> {
        return projectMetrics[projectId]?.toList() ?: emptyList()
    }
}

class SystemEventRepository(
    private val logFile: File? = null,
    private val maxEventsInMemory: Int = 200
) {
    private val events = ConcurrentLinkedDeque<SystemEvent>()

    fun recordEvent(tag: String, severity: String, message: String) {
        val event = SystemEvent(tag = tag, severity = severity, message = message)
        events.addLast(event)
        while (events.size > maxEventsInMemory) {
            events.pollFirst()
        }

        val file = logFile
        if (file != null) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText("${event.timestamp} [${event.severity}] [${event.tag}] ${event.message}\n")
            }
        }
    }

    fun getRecentEvents(): List<SystemEvent> = events.toList().reversed()
}
