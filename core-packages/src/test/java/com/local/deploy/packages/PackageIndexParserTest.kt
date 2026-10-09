package com.local.deploy.packages

import com.local.deploy.model.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageIndexParserTest {

    @Test
    fun testParseValidIndexJson() {
        val json = """
        [
            {
                "id": "node-20",
                "name": "Node.js 20 LTS",
                "version": "20.11.1",
                "abi": "aarch64",
                "sizeBytes": 35651584,
                "sha256": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                "url": "https://example.com/packages/node-20.tar.gz",
                "provides": ["node", "npm", "npx"],
                "requires": []
            },
            {
                "id": "caddy",
                "name": "Caddy Web Server",
                "version": "2.7.6",
                "abi": "aarch64",
                "sizeBytes": 14680064,
                "sha256": "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                "url": "https://example.com/packages/caddy.tar.gz",
                "provides": ["caddy"],
                "requires": []
            }
        ]
        """.trimIndent()

        val packages = PackageIndexParser.parse(json)
        assertEquals(2, packages.size)

        val nodePkg = packages.first { it.id == "node-20" }
        assertEquals("Node.js 20 LTS", nodePkg.name)
        assertEquals(RuntimeType.NODEJS, nodePkg.type)
        assertEquals("20.11.1", nodePkg.version)
        assertEquals("aarch64", nodePkg.abi)
        assertEquals(35651584L, nodePkg.sizeBytes)
        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", nodePkg.sha256)
        assertEquals("https://example.com/packages/node-20.tar.gz", nodePkg.downloadUrl)
        assertEquals(listOf("node", "npm", "npx"), nodePkg.provides)
        assertTrue(nodePkg.requires.isEmpty())

        val caddyPkg = packages.first { it.id == "caddy" }
        assertEquals(RuntimeType.CADDY, caddyPkg.type)
        assertEquals(listOf("caddy"), caddyPkg.provides)
    }

    @Test
    fun testParseEmptyJson() {
        val emptyList = PackageIndexParser.parse("")
        assertTrue(emptyList.isEmpty())

        val emptyJsonArray = PackageIndexParser.parse("[]")
        assertTrue(emptyJsonArray.isEmpty())
    }
}
