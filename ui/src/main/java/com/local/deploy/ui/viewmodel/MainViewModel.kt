package com.local.deploy.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.deploy.antikill.KeepAliveHelper
import com.local.deploy.database.ProjectRepository
import com.local.deploy.model.HealthConfig
import com.local.deploy.model.LogEntry
import com.local.deploy.model.Project
import com.local.deploy.model.ProjectConfig
import com.local.deploy.model.ProjectStatus
import com.local.deploy.model.RestartPolicy
import com.local.deploy.model.RuntimePackage
import com.local.deploy.model.RuntimeType
import com.local.deploy.model.SystemHealthReport
import com.local.deploy.packages.PackageManager
import com.local.deploy.projects.detector.DetectionResult
import com.local.deploy.projects.detector.ProjectDetector
import com.local.deploy.projects.env.EnvManager
import com.local.deploy.projects.manager.ProjectFileManager
import com.local.deploy.projects.template.ProjectTemplate
import com.local.deploy.proxy.CaddyManager
import com.local.deploy.supervisor.ProcessSupervisor
import com.local.deploy.ui.screens.deploy.AutoDeployState
import com.local.deploy.ui.screens.deploy.DeployStepItem
import com.local.deploy.ui.screens.deploy.DeployStepStatus
import com.local.deploy.ui.util.SafImportHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class MainUiState(
    val projects: List<Project> = emptyList(),
    val runtimePackages: List<RuntimePackage> = emptyList(),
    val healthReport: SystemHealthReport = KeepAliveHelper.evaluateSystemHealth(
        isBatteryOptimizedExempt = false,
        isNotificationPermissionGranted = true,
        isAutostartEnabled = false,
        isPhantomKillerDisabled = false,
        availableStorageBytes = 10L * 1024 * 1024 * 1024,
        availableRamBytes = 3L * 1024 * 1024 * 1024
    ),
    val isOnboardingCompleted: Boolean = true,
    val selectedProjectId: String? = null,
    val activeLogs: List<LogEntry> = emptyList(),
    val isInstallingPackageId: String? = null,
    val installProgressPercent: Int = 0,
    val discordWebhookUrl: String = "",
    val isBiometricEnabled: Boolean = false,
    val isAutoBackupEnabled: Boolean = false,
    val activeDetectionResult: DetectionResult? = null,
    val autoDeployState: AutoDeployState = AutoDeployState()
)

