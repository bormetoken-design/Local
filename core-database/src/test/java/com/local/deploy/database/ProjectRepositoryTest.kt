package com.local.deploy.database

import com.local.deploy.model.HealthCheckType
import com.local.deploy.model.HealthConfig
import com.local.deploy.model.Project
import com.local.deploy.model.ProjectConfig
import com.local.deploy.model.ProjectStatus
import com.local.deploy.model.RestartPolicy
import com.local.deploy.model.RuntimeType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSaveAndRetrieveProject() = runBlocking {
        val repo = JsonFileProjectRepository(tempFolder.newFolder("db"))

        val project = Project(
            id = "proj-123",
            name = "Test Discord Bot",
            status = ProjectStatus.STOPPED,
            config = ProjectConfig(
                name = "Test Discord Bot",
                type = RuntimeType.NODEJS,
                startCommand = "node index.js",
                port = 8010,
                autostart = true,
                restartPolicy = RestartPolicy.ALWAYS,
                healthConfig = HealthConfig(type = HealthCheckType.HEARTBEAT_LOG),
                env = mapOf("TOKEN" to "abc123xyz"),
                secretKeys = listOf("TOKEN")
            ),
            projectDirPath = "/data/data/com.local.deploy/files/projects/proj-123"
        )

        repo.saveProject(project)

        val retrieved = repo.getProjectById("proj-123")
        assertNotNull(retrieved)
        assertEquals("Test Discord Bot", retrieved?.name)
        assertEquals(8010, retrieved?.config?.port)
        assertEquals(true, retrieved?.config?.autostart)
        assertEquals("abc123xyz", retrieved?.config?.env?.get("TOKEN"))

        val autostartList = repo.getAutostartProjects()
        assertEquals(1, autostartList.size)
    }
}
