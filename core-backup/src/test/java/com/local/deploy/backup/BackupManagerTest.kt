package com.local.deploy.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBackupAndRestoreUnencrypted() {
        val projDir = tempFolder.newFolder("my-project")
        val appDir = File(projDir, "app").apply { mkdirs() }
        File(appDir, "server.js").writeText("console.log('hello');")
        File(projDir, "project.json").writeText("""{"name":"test"}""")
        File(projDir, ".env").writeText("KEY=123")

        val backupDir = tempFolder.newFolder("backups")
        val metadata = BackupManager.createBackup(
            projectDir = projDir,
            projectId = "p1",
            projectName = "MyProject",
            destinationDir = backupDir
        )

        assertTrue(metadata.backupFile.exists())

        // Restore to another directory
        val restoredDir = tempFolder.newFolder("restored-project")
        BackupManager.restoreBackup(metadata.backupFile, restoredDir)

        assertTrue(File(restoredDir, "app/server.js").exists())
        assertEquals("console.log('hello');", File(restoredDir, "app/server.js").readText())
        assertEquals("""{"name":"test"}""", File(restoredDir, "project.json").readText())
        assertEquals("KEY=123", File(restoredDir, ".env").readText())
    }

    @Test
    fun testBackupAndRestoreEncrypted() {
        val projDir = tempFolder.newFolder("secure-project")
        val appDir = File(projDir, "app").apply { mkdirs() }
        File(appDir, "index.js").writeText("const secret = true;")

        val backupDir = tempFolder.newFolder("sec-backups")
        val pass = "SuperStrongPassword123!"

        val metadata = BackupManager.createBackup(
            projectDir = projDir,
            projectId = "p-sec",
            projectName = "SecProject",
            destinationDir = backupDir,
            password = pass
        )

        assertTrue(metadata.isEncrypted)
        assertTrue(metadata.backupFile.name.endsWith(".enc"))

        // Restore with correct password
        val restoredDir = tempFolder.newFolder("sec-restored")
        BackupManager.restoreBackup(metadata.backupFile, restoredDir, password = pass)

        assertTrue(File(restoredDir, "app/index.js").exists())
        assertEquals("const secret = true;", File(restoredDir, "app/index.js").readText())
    }
}
