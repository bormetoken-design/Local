package com.local.deploy.packages

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class PackageManagerTest {

    private lateinit var tempDir: File
    private lateinit var packageManager: PackageManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("pkg_mgr_test").toFile()
        packageManager = PackageManager(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // --- 1. SHA-256 Tests ---

    @Test
    fun testSha256CalculationAndVerification() {
        val testFile = File(tempDir, "test_file.txt").apply {
            writeText("antigravity-local-deploy-sha256-test")
        }

        val sha = packageManager.computeSha256(testFile)
        assertNotNull(sha)
        assertEquals(64, sha.length)

        // Verifying correct hash returns true
        assertTrue(packageManager.verifyChecksum(testFile, sha))
        assertTrue(packageManager.verifyChecksum(testFile, sha.uppercase()))

        // Verifying wrong hash returns false
        val invalidSha = "0".repeat(64)
        assertFalse(packageManager.verifyChecksum(testFile, invalidSha))

        // Empty sha returns true (skipped check)
        assertTrue(packageManager.verifyChecksum(testFile, ""))
    }

    // --- 2. TAR Extraction: Symlink, Long Names, Traversal ---

    @Test
    fun testTarExtractionNormalFileAndPermissions() {
        val targetDir = File(tempDir, "target_normal").apply { mkdirs() }
        val content = "hello world content"

        val tarBytes = buildTarGzArchive {
            addFileEntry(name = "bin/my_tool", content = content.toByteArray(), mode = 493) // 0755
        }

        packageManager.extractTarGz(ByteArrayInputStream(tarBytes), targetDir)

        val extracted = File(targetDir, "bin/my_tool")
        assertTrue("Extracted file must exist", extracted.exists())
        assertEquals(content, extracted.readText())
        assertTrue("File should be executable", extracted.canExecute())
    }

    @Test
    fun testTarExtractionSymlink() {
        val targetDir = File(tempDir, "target_symlink").apply { mkdirs() }
        val targetContent = "real shared library data"

        val tarBytes = buildTarGzArchive {
            addFileEntry(name = "lib/libsample.so", content = targetContent.toByteArray(), mode = 420) // 0644
            addSymlinkEntry(name = "bin/sample_link", target = "../lib/libsample.so")
        }

        packageManager.extractTarGz(ByteArrayInputStream(tarBytes), targetDir)

        val targetFile = File(targetDir, "lib/libsample.so")
        val linkFile = File(targetDir, "bin/sample_link")

        assertTrue("Target file must exist", targetFile.exists())
        assertTrue("Symlink file must exist", linkFile.exists() || Files.isSymbolicLink(linkFile.toPath()))
        assertEquals(targetContent, linkFile.readText())
    }

    @Test
    fun testTarExtractionLongFileNameGnu() {
        val targetDir = File(tempDir, "target_gnu_long").apply { mkdirs() }
        val longPath = "very/deeply/nested/directory/hierarchy/with/a/filename/that/exceeds/the/standard/one_hundred_characters_limit_in_classic_tar_header_node.js"
        val content = "console.log('long path executed');"

        val tarBytes = buildTarGzArchive {
            addGnuLongNameFileEntry(path = longPath, content = content.toByteArray(), mode = 420)
        }

        packageManager.extractTarGz(ByteArrayInputStream(tarBytes), targetDir)

        val extracted = File(targetDir, longPath)
        assertTrue("Long path file must exist at $longPath", extracted.exists())
        assertEquals(content, extracted.readText())
    }

    @Test
    fun testTarExtractionLongFileNameUstarPrefix() {
        val targetDir = File(tempDir, "target_ustar_prefix").apply { mkdirs() }
        val prefix = "usr/local/share/doc/packages"
        val name = "documentation_readme_file.md"
        val content = "# Full Documentation"

        val tarBytes = buildTarGzArchive {
            addUstarPrefixFileEntry(prefix = prefix, name = name, content = content.toByteArray(), mode = 420)
        }

        packageManager.extractTarGz(ByteArrayInputStream(tarBytes), targetDir)

        val extracted = File(targetDir, "$prefix/$name")
        assertTrue("File with USTAR prefix must exist at $prefix/$name", extracted.exists())
        assertEquals(content, extracted.readText())
    }

    @Test
    fun testTarExtractionPathTraversalEntryRejected() {
        val targetDir = File(tempDir, "target_traversal_entry").apply { mkdirs() }
        val maliciousPath = "../../traversal_attack.txt"

        val tarBytes = buildTarGzArchive {
            addFileEntry(name = maliciousPath, content = "malicious payload".toByteArray())
        }

        try {
            packageManager.extractTarGz(ByteArrayInputStream(tarBytes), targetDir)
            fail("Expected SecurityException on path traversal entry")
        } catch (_: SecurityException) {
            // Expected
        }

        val outsideFile = File(tempDir, "traversal_attack.txt")
        assertFalse("Traversal file must NOT exist outside target dir", outsideFile.exists())
    }

    @Test
    fun testTarExtractionPathTraversalSymlinkTargetRejected() {
        val targetDir = File(tempDir, "target_traversal_symlink").apply { mkdirs() }
        val maliciousTarget = "../../../../../../../etc/passwd"

        val tarBytes = buildTarGzArchive {
            addSymlinkEntry(name = "bin/bad_symlink", target = maliciousTarget)
        }

        try {
            packageManager.extractTarGz(ByteArrayInputStream(tarBytes), targetDir)
            fail("Expected SecurityException on escaping symlink target")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    // --- 3. Download Range Resume Tests ---

    @Test
    fun testRangeResumeDownload() = runBlocking {
        val fullData = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val fullBytes = fullData.toByteArray(Charsets.UTF_8)
        val initialPartSize = 10

        // Spin up local HTTP server supporting HTTP Range
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/download") { exchange ->
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val start = rangeHeader.substringAfter("bytes=").substringBefore("-").toInt()
                val chunk = fullBytes.copyOfRange(start, fullBytes.size)
                exchange.responseHeaders.set("Content-Range", "bytes $start-${fullBytes.size - 1}/${fullBytes.size}")
                exchange.sendResponseHeaders(206, chunk.size.toLong())
                exchange.responseBody.use { it.write(chunk) }
            } else {
                exchange.sendResponseHeaders(200, fullBytes.size.toLong())
                exchange.responseBody.use { it.write(fullBytes) }
            }
        }
        server.start()

        val port = server.address.port
        val downloadUrl = "http://127.0.0.1:$port/download"
        val targetFile = File(tempDir, "resumed_file.bin")
        val partFile = File(tempDir, "resumed_file.bin.part")

        try {
            // Pre-create .part file with initial partial content
            FileOutputStream(partFile).use { fos ->
                fos.write(fullBytes, 0, initialPartSize)
            }
            assertEquals(initialPartSize.toLong(), partFile.length())

            // Execute downloadPackage with resume
            val downloadedFile = packageManager.downloadPackage(
                downloadUrl = downloadUrl,
                targetFile = targetFile,
                expectedSizeBytes = fullBytes.size.toLong()
            )

            // Verification
            assertTrue("Target file must exist", downloadedFile.exists())
            assertFalse("Temporary .part file must be cleaned up / renamed", partFile.exists())
            assertEquals(fullData, downloadedFile.readText(Charsets.UTF_8))
        } finally {
            server.stop(0)
        }
    }

    // --- TAR Archive Builder Helper ---

    private class TarArchiveBuilder {
        private val outputStream = ByteArrayOutputStream()

        fun addFileEntry(name: String, content: ByteArray, mode: Int = 420) {
            writeTarHeader(name = name, size = content.size.toLong(), typeFlag = '0', mode = mode)
            outputStream.write(content)
            padTo512(content.size)
        }

        fun addSymlinkEntry(name: String, target: String) {
            writeTarHeader(name = name, size = 0L, typeFlag = '2', linkName = target, mode = 511)
        }

        fun addGnuLongNameFileEntry(path: String, content: ByteArray, mode: Int = 420) {
            val pathBytes = (path + "\u0000").toByteArray(Charsets.UTF_8)
            writeTarHeader(name = "././@LongLink", size = pathBytes.size.toLong(), typeFlag = 'L', mode = 420)
            outputStream.write(pathBytes)
            padTo512(pathBytes.size)

            val shortName = path.take(100)
            writeTarHeader(name = shortName, size = content.size.toLong(), typeFlag = '0', mode = mode)
            outputStream.write(content)
            padTo512(content.size)
        }

        fun addUstarPrefixFileEntry(prefix: String, name: String, content: ByteArray, mode: Int = 420) {
            writeTarHeader(name = name, size = content.size.toLong(), typeFlag = '0', prefix = prefix, mode = mode)
            outputStream.write(content)
            padTo512(content.size)
        }

        private fun writeTarHeader(
            name: String,
            size: Long,
            typeFlag: Char,
            linkName: String = "",
            prefix: String = "",
            mode: Int = 420
        ) {
            val header = ByteArray(512)

            // 0-99 Name
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))

            // 100-107 Mode
            val modeStr = "%07o".format(mode)
            System.arraycopy(modeStr.toByteArray(Charsets.US_ASCII), 0, header, 100, 7)

            // 108-115 UID, 116-123 GID
            System.arraycopy("0000000".toByteArray(Charsets.US_ASCII), 0, header, 108, 7)
            System.arraycopy("0000000".toByteArray(Charsets.US_ASCII), 0, header, 116, 7)

            // 124-135 Size
            val sizeStr = "%011o".format(size)
            System.arraycopy(sizeStr.toByteArray(Charsets.US_ASCII), 0, header, 124, 11)

            // 136-147 Mtime
            System.arraycopy("00000000000".toByteArray(Charsets.US_ASCII), 0, header, 136, 11)

            // 148-155 Checksum placeholder
            for (i in 148 until 156) header[i] = ' '.code.toByte()

            // 156 TypeFlag
            header[156] = typeFlag.code.toByte()

            // 157-256 LinkName
            val linkBytes = linkName.toByteArray(Charsets.US_ASCII)
            System.arraycopy(linkBytes, 0, header, 157, minOf(linkBytes.size, 100))

            // 257-262 Magic, 263-264 Version
            System.arraycopy("ustar\u0000".toByteArray(Charsets.US_ASCII), 0, header, 257, 6)
            System.arraycopy("00".toByteArray(Charsets.US_ASCII), 0, header, 263, 2)

            // 345-499 Prefix
            val prefixBytes = prefix.toByteArray(Charsets.US_ASCII)
            System.arraycopy(prefixBytes, 0, header, 345, minOf(prefixBytes.size, 155))

            // Checksum calculation
            var sum = 0
            for (b in header) {
                sum += (b.toInt() and 0xFF)
            }
            val chkStr = "%06o\u0000 ".format(sum)
            System.arraycopy(chkStr.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)

            outputStream.write(header)
        }

        private fun padTo512(size: Int) {
            val rem = size % 512
            if (rem > 0) {
                outputStream.write(ByteArray(512 - rem))
            }
        }

        fun buildGzBytes(): ByteArray {
            // End of archive marker (two 512-byte zero blocks)
            outputStream.write(ByteArray(1024))

            val gzOutput = ByteArrayOutputStream()
            GZIPOutputStream(gzOutput).use { it.write(outputStream.toByteArray()) }
            return gzOutput.toByteArray()
        }
    }

    private fun buildTarGzArchive(block: TarArchiveBuilder.() -> Unit): ByteArray {
        val builder = TarArchiveBuilder()
        builder.block()
        return builder.buildGzBytes()
    }
}
