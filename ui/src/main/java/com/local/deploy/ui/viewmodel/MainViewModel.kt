package com.local.deploy.ui.viewmodel

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
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
    val activeDetectionResult: DetectionResult? = null
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
}
