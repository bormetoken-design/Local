package com.local.deploy.projects.detector

import com.local.deploy.model.HealthCheckType
import com.local.deploy.model.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDetectDiscordJsBot() {
        val root = tempFolder.newFolder("discord-bot")
        val pkgJson = File(root, "package.json")
        pkgJson.writeText("""
            {
              "name": "my-cool-bot",
              "main": "index.js",
              "dependencies": {
                "discord.js": "^14.0.0"
              }
            }
        """.trimIndent())
        File(root, "index.js").writeText("console.log('hi');")
        File(root, ".env.example").writeText("DISCORD_TOKEN=xyz\nPORT=8000")

        val result = ProjectDetector.detect(root)

        assertEquals(RuntimeType.NODEJS, result.detectedType)
        assertEquals("my-cool-bot", result.suggestedName)
        assertEquals("node index.js", result.suggestedCommand)
        assertEquals(HealthCheckType.HEARTBEAT_LOG, result.healthCheckType)
        assertTrue(result.detectedEnvVars.any { it.key == "DISCORD_TOKEN" && it.isSecret })
    }

    @Test
    fun testDetectPythonBot() {
        val root = tempFolder.newFolder("python-bot")
        val reqTxt = File(root, "requirements.txt")
        reqTxt.writeText("discord.py\npython-dotenv\n")
        File(root, "main.py").writeText("import discord\nprint('bot')")

        val result = ProjectDetector.detect(root)

        assertEquals(RuntimeType.PYTHON, result.detectedType)
        assertEquals("python3 main.py", result.suggestedCommand)
        assertEquals(HealthCheckType.HEARTBEAT_LOG, result.healthCheckType)
    }

    @Test
    fun testDetectPhpProject() {
        val root = tempFolder.newFolder("php-site")
        File(root, "index.php").writeText("<?php phpinfo(); ?>")

        val result = ProjectDetector.detect(root)

        assertEquals(RuntimeType.PHP, result.detectedType)
        assertEquals(HealthCheckType.HTTP, result.healthCheckType)
        assertTrue(result.suggestedCommand.contains("php -S"))
    }

    @Test
    fun testDetectStaticWebsite() {
        val root = tempFolder.newFolder("static-site")
        File(root, "index.html").writeText("<h1>Hello World</h1>")

        val result = ProjectDetector.detect(root)

        assertEquals(RuntimeType.STATIC, result.detectedType)
        assertTrue(result.suggestedCommand.contains("caddy file-server"))
    }
}
