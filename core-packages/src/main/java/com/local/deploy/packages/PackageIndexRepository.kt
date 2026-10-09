package com.local.deploy.packages

import com.local.deploy.model.RuntimePackage
import com.local.deploy.model.RuntimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PackageIndexRepository(
    private val filesDir: File,
    private val packageManager: PackageManager
) {
    private val configFile = File(filesDir, "db/runtime_index.conf").apply { parentFile?.mkdirs() }
    private val cachedIndexFile = File(filesDir, "cache/index.json").apply { parentFile?.mkdirs() }

    fun getIndexUrl(): String {
        return if (configFile.exists()) {
            configFile.readText().trim().ifBlank { DEFAULT_INDEX_URL }
        } else {
            DEFAULT_INDEX_URL
        }
    }

    fun setIndexUrl(newUrl: String) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(newUrl.trim())
    }

    suspend fun loadPackages(sourceUrlOrPath: String? = null): List<RuntimePackage> = withContext(Dispatchers.IO) {
        val targetSource = sourceUrlOrPath?.trim()?.takeIf { it.isNotBlank() } ?: getIndexUrl()
        val deviceAbi = packageManager.detectDeviceAbi()

        var jsonContent: String? = null

        // 1. Attempt to fetch from network if HTTP/HTTPS
        if (targetSource.startsWith("http://", ignoreCase = true) || targetSource.startsWith("https://", ignoreCase = true)) {
            try {
                val connection = (URL(targetSource).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 12000
                    requestMethod = "GET"
                }
                connection.connect()
                if (connection.responseCode in 200..299) {
                    val fetched = connection.inputStream.bufferedReader().use { it.readText() }
                    cachedIndexFile.writeText(fetched)
                    jsonContent = fetched
                }
                connection.disconnect()
            } catch (_: Exception) {
                // Network unavailable or timeout; fallback to cache
            }
        } else {
            // Local file path
            val localFile = File(targetSource)
            if (localFile.exists() && localFile.isFile) {
                jsonContent = localFile.readText()
            }
        }

        // 2. Fallback to cached index.json if present
        if (jsonContent.isNullOrBlank() && cachedIndexFile.exists() && cachedIndexFile.isFile) {
            jsonContent = cachedIndexFile.readText()
        }

        // 3. Fallback to default built-in JSON
        if (jsonContent.isNullOrBlank()) {
            jsonContent = getDefaultIndexJson(deviceAbi)
        }

        // 4. Parse JSON
        val parsedList = PackageIndexParser.parse(jsonContent)

        // 5. Decorate with real system installation status
        parsedList.map { pkg ->
            val primaryBinary = pkg.provides.firstOrNull() ?: pkg.id
            val isInstalled = pkg.provides.any { bin -> packageManager.isBinaryInstalled(bin) }
                    || packageManager.isBinaryInstalled(primaryBinary)

            val installedVer = if (isInstalled) {
                pkg.provides.firstNotNullOfOrNull { bin -> packageManager.getBinaryVersion(bin) }
                    ?: packageManager.getBinaryVersion(primaryBinary)
            } else null

            val binPath = if (isInstalled) {
                File(packageManager.binDir, primaryBinary).absolutePath
            } else null

            val isUpdate = isInstalled && installedVer != null && installedVer != pkg.version

            pkg.copy(
                isInstalled = isInstalled,
                installedVersion = installedVer,
                binaryPath = binPath,
                isUpdateAvailable = isUpdate
            )
        }
    }

    private fun getDefaultIndexJson(abi: String): String {
        return """
        [
          {
            "id": "nodejs",
            "name": "Node.js (LTS)",
            "version": "20.14.0",
            "abi": "$abi",
            "sizeBytes": 39845888,
            "sha256": "",
            "url": "https://github.com/bormetoken-design/Local/releases/download/v1.0.0/node-20.14.0-$abi.tar.gz",
            "provides": ["node", "npm", "npx"],
            "requires": []
          },
          {
            "id": "python",
            "name": "Python 3.11",
            "version": "3.11.9",
            "abi": "$abi",
            "sizeBytes": 44040192,
            "sha256": "",
            "url": "https://github.com/bormetoken-design/Local/releases/download/v1.0.0/python-3.11.9-$abi.tar.gz",
            "provides": ["python3", "pip3", "python", "pip"],
            "requires": []
          },
          {
            "id": "php",
            "name": "PHP 8.2 (CLI + FPM)",
            "version": "8.2.18",
            "abi": "$abi",
            "sizeBytes": 26214400,
            "sha256": "",
            "url": "https://github.com/bormetoken-design/Local/releases/download/v1.0.0/php-8.2.18-$abi.tar.gz",
            "provides": ["php", "php-fpm"],
            "requires": []
          },
          {
            "id": "caddy",
            "name": "Caddy Reverse Proxy",
            "version": "2.8.4",
            "abi": "$abi",
            "sizeBytes": 18874368,
            "sha256": "",
            "url": "https://github.com/bormetoken-design/Local/releases/download/v1.0.0/caddy-2.8.4-$abi.tar.gz",
            "provides": ["caddy"],
            "requires": []
          },
          {
            "id": "mariadb",
            "name": "MariaDB Server",
            "version": "10.11.6",
            "abi": "$abi",
            "sizeBytes": 68157440,
            "sha256": "",
            "url": "https://github.com/bormetoken-design/Local/releases/download/v1.0.0/mariadb-10.11.6-$abi.tar.gz",
            "provides": ["mariadbd", "mariadb", "mysql"],
            "requires": []
          }
        ]
        """.trimIndent()
    }

    companion object {
        const val DEFAULT_INDEX_URL = "https://raw.githubusercontent.com/bormetoken-design/Local/main/packages/index.json"
    }
}
