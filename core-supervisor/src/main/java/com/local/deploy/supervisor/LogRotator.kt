package com.local.deploy.supervisor

import com.local.deploy.model.LogEntry
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

class LogRotator(
    private val logDir: File,
    private val maxFileSizeBytes: Long = 2 * 1024 * 1024, // 2 MB
    private val maxBackupFiles: Int = 5,
    private val memoryBufferSize: Int = 500
) {
    private val activeLogFile = File(logDir, "project.log")
    private val liveBuffer = ConcurrentLinkedDeque<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var writer: PrintWriter? = null

    init {
        logDir.mkdirs()
        openWriter()
    }

    @Synchronized
    private fun openWriter() {
        writer?.close()
        writer = PrintWriter(
            OutputStreamWriter(
                FileOutputStream(activeLogFile, true),
                StandardCharsets.UTF_8
            ),
            true
        )
    }

    @Synchronized
    fun append(message: String, isStderr: Boolean = false) {
        val timestamp = System.currentTimeMillis()
        val entry = LogEntry(timestamp = timestamp, isStderr = isStderr, message = message)

        // Maintain in-memory ring buffer for instant Compose UI display
        liveBuffer.addLast(entry)
        while (liveBuffer.size > memoryBufferSize) {
            liveBuffer.pollFirst()
        }

        // Format for disk
        val timeStr = dateFormat.format(Date(timestamp))
        val prefix = if (isStderr) "[STDERR]" else "[STDOUT]"
        val line = "$timeStr $prefix $message"

        writer?.println(line)

        // Check file size for rotation
        if (activeLogFile.length() >= maxFileSizeBytes) {
            rotate()
        }
    }

    @Synchronized
    private fun rotate() {
        writer?.flush()
        writer?.close()
        writer = null

        // Shift existing backup files: project.log.4 -> project.log.5 (deleted), ..., project.log.1 -> project.log.2
        for (i in (maxBackupFiles - 1) downTo 1) {
            val oldFile = File(logDir, "project.log.$i")
            if (oldFile.exists()) {
                val newFile = File(logDir, "project.log.${i + 1}")
                if (newFile.exists()) newFile.delete()
                oldFile.renameTo(newFile)
            }
        }

        // Rotate current file to project.log.1
        val firstBackup = File(logDir, "project.log.1")
        if (firstBackup.exists()) firstBackup.delete()
        activeLogFile.renameTo(firstBackup)

        openWriter()
    }

    fun getRecentLogs(): List<LogEntry> = liveBuffer.toList()

    @Synchronized
    fun clearLogs() {
        liveBuffer.clear()
        writer?.close()
        activeLogFile.delete()
        for (i in 1..maxBackupFiles) {
            File(logDir, "project.log.$i").delete()
        }
        openWriter()
    }

    @Synchronized
    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }
}
