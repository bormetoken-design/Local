package com.local.deploy.database

import com.local.deploy.model.HealthCheckType
import com.local.deploy.model.HealthConfig
import com.local.deploy.model.Project
import com.local.deploy.model.ProjectConfig
import com.local.deploy.model.ProjectStatus
import com.local.deploy.model.RestartPolicy
import com.local.deploy.model.RuntimeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface ProjectRepository {
    fun getAllProjectsFlow(): Flow<List<Project>>
    suspend fun getAllProjects(): List<Project>
    suspend fun getProjectById(id: String): Project?
    suspend fun saveProject(project: Project)
    suspend fun deleteProject(id: String)
    suspend fun getAutostartProjects(): List<Project>
}

class JsonFileProjectRepository(
    private val dbDir: File
) : ProjectRepository {

    private val dbFile = File(dbDir, "projects.json")
    private val memoryStore = ConcurrentHashMap<String, Project>()
    private val _projectsFlow = MutableStateFlow<List<Project>>(emptyList())

    init {
        dbDir.mkdirs()
        loadFromDisk()
    }

    private fun loadFromDisk() {
        if (!dbFile.exists()) return
        runCatching {
            val lines = dbFile.readLines()
            lines.forEach { line ->
                val proj = deserializeProject(line)
                if (proj != null) {
                    memoryStore[proj.id] = proj
                }
            }
            updateFlow()
        }
    }

    @Synchronized
    private fun saveToDisk() {
        runCatching {
            val serialized = memoryStore.values.joinToString("\n") { serializeProject(it) }
            dbFile.writeText(serialized)
            updateFlow()
        }
    }

    private fun updateFlow() {
        _projectsFlow.value = memoryStore.values.toList().sortedByDescending { it.createdAt }
    }

    override fun getAllProjectsFlow(): Flow<List<Project>> = _projectsFlow.asStateFlow()

    override suspend fun getAllProjects(): List<Project> =
        memoryStore.values.toList().sortedByDescending { it.createdAt }

    override suspend fun getProjectById(id: String): Project? = memoryStore[id]

    override suspend fun saveProject(project: Project) {
        memoryStore[project.id] = project
        saveToDisk()
    }

    override suspend fun deleteProject(id: String) {
        memoryStore.remove(id)
        saveToDisk()
    }

    override suspend fun getAutostartProjects(): List<Project> =
        memoryStore.values.filter { it.config.autostart }

    // Lightweight deterministic serializer (no bulky reflection or code generation needed)
    private fun serializeProject(p: Project): String {
        val escape: (String) -> String = { it.replace("|", "%7C").replace("\n", "%0A") }
        val envStr = p.config.env.entries.joinToString(";") { "${escape(it.key)}=${escape(it.value)}" }
        val secretsStr = p.config.secretKeys.joinToString(";") { escape(it) }

        return listOf(
            p.id,
            escape(p.name),
            p.status.name,
            p.config.type.name,
            escape(p.config.runtimeVersion),
            escape(p.config.startCommand),
            escape(p.config.workDir),
            p.config.port.toString(),
            escape(p.config.bindHost),
            p.config.autostart.toString(),
            p.config.restartPolicy.name,
            p.config.maxRestarts.toString(),
            p.config.healthConfig.type.name,
            escape(p.config.healthConfig.endpointOrPort),
            p.config.healthConfig.intervalSeconds.toString(),
            escape(p.projectDirPath),
            p.createdAt.toString(),
            p.updatedAt.toString(),
            envStr,
            secretsStr,
            escape(p.config.discordWebhookUrl ?: "")
        ).joinToString("|")
    }

    private fun deserializeProject(line: String): Project? {
        val parts = line.split("|")
        if (parts.size < 17) return null

        val unescape: (String) -> String = { it.replace("%7C", "|").replace("%0A", "\n") }

        return try {
            val envMap = mutableMapOf<String, String>()
            if (parts.size >= 19 && parts[18].isNotBlank()) {
                parts[18].split(";").forEach { pair ->
                    val kv = pair.split("=")
                    if (kv.size == 2) envMap[unescape(kv[0])] = unescape(kv[1])
                }
            }

            val secretKeys = if (parts.size >= 20 && parts[19].isNotBlank()) {
                parts[19].split(";").map { unescape(it) }
            } else emptyList()

            val webhook = if (parts.size >= 21) unescape(parts[20]).ifBlank { null } else null

            Project(
                id = parts[0],
                name = unescape(parts[1]),
                status = ProjectStatus.valueOf(parts[2]),
                config = ProjectConfig(
                    name = unescape(parts[1]),
                    type = RuntimeType.valueOf(parts[3]),
                    runtimeVersion = unescape(parts[4]),
                    startCommand = unescape(parts[5]),
                    workDir = unescape(parts[6]),
                    port = parts[7].toIntOrNull() ?: 8080,
                    bindHost = unescape(parts[8]),
                    autostart = parts[9].toBooleanStrictOrNull() ?: false,
                    restartPolicy = RestartPolicy.valueOf(parts[10]),
                    maxRestarts = parts[11].toIntOrNull() ?: 10,
                    healthConfig = HealthConfig(
                        type = HealthCheckType.valueOf(parts[12]),
                        endpointOrPort = unescape(parts[13]),
                        intervalSeconds = parts[14].toIntOrNull() ?: 30
                    ),
                    env = envMap,
                    secretKeys = secretKeys,
                    discordWebhookUrl = webhook
                ),
                projectDirPath = unescape(parts[15]),
                createdAt = parts[16].toLongOrNull() ?: System.currentTimeMillis(),
                updatedAt = parts.getOrNull(17)?.toLongOrNull() ?: System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }
}
