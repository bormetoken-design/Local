package com.local.deploy.projects.env

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnvManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testParseAndWriteEnv() {
        val envFile = tempFolder.newFile(".env")
        envFile.writeText("""
            # Database Configuration
            DB_HOST=127.0.0.1
            DB_PORT=3306
            DB_USER="root"
            SECRET_KEY='my super secret'
        """.trimIndent())

        val parsed = EnvManager.parse(envFile)
        assertEquals("127.0.0.1", parsed["DB_HOST"])
        assertEquals("3306", parsed["DB_PORT"])
        assertEquals("root", parsed["DB_USER"])
        assertEquals("my super secret", parsed["SECRET_KEY"])

        val targetFile = tempFolder.newFile(".env.out")
        EnvManager.write(targetFile, parsed)
        val reparsed = EnvManager.parse(targetFile)
        assertEquals(parsed, reparsed)
    }

    @Test
    fun testResolvePlaceholders() {
        val input = "node server.js --port={PORT} --host={HOST}"
        val resolved = EnvManager.resolvePlaceholders(input, port = 8085, host = "127.0.0.1")
        assertEquals("node server.js --port=8085 --host=127.0.0.1", resolved)
    }
}
