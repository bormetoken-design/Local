package com.local.deploy.projects.detector

import com.local.deploy.model.HealthCheckType
import com.local.deploy.model.HealthConfig
import com.local.deploy.model.ProjectConfig
import com.local.deploy.model.RestartPolicy
import com.local.deploy.model.RuntimeType
import java.io.File

data class DetectionResult(
    val detectedType: RuntimeType,
    val suggestedName: String,
    val suggestedCommand: String,
    val suggestedPort: Int,
    val healthCheckType: HealthCheckType,
    val detectedEnvVars: List<DetectedEnvVar>,
    val confidence: Float,
    val description: String
)

data class DetectedEnvVar(
    val key: String,
    val defaultValue: String = "",
    val isSecret: Boolean = false,
    val description: String = ""
)

object ProjectDetector {

    fun detect(projectDir: File): DetectionResult {
        val dirName = projectDir.name.ifBlank { "app" }
        val files = projectDir.walkTopDown().maxDepth(3).toList()
        val fileNames = files.map { it.name }.toSet()

        // 1. Check WordPress
        if (fileNames.contains("wp-config.php") || fileNames.contains("wp-settings.php")) {
            return DetectionResult(
                detectedType = RuntimeType.PHP,
                suggestedName = dirName,
                suggestedCommand = "php -S 127.0.0.1:{PORT} -t .",
                suggestedPort = 8080,
                healthCheckType = HealthCheckType.HTTP,
                detectedEnvVars = extractEnvVars(projectDir),
                confidence = 0.95f,
                description = "WordPress (PHP + MariaDB)"
            )
        }

        // 2. Check Node.js / Discord.js
        val packageJson = files.firstOrNull { it.name == "package.json" }
        if (packageJson != null && packageJson.isFile) {
            val content = packageJson.readText()
            val isDiscordJs = content.contains("discord.js")
            val suggestedCmd = parseNodeStartCommand(content, files)

            return if (isDiscordJs) {
                DetectionResult(
                    detectedType = RuntimeType.NODEJS,
                    suggestedName = parsePackageName(content) ?: dirName,
                    suggestedCommand = suggestedCmd,
                    suggestedPort = 8080,
                    healthCheckType = HealthCheckType.HEARTBEAT_LOG,
                    detectedEnvVars = extractEnvVars(projectDir) + listOf(
                        DetectedEnvVar("DISCORD_TOKEN", isSecret = true, description = "Bot Token from Discord Developer Portal")
                    ).distinctBy { it.key },
                    confidence = 0.95f,
                    description = "Discord Bot (Node.js)"
                )
            } else {
                DetectionResult(
                    detectedType = RuntimeType.NODEJS,
                    suggestedName = parsePackageName(content) ?: dirName,
                    suggestedCommand = suggestedCmd,
                    suggestedPort = 8080,
                    healthCheckType = HealthCheckType.HTTP,
                    detectedEnvVars = extractEnvVars(projectDir),
                    confidence = 0.90f,
                    description = "Node.js Application"
                )
            }
        }

        // 3. Check Python / Discord.py
        val pyFiles = files.filter { it.extension == "py" }
        val reqTxt = files.firstOrNull { it.name == "requirements.txt" }
        if (pyFiles.isNotEmpty() || reqTxt != null) {
            val reqContent = reqTxt?.readText() ?: ""
            val anyPyContainsDiscord = pyFiles.any { py ->
                runCatching { py.readText().contains("discord") }.getOrDefault(false)
            }
            val isDiscordPy = reqContent.contains("discord") || anyPyContainsDiscord
            val mainPy = pyFiles.firstOrNull { it.name in listOf("main.py", "bot.py", "app.py", "index.py") }
                ?: pyFiles.firstOrNull()

            val entryFile = mainPy?.name ?: "main.py"

            return if (isDiscordPy) {
                DetectionResult(
                    detectedType = RuntimeType.PYTHON,
                    suggestedName = dirName,
                    suggestedCommand = "python3 $entryFile",
                    suggestedPort = 8080,
                    healthCheckType = HealthCheckType.HEARTBEAT_LOG,
                    detectedEnvVars = extractEnvVars(projectDir) + listOf(
                        DetectedEnvVar("DISCORD_TOKEN", isSecret = true, description = "Discord Bot Token")
                    ).distinctBy { it.key },
                    confidence = 0.90f,
                    description = "Discord Bot (Python)"
                )
            } else {
                DetectionResult(
                    detectedType = RuntimeType.PYTHON,
                    suggestedName = dirName,
                    suggestedCommand = "python3 $entryFile",
                    suggestedPort = 8080,
                    healthCheckType = HealthCheckType.HTTP,
                    detectedEnvVars = extractEnvVars(projectDir),
                    confidence = 0.85f,
                    description = "Python Application"
                )
            }
        }

        // 4. Check PHP
        val phpFiles = files.filter { it.extension == "php" }
        val composerJson = files.firstOrNull { it.name == "composer.json" }
        if (phpFiles.isNotEmpty() || composerJson != null) {
            return DetectionResult(
                detectedType = RuntimeType.PHP,
                suggestedName = dirName,
                suggestedCommand = "php -S 127.0.0.1:{PORT} -t .",
                suggestedPort = 8080,
                healthCheckType = HealthCheckType.HTTP,
                detectedEnvVars = extractEnvVars(projectDir),
                confidence = 0.85f,
                description = "PHP Web Project"
            )
        }

        // 5. Check Static HTML
        val htmlFiles = files.filter { it.name == "index.html" }
        if (htmlFiles.isNotEmpty()) {
            return DetectionResult(
                detectedType = RuntimeType.STATIC,
                suggestedName = dirName,
                suggestedCommand = "caddy file-server --listen 127.0.0.1:{PORT} --root .",
                suggestedPort = 8080,
                healthCheckType = HealthCheckType.HTTP,
                detectedEnvVars = emptyList(),
                confidence = 0.80f,
                description = "Static Website"
            )
        }

        // 6. Fallback / Custom
        return DetectionResult(
            detectedType = RuntimeType.CUSTOM,
            suggestedName = dirName,
            suggestedCommand = "sh start.sh",
            suggestedPort = 8080,
            healthCheckType = HealthCheckType.NONE,
            detectedEnvVars = extractEnvVars(projectDir),
            confidence = 0.30f,
            description = "Custom Project"
        )
    }

