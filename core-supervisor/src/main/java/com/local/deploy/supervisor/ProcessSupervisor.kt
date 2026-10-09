package com.local.deploy.supervisor

import com.local.deploy.model.HealthCheckType
import com.local.deploy.model.Project
import com.local.deploy.model.ProjectConfig
import com.local.deploy.model.ProjectStatus
import com.local.deploy.projects.env.EnvManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ProcessSupervisor(
    private val filesDir: File,
    private val portManager: PortManager = PortManager(),
    private val procStatReader: ProcStatReader = ProcStatReader(),
    private val healthChecker: HealthChecker = HealthChecker(),
    private val watchdog: Watchdog = Watchdog(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
) {
    private val usrBin = File(filesDir, "usr/bin")
    private val usrLib = File(filesDir, "usr/lib")
    private val homeDir = File(filesDir, "home").apply { mkdirs() }
    private val cacheDir = File(filesDir, "cache").apply { mkdirs() }

    private val runningProcesses = ConcurrentHashMap<String, Process>()
    private val logRotators = ConcurrentHashMap<String, LogRotator>()
    private val monitorJobs = ConcurrentHashMap<String, Job>()
    private val lastLogTimes = ConcurrentHashMap<String, Long>()
    private val startTimes = ConcurrentHashMap<String, Long>()

    private val _projects = MutableStateFlow<Map<String, Project>>(emptyMap())
    val projects: StateFlow<Map<String, Project>> = _projects.asStateFlow()

    fun registerProject(project: Project) {
        val current = _projects.value.toMutableMap()
        current[project.id] = project
        _projects.value = current
    }

    fun unregisterProject(projectId: String) {
        stopProject(projectId)
        val current = _projects.value.toMutableMap()
        current.remove(projectId)
        _projects.value = current
        portManager.releasePortForProject(projectId)
    }

    fun getLogRotator(projectId: String): LogRotator {
        return logRotators.getOrPut(projectId) {
            val proj = _projects.value[projectId]
            val dir = if (proj != null) File(proj.projectDirPath, "logs") else File(filesDir, "projects/$projectId/logs")
            LogRotator(dir)
        }
    }

    @Synchronized
    fun startProject(projectId: String): Boolean {
        val project = _projects.value[projectId] ?: return false
        if (project.isRunning) return true

        updateProjectStatus(projectId, ProjectStatus.STARTING)

        val port = portManager.allocatePortForProject(projectId, project.config.port)
        val projectDir = File(project.projectDirPath)
        val appWorkDir = File(projectDir, project.config.workDir).takeIf { it.exists() } ?: projectDir
        val rotator = getLogRotator(projectId)

        rotator.append("Initiating process launch for ${project.name} on port $port...")

        try {
            // 1. Resolve start command and placeholders
            val resolvedCommand = EnvManager.resolvePlaceholders(project.config.startCommand, port, project.config.bindHost)
            val commandTokens = resolvedCommand.split("\\s+".toRegex()).filter { it.isNotBlank() }

            val processBuilder = ProcessBuilder(commandTokens)
            processBuilder.directory(appWorkDir)

            // 2. Set environment variables
            val env = processBuilder.environment()
            val existingPath = System.getenv("PATH") ?: "/system/bin"
            env["PATH"] = "${usrBin.absolutePath}:$existingPath"
            env["LD_LIBRARY_PATH"] = usrLib.absolutePath
            env["HOME"] = homeDir.absolutePath
            env["TMPDIR"] = cacheDir.absolutePath
            env["PORT"] = port.toString()
            env["HOST"] = project.config.bindHost

            // Load .env variables
            val envFile = File(projectDir, ".env")
            if (envFile.exists()) {
                val parsedEnv = EnvManager.parse(envFile)
                parsedEnv.forEach { (k, v) ->
                    env[k] = EnvManager.resolvePlaceholders(v, port, project.config.bindHost)
                }
            }
            project.config.env.forEach { (k, v) ->
                env[k] = EnvManager.resolvePlaceholders(v, port, project.config.bindHost)
            }

            // 3. Spawn process
            val process = processBuilder.start()
            val pid = extractPid(process)
            runningProcesses[projectId] = process
            val startTime = System.currentTimeMillis()
            startTimes[projectId] = startTime
            lastLogTimes[projectId] = startTime

            rotator.append("Process spawned successfully [PID: $pid]")

            // Update project with PID and Port
            updateProject(projectId) { it.copy(pid = pid, config = it.config.copy(port = port)) }

            // 4. Capture STDOUT and STDERR
            pipeStream(process.inputStream, projectId, rotator, isStderr = false)
            pipeStream(process.errorStream, projectId, rotator, isStderr = true)

            // 5. Start background monitoring & health check job
            val job = scope.launch {
                monitorProcess(projectId, process, pid, startTime)
            }
            monitorJobs[projectId] = job

            return true
        } catch (e: Exception) {
            rotator.append("Failed to start process: ${e.message}", isStderr = true)
            updateProjectStatus(projectId, ProjectStatus.FAILED, reason = e.message)
            return false
        }
    }

    private fun pipeStream(inputStream: java.io.InputStream, projectId: String, rotator: LogRotator, isStderr: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        rotator.append(line, isStderr = isStderr)
                        lastLogTimes[projectId] = System.currentTimeMillis()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun monitorProcess(projectId: String, process: Process, pid: Int?, startTime: Long) {
        var consecutiveHealthFails = 0
        var isHealthy = false

        while (scope.isActive && process.isAlive) {
            // Update CPU and RAM metrics
            if (pid != null) {
                val cpu = procStatReader.getCpuPercent(pid)
                val rss = procStatReader.getMemoryRssBytes(pid)
                val uptime = (System.currentTimeMillis() - startTime) / 1000

                updateProject(projectId) {
                    it.copy(
                        cpuPercent = cpu,
                        memoryRssBytes = rss,
                        uptimeSeconds = uptime
                    )
                }
            }

            // Run Health Check
            val proj = _projects.value[projectId] ?: break
            val lastLogTime = lastLogTimes[projectId] ?: startTime
            val healthCheckOk = healthChecker.checkHealth(
                config = proj.config.healthConfig,
                port = proj.config.port,
                lastLogTimestamp = lastLogTime
            )

            if (healthCheckOk) {
                consecutiveHealthFails = 0
                if (!isHealthy) {
                    isHealthy = true
                    updateProjectStatus(projectId, ProjectStatus.RUNNING)
                    watchdog.recordSuccess(projectId)
                }
            } else {
                consecutiveHealthFails++
                if (consecutiveHealthFails >= proj.config.healthConfig.maxRetriesBeforeRestart) {
                    getLogRotator(projectId).append("Health check failed $consecutiveHealthFails consecutive times. Triggering restart.", isStderr = true)
                    updateProjectStatus(projectId, ProjectStatus.DEGRADED)
                    restartProject(projectId)
                    return
                }
            }

            delay(3000L) // Poll every 3 seconds
        }

        // Process exited
        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        runningProcesses.remove(projectId)
        val proj = _projects.value[projectId]
        val rotator = getLogRotator(projectId)
        rotator.append("Process exited with code $exitCode")

        if (proj != null && proj.status != ProjectStatus.STOPPED) {
            handleProcessCrash(projectId, exitCode, startTime)
        }
    }

    private fun handleProcessCrash(projectId: String, exitCode: Int, startTime: Long) {
        val proj = _projects.value[projectId] ?: return
        val backoff = watchdog.shouldRestart(projectId, exitCode, proj.config.restartPolicy, startTime)

        if (backoff > 0) {
            updateProjectStatus(projectId, ProjectStatus.RECOVERING, reason = "Crashed with exit code $exitCode")
            getLogRotator(projectId).append("Watchdog: Scheduling restart in ${backoff / 1000}s...")

            scope.launch {
                delay(backoff)
                if (_projects.value[projectId]?.status == ProjectStatus.RECOVERING) {
                    startProject(projectId)
                }
            }
        } else {
            getLogRotator(projectId).append("Watchdog: Max restart limit reached or policy prohibits restart.", isStderr = true)
            updateProjectStatus(projectId, ProjectStatus.FAILED, reason = "Crashed with exit code $exitCode")
        }
    }

    @Synchronized
    fun stopProject(projectId: String): Boolean {
        monitorJobs.remove(projectId)?.cancel()
        val process = runningProcesses.remove(projectId)
        val rotator = getLogRotator(projectId)

        if (process != null && process.isAlive) {
            rotator.append("Sending graceful termination signal (SIGTERM)...")
            process.destroy()

            try {
                // Wait up to 10 seconds for graceful shutdown
                val exited = process.waitFor(10, TimeUnit.SECONDS)
                if (!exited) {
                    rotator.append("Graceful shutdown timed out. Forcing termination (SIGKILL)...", isStderr = true)
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                process.destroyForcibly()
            }
        }

        updateProjectStatus(projectId, ProjectStatus.STOPPED)
        updateProject(projectId) { it.copy(pid = null, cpuPercent = 0.0, memoryRssBytes = 0L) }
        rotator.append("Project stopped.")
        return true
    }

    fun restartProject(projectId: String): Boolean {
        stopProject(projectId)
        return startProject(projectId)
    }

    private fun updateProjectStatus(projectId: String, status: ProjectStatus, reason: String? = null) {
        updateProject(projectId) {
            it.copy(
                status = status,
                lastCrashReason = reason ?: it.lastCrashReason,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun updateProject(projectId: String, transform: (Project) -> Project) {
        val current = _projects.value.toMutableMap()
        current[projectId]?.let {
            current[projectId] = transform(it)
            _projects.value = current
        }
    }

    private fun extractPid(process: Process): Int? {
        return try {
            // Java 9+ supports process.pid()
            val method = process.javaClass.getMethod("pid")
            (method.invoke(process) as Long).toInt()
        } catch (e: Exception) {
            try {
                // Fallback for UNIXProcess reflection
                val field: Field = process.javaClass.getDeclaredField("pid")
                field.isAccessible = true
                field.getInt(process)
            } catch (e2: Exception) {
                null
            }
        }
    }
}