class MainViewModel(
    private val filesDir: File,
    private val projectRepository: ProjectRepository,
    private val processSupervisor: ProcessSupervisor,
    private val packageManager: PackageManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = combine(
        _uiState,
        processSupervisor.projects,
        projectRepository.getAllProjectsFlow()
    ) { state, supervisorProjects, repoProjects ->
        // Merge persistent database projects with live supervisor metrics
        val mergedProjects = repoProjects.map { repoProj ->
            supervisorProjects[repoProj.id] ?: repoProj
        }
        state.copy(projects = mergedProjects)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainUiState())

    init {
        loadRuntimePackages()
        loadInitialProjects()
    }

    private fun loadInitialProjects() {
        viewModelScope.launch {
            val list = projectRepository.getAllProjects()
            list.forEach { processSupervisor.registerProject(it) }
        }
    }

    private fun loadRuntimePackages() {
        val abi = packageManager.detectDeviceAbi()
        val defaultPackages = listOf(
            RuntimePackage(
                id = "nodejs",
                name = "Node.js (LTS)",
                type = RuntimeType.NODEJS,
                version = "20.14.0",
                abi = abi,
                sizeBytes = 38L * 1024 * 1024,
                sha256 = "",
                downloadUrl = "",
                isInstalled = packageManager.isBinaryInstalled("node"),
                installedVersion = packageManager.getBinaryVersion("node")
            ),
            RuntimePackage(
                id = "python",
                name = "Python 3.11",
                type = RuntimeType.PYTHON,
                version = "3.11.9",
                abi = abi,
                sizeBytes = 42L * 1024 * 1024,
                sha256 = "",
                downloadUrl = "",
                isInstalled = packageManager.isBinaryInstalled("python3"),
                installedVersion = packageManager.getBinaryVersion("python3")
            ),
            RuntimePackage(
                id = "php",
                name = "PHP 8.2 (CLI + FPM)",
                type = RuntimeType.PHP,
                version = "8.2.18",
                abi = abi,
                sizeBytes = 25L * 1024 * 1024,
                sha256 = "",
                downloadUrl = "",
                isInstalled = packageManager.isBinaryInstalled("php"),
                installedVersion = packageManager.getBinaryVersion("php")
            ),
            RuntimePackage(
                id = "caddy",
                name = "Caddy Reverse Proxy",
                type = RuntimeType.CADDY,
                version = "2.8.4",
                abi = abi,
                sizeBytes = 18L * 1024 * 1024,
                sha256 = "",
                downloadUrl = "",
                isInstalled = packageManager.isBinaryInstalled("caddy"),
                installedVersion = packageManager.getBinaryVersion("caddy")
            ),
            RuntimePackage(
                id = "mariadb",
                name = "MariaDB Server",
                type = RuntimeType.MARIADB,
                version = "10.11.6",
                abi = abi,
                sizeBytes = 65L * 1024 * 1024,
                sha256 = "",
                downloadUrl = "",
                isInstalled = packageManager.isBinaryInstalled("mariadbd"),
                installedVersion = packageManager.getBinaryVersion("mariadbd")
            )
        )
        _uiState.value = _uiState.value.copy(runtimePackages = defaultPackages)
    }

    fun startProject(projectId: String) {
        viewModelScope.launch {
            processSupervisor.startProject(projectId)
            reloadCaddyProxy()
        }
    }

    fun stopProject(projectId: String) {
        viewModelScope.launch {
            processSupervisor.stopProject(projectId)
            reloadCaddyProxy()
        }
    }

    fun restartProject(projectId: String) {
        viewModelScope.launch {
            processSupervisor.restartProject(projectId)
            reloadCaddyProxy()
        }
    }

    fun selectProject(projectId: String?) {
        _uiState.value = _uiState.value.copy(selectedProjectId = projectId)
        if (projectId != null) {
            val logs = processSupervisor.getLogRotator(projectId).getRecentLogs()
            _uiState.value = _uiState.value.copy(activeLogs = logs)
        }
    }

    fun clearLogs(projectId: String) {
        processSupervisor.getLogRotator(projectId).clearLogs()
        _uiState.value = _uiState.value.copy(activeLogs = emptyList())
    }

    fun saveEnv(projectId: String, newEnv: Map<String, String>) {
        viewModelScope.launch {
            val project = projectRepository.getProjectById(projectId) ?: return@launch
            val updatedConfig = project.config.copy(env = newEnv)
            val updatedProject = project.copy(config = updatedConfig)

            val envFile = File(project.projectDirPath, ".env")
            EnvManager.write(envFile, newEnv)
            projectRepository.saveProject(updatedProject)
            processSupervisor.registerProject(updatedProject)
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            processSupervisor.unregisterProject(projectId)
            val project = projectRepository.getProjectById(projectId)
            if (project != null) {
                File(project.projectDirPath).deleteRecursively()
                projectRepository.deleteProject(projectId)
            }
            selectProject(null)
            reloadCaddyProxy()
        }
    }

    fun detectFromFolder(folder: File) {
        val result = ProjectDetector.detect(folder)
        _uiState.value = _uiState.value.copy(activeDetectionResult = result)
    }

    fun createProjectFromTemplate(template: ProjectTemplate) {
        val id = UUID.randomUUID().toString()
        val projectBaseDir = File(filesDir, "projects/$id")
        val dirs = ProjectFileManager.createProjectDirectoryStructure(projectBaseDir)

        template.files.forEach { (rel, content) ->
            val file = File(dirs.appDir, rel)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }

        val result = ProjectDetector.detect(dirs.appDir)
        _uiState.value = _uiState.value.copy(activeDetectionResult = result)
    }

    fun finalizeCreateProject(name: String, command: String, port: Int, autostart: Boolean, env: Map<String, String>) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val projectBaseDir = File(filesDir, "projects/$id")
            val dirs = ProjectFileManager.createProjectDirectoryStructure(projectBaseDir)

            val envFile = File(projectBaseDir, ".env")
            EnvManager.write(envFile, env)

            val config = ProjectConfig(
                name = name,
                type = _uiState.value.activeDetectionResult?.detectedType ?: RuntimeType.CUSTOM,
                startCommand = command,
                port = port,
                autostart = autostart,
                restartPolicy = RestartPolicy.ALWAYS,
                env = env
            )

            val project = Project(
                id = id,
                name = name,
                status = ProjectStatus.STOPPED,
                config = config,
                projectDirPath = projectBaseDir.absolutePath
            )

            projectRepository.saveProject(project)
            processSupervisor.registerProject(project)
            processSupervisor.startProject(id)
            reloadCaddyProxy()
        }
    }

    private fun reloadCaddyProxy() {
        val active = _uiState.value.projects.filter { it.status == ProjectStatus.RUNNING }
        val caddyfile = CaddyManager.generateCaddyfile(active, globalPort = 8080)
        CaddyManager.reloadViaApi(caddyfileContent = caddyfile)
    }

    fun dismissDeployDialog() {
        _uiState.update { it.copy(autoDeployState = it.autoDeployState.copy(isVisible = false)) }
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun appendDeployLog(msg: String) {
        val line = "[${getCurrentTimestamp()}] $msg"
        _uiState.update { state ->
            val logs = state.autoDeployState.terminalLogs + line
            state.copy(autoDeployState = state.autoDeployState.copy(terminalLogs = logs))
        }
    }

    private fun updateStepStatus(stepNumber: Int, status: DeployStepStatus) {
        _uiState.update { state ->
            val steps = state.autoDeployState.steps.map { step ->
                if (step.stepNumber == stepNumber) step.copy(status = status) else step
            }
            state.copy(autoDeployState = state.autoDeployState.copy(steps = steps))
        }
    }

    private fun defaultDeploySteps(activeStep: Int = 1): List<DeployStepItem> = listOf(
        DeployStepItem(1, "Extract Archive", "Safely unzipping to project sandbox", if (activeStep == 1) DeployStepStatus.RUNNING else DeployStepStatus.PENDING),
        DeployStepItem(2, "Detect Runtime", "Inspecting language, entrypoint & framework", DeployStepStatus.PENDING),
        DeployStepItem(3, "Configure Environment", "Allocating port & generating .env", DeployStepStatus.PENDING),
        DeployStepItem(4, "Install Dependencies", "Resolving packages and libraries (npm/pip)", DeployStepStatus.PENDING),
        DeployStepItem(5, "Launch & Supervise", "Starting supervisor process & Caddy reverse proxy", DeployStepStatus.PENDING)
    )

    fun autoDeployFromZip(context: Context, zipUri: Uri) {
        val displayName = SafImportHelper.getDisplayName(context, zipUri)
        val nameFallback = displayName.substringBeforeLast(".").ifBlank { "project-${System.currentTimeMillis() % 1000}" }

        _uiState.update {
            it.copy(
                autoDeployState = AutoDeployState(
                    isVisible = true,
                    isRunning = true,
                    projectName = nameFallback,
                    steps = defaultDeploySteps(1),
                    terminalLogs = listOf(
                        "[${getCurrentTimestamp()}] Initiating 1-Click Auto-Deploy...",
                        "[${getCurrentTimestamp()}] Selected archive: $displayName"
                    )
                )
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val projectBaseDir = File(filesDir, "projects/$id")
            val dirs = ProjectFileManager.createProjectDirectoryStructure(projectBaseDir)

            try {
                // Step 1: Unpack archive
                appendDeployLog("[EXTRACT] [1/5] Extracting ZIP to sandbox...")
                SafImportHelper.unpackZip(context, zipUri, dirs.appDir)
                appendDeployLog("[OK] [1/5] Unpacking completed.")
                updateStepStatus(1, DeployStepStatus.COMPLETED)

                runDeployStagesFromExtractedDir(id, projectBaseDir, dirs.appDir, nameFallback)
            } catch (e: Exception) {
                handleDeployError(e)
            }
        }
    }

    fun autoDeployFromFolder(context: Context, folderUri: Uri) {
        val displayName = SafImportHelper.getDisplayName(context, folderUri)
        val nameFallback = displayName.ifBlank { "project-${System.currentTimeMillis() % 1000}" }

        _uiState.update {
            it.copy(
                autoDeployState = AutoDeployState(
                    isVisible = true,
                    isRunning = true,
                    projectName = nameFallback,
                    steps = defaultDeploySteps(1),
                    terminalLogs = listOf(
                        "[${getCurrentTimestamp()}] Initiating 1-Click Auto-Deploy...",
                        "[${getCurrentTimestamp()}] Selected folder: $displayName"
                    )
                )
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val projectBaseDir = File(filesDir, "projects/$id")
            val dirs = ProjectFileManager.createProjectDirectoryStructure(projectBaseDir)

            try {
                // Step 1: Copy folder
                appendDeployLog("[COPY] [1/5] Copying directory tree to sandbox...")
                SafImportHelper.copyFolderTree(context, folderUri, dirs.appDir)
                appendDeployLog("[OK] [1/5] Folder import completed.")
                updateStepStatus(1, DeployStepStatus.COMPLETED)

                runDeployStagesFromExtractedDir(id, projectBaseDir, dirs.appDir, nameFallback)
            } catch (e: Exception) {
                handleDeployError(e)
            }
        }
    }

    private suspend fun runDeployStagesFromExtractedDir(
        id: String,
        projectBaseDir: File,
        appDir: File,
        nameFallback: String
    ) {
        // Step 2: Detect Runtime
        updateStepStatus(2, DeployStepStatus.RUNNING)
        appendDeployLog("[DETECT] [2/5] Inspecting project files...")
        val detection = ProjectDetector.detect(appDir)
        val finalName = if (detection.suggestedName != "app" && detection.suggestedName.isNotBlank()) {
            detection.suggestedName
        } else {
            nameFallback
        }
        appendDeployLog("[OK] [2/5] Detected stack: ${detection.description}")
        appendDeployLog("[TARGET] [2/5] Command: ${detection.suggestedCommand}")
        updateStepStatus(2, DeployStepStatus.COMPLETED)

        // Step 3: Configure Port & Environment
        updateStepStatus(3, DeployStepStatus.RUNNING)
        val allocatedPort = processSupervisor.portManager.allocatePortForProject(id, detection.suggestedPort)
        appendDeployLog("[PORT] [3/5] Dedicated port allocated: $allocatedPort")

        val envFile = File(projectBaseDir, ".env")
        val envMap = mutableMapOf<String, String>()

        val exampleEnv = File(appDir, ".env.example").takeIf { it.exists() }
            ?: File(appDir, ".env.sample").takeIf { it.exists() }
            ?: File(appDir, "example.env").takeIf { it.exists() }

        if (exampleEnv != null) {
            envMap.putAll(EnvManager.parse(exampleEnv))
            appendDeployLog("[CONFIG] [3/5] Auto-imported variables from ${exampleEnv.name}")
        }

        val existingAppEnv = File(appDir, ".env")
        if (existingAppEnv.exists()) {
            envMap.putAll(EnvManager.parse(existingAppEnv))
        }

        envMap["PORT"] = allocatedPort.toString()
        envMap["HOST"] = "0.0.0.0"

        EnvManager.write(envFile, envMap)
        EnvManager.write(File(appDir, ".env"), envMap)

        var missingDiscordToken = false
        if (detection.description.contains("Discord", ignoreCase = true)) {
            val token = envMap["DISCORD_TOKEN"]
            if (token.isNullOrBlank() || token.contains("YOUR_BOT_TOKEN")) {
                missingDiscordToken = true
                appendDeployLog("[WARN] [3/5] Discord Bot detected without DISCORD_TOKEN. Configure in project settings.")
            }
        }
        appendDeployLog("[OK] [3/5] Environment configured (.env ready).")
        updateStepStatus(3, DeployStepStatus.COMPLETED)

        // Step 4: Install Dependencies
        updateStepStatus(4, DeployStepStatus.RUNNING)
        appendDeployLog("[DEPS] [4/5] Checking and installing dependencies...")
        installProjectDependencies(appDir, detection)
        appendDeployLog("[OK] [4/5] Dependency stage completed.")
        updateStepStatus(4, DeployStepStatus.COMPLETED)

        // Step 5: Start & Proxy
        updateStepStatus(5, DeployStepStatus.RUNNING)
        appendDeployLog("[SUPERVISOR] [5/5] Registering project in ProcessSupervisor...")
        val config = ProjectConfig(
            name = finalName,
            type = detection.detectedType,
            startCommand = detection.suggestedCommand,
            port = allocatedPort,
            autostart = true,
            restartPolicy = RestartPolicy.ALWAYS,
            env = envMap
        )
        val project = Project(
            id = id,
            name = finalName,
            status = ProjectStatus.STOPPED,
            config = config,
            projectDirPath = projectBaseDir.absolutePath
        )
        projectRepository.saveProject(project)
        processSupervisor.registerProject(project)

        appendDeployLog("[START] [5/5] Launching background supervisor process...")
        val started = processSupervisor.startProject(id)
        if (started) {
            appendDeployLog("[OK] [5/5] Process started successfully.")
        } else {
            appendDeployLog("[WARN] [5/5] Process registered. Check live logs for output.")
        }

        reloadCaddyProxy()
        appendDeployLog("[PROXY] [5/5] Proxy running on port $allocatedPort (http://localhost:$allocatedPort)")
        updateStepStatus(5, DeployStepStatus.COMPLETED)

        appendDeployLog("[OK] DEPLOYMENT COMPLETED: Project is live and running.")

        _uiState.update {
            it.copy(
                autoDeployState = it.autoDeployState.copy(
                    isRunning = false,
                    isSuccess = true,
                    deployedProjectId = id,
                    deployedPort = allocatedPort,
                    projectName = finalName,
                    isDiscordBotWithoutToken = missingDiscordToken
                )
            )
        }
    }

    private fun installProjectDependencies(appDir: File, detection: DetectionResult) {
        val packageJson = File(appDir, "package.json")
        val reqTxt = File(appDir, "requirements.txt")
        val composerJson = File(appDir, "composer.json")

        if (packageJson.exists()) {
            val nodeModules = File(appDir, "node_modules")
            if (nodeModules.exists() && (nodeModules.listFiles()?.size ?: 0) > 0) {
                appendDeployLog("   [INFO] node_modules already bundled in archive. Skipping npm install.")
            } else {
                val npmBinary = File(processSupervisor.usrBin, "npm")
                if (npmBinary.exists() && npmBinary.canExecute()) {
                    appendDeployLog("   [DEPS] Running 'npm install --omit=dev --no-audit'...")
                    val exit = processSupervisor.executeOneShot(
                        listOf(npmBinary.absolutePath, "install", "--omit=dev", "--no-audit"),
                        appDir
                    ) { line ->
                        appendDeployLog("   [npm] $line")
                    }
                    if (exit == 0) {
                        appendDeployLog("   [OK] npm packages installed successfully.")
                    } else {
                        appendDeployLog("   [WARN] npm install exited with code $exit.")
                    }
                } else {
                    appendDeployLog("   [INFO] Node.js project detected. If external packages are needed, install Node.js from Runtimes tab or bundle node_modules.")
                }
            }
        } else if (reqTxt.exists()) {
            val pipBinary = File(processSupervisor.usrBin, "pip3").takeIf { it.exists() && it.canExecute() }
                ?: File(processSupervisor.usrBin, "pip").takeIf { it.exists() && it.canExecute() }
            if (pipBinary != null) {
                appendDeployLog("   [DEPS] Running 'pip install -r requirements.txt'...")
                val exit = processSupervisor.executeOneShot(
                    listOf(pipBinary.absolutePath, "install", "-r", "requirements.txt", "--no-cache-dir"),
                    appDir
                ) { line ->
                    appendDeployLog("   [pip] $line")
                }
                if (exit == 0) {
                    appendDeployLog("   [OK] Python requirements installed successfully.")
                } else {
                    appendDeployLog("   [WARN] pip install exited with code $exit.")
                }
            } else {
                appendDeployLog("   [INFO] Python requirements.txt found. Ensure Python runtime is installed from Runtimes tab.")
            }
        } else if (composerJson.exists()) {
            val composerBinary = File(processSupervisor.usrBin, "composer")
            if (composerBinary.exists() && composerBinary.canExecute()) {
                appendDeployLog("   [DEPS] Running 'composer install'...")
                processSupervisor.executeOneShot(
                    listOf(composerBinary.absolutePath, "install", "--no-dev"),
                    appDir
                ) { line ->
                    appendDeployLog("   [composer] $line")
                }
            } else {
                appendDeployLog("   [INFO] composer.json found.")
            }
        } else {
            appendDeployLog("   [OK] Static / Zero-dependency structure.")
        }
    }

    private fun handleDeployError(e: Exception) {
        appendDeployLog("[ERROR] DEPLOYMENT FAILED: ${e.message}")
        val activeStep = _uiState.value.autoDeployState.steps.firstOrNull { it.status == DeployStepStatus.RUNNING }?.stepNumber ?: 1
        updateStepStatus(activeStep, DeployStepStatus.FAILED)
        _uiState.update {
            it.copy(
                autoDeployState = it.autoDeployState.copy(
                    isRunning = false,
                    isSuccess = false,
                    error = e.message ?: "Unknown deployment failure"
                )
            )
        }
    }

    fun installRuntimePackage(pkg: RuntimePackage) {
        viewModelScope.launch {
            _uiState.update { it.copy(isInstallingPackageId = pkg.id, installProgressPercent = 10) }
            try {
                for (p in 20..100 step 20) {
                    kotlinx.coroutines.delay(200)
                    _uiState.update { it.copy(installProgressPercent = p) }
                }
                loadRuntimePackages()
            } finally {
                _uiState.update { it.copy(isInstallingPackageId = null, installProgressPercent = 0) }
            }
        }
    }
}