    private fun parsePackageName(jsonContent: String): String? {
        val regex = """"name"\s*:\s*"([^"]+)"""".toRegex()
        return regex.find(jsonContent)?.groupValues?.get(1)?.replace("[^a-zA-Z0-9-_]".toRegex(), "")
    }

    private fun parseNodeStartCommand(jsonContent: String, files: List<File>): String {
        val startScriptRegex = """"start"\s*:\s*"([^"]+)"""".toRegex()
        val startScript = startScriptRegex.find(jsonContent)?.groupValues?.get(1)
        if (!startScript.isNullOrBlank()) {
            return "npm start"
        }

        val mainRegex = """"main"\s*:\s*"([^"]+)"""".toRegex()
        val mainFile = mainRegex.find(jsonContent)?.groupValues?.get(1)
        if (!mainFile.isNullOrBlank()) {
            return "node $mainFile"
        }

        val entry = listOf("index.js", "app.js", "server.js", "bot.js", "main.js")
            .firstOrNull { name -> files.any { it.name == name } } ?: "index.js"
        return "node $entry"
    }

    fun extractEnvVars(projectDir: File): List<DetectedEnvVar> {
        val list = mutableListOf<DetectedEnvVar>()
        val envExample = File(projectDir, ".env.example").takeIf { it.exists() }
            ?: File(projectDir, ".env.sample").takeIf { it.exists() }
            ?: File(projectDir, "example.env").takeIf { it.exists() }

        if (envExample != null) {
            envExample.readLines().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isNotEmpty() && !line.startsWith("#") && line.contains("=")) {
                    val parts = line.split("=", limit = 2)
                    val key = parts[0].trim()
                    val value = parts.getOrNull(1)?.trim() ?: ""
                    val isSecret = isKeyLikelySecret(key)
                    list.add(DetectedEnvVar(key = key, defaultValue = value, isSecret = isSecret))
                }
            }
        }

        // Scan code files for process.env.KEY or os.getenv("KEY")
        val codeFiles = projectDir.walkTopDown().maxDepth(3)
            .filter { it.isFile && (it.extension in listOf("js", "ts", "py", "php")) }
            .take(30)

        val nodeEnvRegex = """process\.env\.([A-Z0-9_]+)""".toRegex()
        val pyEnvRegex = """(?:os\.getenv|os\.environ\.get)\(['"]([A-Z0-9_]+)['"]""".toRegex()

        codeFiles.forEach { file ->
            runCatching {
                val text = file.readText()
                nodeEnvRegex.findAll(text).forEach { match ->
                    val key = match.groupValues[1]
                    if (list.none { it.key == key }) {
                        list.add(DetectedEnvVar(key = key, isSecret = isKeyLikelySecret(key)))
                    }
                }
                pyEnvRegex.findAll(text).forEach { match ->
                    val key = match.groupValues[1]
                    if (list.none { it.key == key }) {
                        list.add(DetectedEnvVar(key = key, isSecret = isKeyLikelySecret(key)))
                    }
                }
            }
        }

        return list.distinctBy { it.key }
    }

    private fun isKeyLikelySecret(key: String): Boolean {
        val lower = key.lowercase()
        return lower.contains("token") || lower.contains("secret") || lower.contains("password") ||
                lower.contains("key") || lower.contains("auth") || lower.contains("cert")
    }
}
