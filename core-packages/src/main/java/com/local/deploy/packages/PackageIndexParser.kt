package com.local.deploy.packages

import com.local.deploy.model.RuntimePackage
import com.local.deploy.model.RuntimeType

object PackageIndexParser {

    fun parse(jsonContent: String): List<RuntimePackage> {
        val trimmed = jsonContent.trim()
        if (trimmed.isEmpty()) return emptyList()

        val packages = mutableListOf<RuntimePackage>()
        val packageBlocks = extractObjectBlocks(trimmed)

        for (block in packageBlocks) {
            val id = extractString(block, "id") ?: continue
            val name = extractString(block, "name") ?: id
            val version = extractString(block, "version") ?: "1.0.0"
            val abi = extractString(block, "abi") ?: "aarch64"
            val sizeBytes = extractLong(block, "sizeBytes") ?: 0L
            val sha256 = extractString(block, "sha256") ?: ""
            val url = extractString(block, "url") ?: extractString(block, "downloadUrl") ?: ""
            val provides = extractStringArray(block, "provides")
            val requires = extractStringArray(block, "requires").ifEmpty { extractStringArray(block, "dependencies") }

            val type = when {
                id.contains("node", ignoreCase = true) -> RuntimeType.NODEJS
                id.contains("python", ignoreCase = true) -> RuntimeType.PYTHON
                id.contains("php", ignoreCase = true) -> RuntimeType.PHP
                id.contains("caddy", ignoreCase = true) -> RuntimeType.CADDY
                id.contains("maria", ignoreCase = true) -> RuntimeType.MARIADB
                else -> RuntimeType.CUSTOM
            }

            packages.add(
                RuntimePackage(
                    id = id,
                    name = name,
                    type = type,
                    version = version,
                    abi = abi,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    downloadUrl = url,
                    provides = provides,
                    requires = requires,
                    dependencies = requires
                )
            )
        }
        return packages
    }

    private fun extractObjectBlocks(json: String): List<String> {
        val blocks = mutableListOf<String>()
        var depth = 0
        var startIndex = -1
        var inString = false
        var escape = false

        for (i in json.indices) {
            val c = json[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            if (c == '{') {
                if (depth == 0 || (depth == 1 && json.substring(0, i).contains("["))) {
                    startIndex = i
                }
                depth++
            } else if (c == '}') {
                depth--
                if ((depth == 0 || depth == 1) && startIndex != -1) {
                    blocks.add(json.substring(startIndex, i + 1))
                    startIndex = -1
                }
            }
        }
        return blocks
    }

    private fun extractString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
        return regex.find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\/", "/")
    }

    private fun extractLong(json: String, key: String): Long? {
        val regex = Regex(""""$key"\s*:\s*([0-9]+)""")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractStringArray(json: String, key: String): List<String> {
        val regex = Regex(""""$key"\s*:\s*\[([^\]]*)\]""")
        val arrayContent = regex.find(json)?.groupValues?.get(1) ?: return emptyList()
        val itemRegex = Regex(""""([^"\\]*(?:\\.[^"\\]*)*)"""")
        return itemRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()
    }
}
