package com.local.deploy.proxy

import com.local.deploy.model.Project
import com.local.deploy.model.ProjectConfig
import com.local.deploy.model.RuntimeType
import org.junit.Assert.assertTrue
import org.junit.Test

class CaddyManagerTest {

    @Test
    fun testGenerateCaddyfile() {
        val projects = listOf(
            Project(
                id = "p1",
                name = "MyExpressApp",
                config = ProjectConfig(
                    name = "MyExpressApp",
                    type = RuntimeType.NODEJS,
                    startCommand = "node app.js",
                    port = 8001
                ),
                projectDirPath = "/tmp/p1"
            ),
            Project(
                id = "p2",
                name = "MyPhpSite",
                config = ProjectConfig(
                    name = "MyPhpSite",
                    type = RuntimeType.PHP,
                    startCommand = "php -S 127.0.0.1:8002",
                    port = 8002
                ),
                projectDirPath = "/tmp/p2"
            )
        )

        val caddyfile = CaddyManager.generateCaddyfile(projects, globalPort = 8080)

        assertTrue(caddyfile.contains("admin 127.0.0.1:2019"))
        assertTrue(caddyfile.contains("127.0.0.1:8080 {"))
        assertTrue(caddyfile.contains("handle_path /myexpressapp*"))
        assertTrue(caddyfile.contains("reverse_proxy 127.0.0.1:8001"))
        assertTrue(caddyfile.contains("handle_path /myphpsite*"))
        assertTrue(caddyfile.contains("reverse_proxy 127.0.0.1:8002"))
    }
}
