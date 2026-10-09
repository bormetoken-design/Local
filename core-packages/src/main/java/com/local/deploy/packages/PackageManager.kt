package com.local.deploy.packages

import com.local.deploy.model.RuntimePackage
import com.local.deploy.model.RuntimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

data class InstallProgress(
    val stage: String,
    val progressPercent: Int,
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L
)

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progressPercent: Int
)

class DownloadCancelledException(message: String = "Download was cancelled") : RuntimeException(message)

class PackageManager(
    private val filesDir: File
) {
    val usrDir: File = File(filesDir, "usr").apply { mkdirs() }
    val binDir: File = File(usrDir, "bin").apply { mkdirs() }
    val libDir: File = File(usrDir, "lib").apply { mkdirs() }
    val cacheDir: File = File(filesDir, "cache").apply { mkdirs() }

    /**
     * Determines current device CPU ABI (e.g., aarch64, x86_64)
     */
    fun detectDeviceAbi(): String {
        val arch = System.getProperty("os.arch") ?: "aarch64"
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
            arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
            arch.contains("arm") -> "armv7l"
            else -> "aarch64"
        }
    }

    /**
     * Checks if the package ABI is compatible with this device.
     */
    fun checkAbiCompatibility(packageAbi: String, deviceAbi: String = detectDeviceAbi()): Boolean {
        if (packageAbi.isBlank() || packageAbi.equals("all", ignoreCase = true) || packageAbi.equals("any", ignoreCase = true)) {
            return true
        }
        val cleanPkgAbi = packageAbi.trim().lowercase()
        val cleanDevAbi = deviceAbi.trim().lowercase()
        return cleanPkgAbi == cleanDevAbi
    }

    /**
     * Checks if there is enough free space in the storage directory.
     */
    fun checkStorageSpace(requiredBytes: Long, safetyMarginBytes: Long = 50L * 1024 * 1024): Boolean {
        val usable = filesDir.usableSpace
        val needed = requiredBytes + safetyMarginBytes
        return usable >= needed
    }

    /**
     * Computes the SHA-256 checksum of any file.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies SHA-256 checksum of a file against expected hash.
     */
    fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        if (expectedSha256.isBlank()) return true
        val actual = computeSha256(file)
        return actual.equals(expectedSha256.trim(), ignoreCase = true)
    }

    /**
     * Downloads a package with .part support, HTTP Range resume, progress and cancellation.
     */
    suspend fun downloadPackage(
        downloadUrl: String,
        targetFile: File,
        expectedSizeBytes: Long = 0L,
        onProgress: ((DownloadProgress) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): File = withContext(Dispatchers.IO) {
        val partFile = File(targetFile.parentFile ?: cacheDir, "${targetFile.name}.part")
        var downloadedBytes = if (partFile.exists()) partFile.length() else 0L

        val url = URL(downloadUrl)
        var connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000

        if (downloadedBytes > 0) {
            connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
        }

        connection.connect()
        val responseCode = connection.responseCode

        val inputStream = when (responseCode) {
            HttpURLConnection.HTTP_PARTIAL -> {
                connection.inputStream
            }
            HttpURLConnection.HTTP_OK -> {
                downloadedBytes = 0L
                partFile.delete()
                connection.inputStream
            }
            416 -> {
                connection.disconnect()
                partFile.delete()
                downloadedBytes = 0L
                connection = url.openConnection() as HttpURLConnection
                connection.connect()
                connection.inputStream
            }
            else -> {
                throw IOException("HTTP error $responseCode: ${connection.responseMessage}")
            }
        }

        val contentLength = connection.contentLengthLong
        val totalBytes = if (contentLength > 0) {
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) downloadedBytes + contentLength else contentLength
        } else {
            expectedSizeBytes
        }

        try {
            FileOutputStream(partFile, downloadedBytes > 0).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled() || !coroutineContext.isActive) {
                        throw DownloadCancelledException("Download cancelled by user")
                    }
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val percent = if (totalBytes > 0) {
                        ((downloadedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                    } else 0
                    onProgress?.invoke(DownloadProgress(downloadedBytes, totalBytes, percent))
                }
                output.flush()
            }
        } finally {
            inputStream.close()
            connection.disconnect()
        }

        if (targetFile.exists()) targetFile.delete()
        if (!partFile.renameTo(targetFile)) {
            partFile.copyTo(targetFile, overwrite = true)
            partFile.delete()
        }

        targetFile
    }

    /**
     * High-level package installer:
     * 1. Check ABI compatibility
     * 2. Check storage space
     * 3. Download (if archive not provided) with .part and resume
     * 4. Verify SHA-256
     * 5. Extract into usrDir
     */
    suspend fun installPackage(
        pkg: RuntimePackage,
        localArchive: File? = null,
        onProgress: ((InstallProgress) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): Boolean = withContext(Dispatchers.IO) {
        val deviceAbi = detectDeviceAbi()

        // 1. ABI Check
        onProgress?.invoke(InstallProgress("Checking ABI compatibility", 5))
        if (!checkAbiCompatibility(pkg.abi, deviceAbi)) {
            throw IllegalStateException("Incompatible ABI: Package requires '${pkg.abi}', but device ABI is '$deviceAbi'")
        }

        // 2. Storage check
        val requiredBytes = if (pkg.sizeBytes > 0) pkg.sizeBytes * 2 else 50L * 1024 * 1024
        if (!checkStorageSpace(requiredBytes)) {
            val reqMb = requiredBytes / (1024 * 1024)
            throw IllegalStateException("Insufficient disk space: Need at least ${reqMb} MB available")
        }

        // 3. Obtain archive file
        val archiveFile = if (localArchive != null && localArchive.exists()) {
            localArchive
        } else {
            if (pkg.downloadUrl.isBlank()) {
                throw IllegalArgumentException("Cannot download package ${pkg.id}: downloadUrl is empty")
            }
            val cacheTarget = File(cacheDir, "${pkg.id}-${pkg.version}-${pkg.abi}.tar.gz")
            onProgress?.invoke(InstallProgress("Downloading package", 10))
            downloadPackage(
                downloadUrl = pkg.downloadUrl,
                targetFile = cacheTarget,
                expectedSizeBytes = pkg.sizeBytes,
                onProgress = { dp ->
                    onProgress?.invoke(InstallProgress("Downloading (${dp.progressPercent}%)", 10 + (dp.progressPercent * 0.4).toInt(), dp.bytesDownloaded, dp.totalBytes))
                },
                isCancelled = isCancelled
            )
        }

        // 4. Verify SHA-256 before extraction
        onProgress?.invoke(InstallProgress("Verifying checksum", 55))
        if (pkg.sha256.isNotBlank()) {
            if (!verifyChecksum(archiveFile, pkg.sha256)) {
                val actual = computeSha256(archiveFile)
                throw IllegalArgumentException("SHA-256 mismatch for ${pkg.id}: expected ${pkg.sha256}, got $actual")
            }
        }

        // 5. Extract
        onProgress?.invoke(InstallProgress("Extracting files", 65))
        installPackageArchive(archiveFile, null) { ip ->
            onProgress?.invoke(InstallProgress(ip.stage, 65 + (ip.progressPercent * 0.35).toInt(), ip.bytesProcessed, ip.totalBytes))
        }

        true
    }

    /**
     * Installs a runtime package from a local archive (.tar.gz)
     */
    suspend fun installPackageArchive(
        archiveFile: File,
        expectedSha256: String?,
        onProgress: ((InstallProgress) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress?.invoke(InstallProgress("Verifying Checksum", 10))

        if (!expectedSha256.isNullOrBlank()) {
            val calculated = computeSha256(archiveFile)
            if (!calculated.equals(expectedSha256, ignoreCase = true)) {
                throw IllegalArgumentException("SHA-256 verification failed! Expected $expectedSha256, got $calculated")
            }
        }

        onProgress?.invoke(InstallProgress("Extracting Runtime", 30))

        // Extract tar.gz into usrDir
        FileInputStream(archiveFile).use { fis ->
            extractTarGz(fis, usrDir) { percent ->
                onProgress?.invoke(InstallProgress("Extracting Files", 30 + (percent * 0.6).toInt()))
            }
        }

        // Apply executable permissions to all binaries in bin/
        onProgress?.invoke(InstallProgress("Configuring Permissions", 95))
        binDir.listFiles()?.forEach { binary ->
            if (binary.isFile) {
                binary.setExecutable(true, false)
                binary.setReadable(true, false)
            }
        }

        onProgress?.invoke(InstallProgress("Installation Completed", 100))
        true
    }

    /**
     * Uninstalls a specific binary and associated files from usr/
     */
    fun uninstallBinary(binaryName: String): Boolean {
        val binaryFile = File(binDir, binaryName)
        return if (binaryFile.exists()) binaryFile.delete() else false
    }

    /**
     * Uninstalls all binaries provided by the given package.
     */
    fun uninstallPackage(pkg: RuntimePackage): Boolean {
        val binaries = pkg.provides.ifEmpty { listOf(pkg.id) }
        var anyDeleted = false
        for (bin in binaries) {
            val binaryFile = File(binDir, bin)
            if (binaryFile.exists()) {
                if (binaryFile.delete()) {
                    anyDeleted = true
                }
            }
        }
        return anyDeleted
    }

    fun isBinaryInstalled(binaryName: String): Boolean {
        val file = File(binDir, binaryName)
        return file.exists() && file.canExecute()
    }

    fun getBinaryVersion(binaryName: String): String? {
        val file = File(binDir, binaryName)
        if (!file.exists() || !file.canExecute()) return null

        return try {
            val process = ProcessBuilder(file.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readLine()
            process.waitFor()
            output?.trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts a Tar.gz stream into targetDir using a temporary staging directory.
     * Supports:
     * - Atomic extraction (aborts cleanly on failure without leaving partial files)
     * - Symlinks (with traversal protection for both symlink path and target)
     * - Long filenames (USTAR prefix, GNU 'L', PAX extended headers)
     * - Long link names (GNU 'K', PAX 'linkpath')
     * - File permissions from TAR mode header
     * - Path traversal prevention
     */
    fun extractTarGz(inputStream: InputStream, targetDir: File, onPercent: ((Int) -> Unit)? = null) {
        targetDir.mkdirs()
        val stagingDir = File(targetDir.parentFile ?: targetDir, ".staging_${targetDir.name}_${System.nanoTime()}").apply { mkdirs() }

        try {
            GZIPInputStream(inputStream).use { gzipStream ->
                val headerBuffer = ByteArray(512)
                var nextLongFileName: String? = null
                var nextLongLinkName: String? = null
                var nextPaxAttributes = mutableMapOf<String, String>()

                while (true) {
                    val readHeaderBytes = readFully(gzipStream, headerBuffer, 512)
                    if (readHeaderBytes < 512) break

                    // Check for end of archive (two zero-filled 512-byte blocks)
                    if (headerBuffer.all { it == 0.toByte() }) {
                        break
                    }

                    // 1. Read header fields
                    var rawFileName = parseTarString(headerBuffer, 0, 100).trim()
                    val modeOctal = parseTarString(headerBuffer, 100, 8).trim()
                    val mode = modeOctal.toIntOrNull(8) ?: 0
                    val sizeOctal = parseTarString(headerBuffer, 124, 12).trim()
                    val fileSize = sizeOctal.toLongOrNull(8) ?: 0L
                    val typeFlag = headerBuffer[156].toInt().toChar()
                    var rawLinkName = parseTarString(headerBuffer, 157, 100).trim()

                    // Check USTAR prefix
                    val magic = parseTarString(headerBuffer, 257, 6).trim()
                    if (magic.startsWith("ustar")) {
                        val prefix = parseTarString(headerBuffer, 345, 155).trim()
                        if (prefix.isNotEmpty()) {
                            rawFileName = "$prefix/$rawFileName"
                        }
                    }

                    // 2. Handle GNU Long Filename ('L')
                    if (typeFlag == 'L') {
                        val longNameBytes = readEntryData(gzipStream, fileSize)
                        nextLongFileName = String(longNameBytes, Charsets.UTF_8).trimEnd('\u0000', '\n')
                        skipTarPadding(gzipStream, fileSize)
                        continue
                    }

                    // 3. Handle GNU Long Link Target ('K')
                    if (typeFlag == 'K') {
                        val longLinkBytes = readEntryData(gzipStream, fileSize)
                        nextLongLinkName = String(longLinkBytes, Charsets.UTF_8).trimEnd('\u0000', '\n')
                        skipTarPadding(gzipStream, fileSize)
                        continue
                    }

                    // 4. Handle PAX Extended Header ('x' or 'g')
                    if (typeFlag == 'x' || typeFlag == 'g') {
                        val paxBytes = readEntryData(gzipStream, fileSize)
                        nextPaxAttributes = parsePaxExtendedHeader(paxBytes)
                        skipTarPadding(gzipStream, fileSize)
                        continue
                    }

                    // Resolve effective filename and linkname
                    val fileName = nextLongFileName ?: nextPaxAttributes["path"] ?: rawFileName
                    val linkName = nextLongLinkName ?: nextPaxAttributes["linkpath"] ?: rawLinkName

                    nextLongFileName = null
                    nextLongLinkName = null
                    nextPaxAttributes = mutableMapOf()

                    if (fileName.isEmpty()) {
                        skipTarData(gzipStream, fileSize)
                        continue
                    }

                    val destFile = File(stagingDir, fileName).canonicalFile

                    // SECURITY CHECK: Path Traversal prevention on filename
                    val stagingCanonical = stagingDir.canonicalPath
                    if (!destFile.path.startsWith(stagingCanonical + File.separator) && destFile.path != stagingCanonical) {
                        throw SecurityException("Tar entry is outside destination: $fileName")
                    }

                    // 5. Extract based on entry type
                    when (typeFlag) {
                        '5' -> {
                            // Directory
                            destFile.mkdirs()
                            skipTarData(gzipStream, fileSize)
                        }
                        '2' -> {
                            // Symbolic link
                            if (linkName.isEmpty()) {
                                skipTarData(gzipStream, fileSize)
                                continue
                            }

                            // SECURITY CHECK: Symlink destination validation
                            val isRelative = !linkName.startsWith("/")
                            val resolvedTarget = if (isRelative) {
                                File(destFile.parentFile ?: stagingDir, linkName).canonicalFile
                            } else {
                                File(stagingDir, linkName.removePrefix("/")).canonicalFile
                            }

                            if (!resolvedTarget.path.startsWith(stagingCanonical + File.separator) &&
                                resolvedTarget.path != stagingCanonical
                            ) {
                                throw SecurityException("Symlink target escapes destination directory: $fileName -> $linkName")
                            }

                            destFile.parentFile?.mkdirs()
                            destFile.delete()
                            try {
                                java.nio.file.Files.createSymbolicLink(destFile.toPath(), java.nio.file.Paths.get(linkName))
                            } catch (_: Exception) {
                                // Fallback for filesystems that do not allow symlinks: copy or record
                            }
                            skipTarData(gzipStream, fileSize)
                        }
                        else -> {
                            // Regular file ('0' or '\u0000')
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { fos ->
                                var remaining = fileSize
                                val buffer = ByteArray(8192)
                                while (remaining > 0) {
                                    val toRead = Math.min(remaining, buffer.size.toLong()).toInt()
                                    val count = gzipStream.read(buffer, 0, toRead)
                                    if (count <= 0) break
                                    fos.write(buffer, 0, count)
                                    remaining -= count
                                }
                            }

                            // Apply permissions from TAR mode header
                            applyTarPermissions(destFile, mode, fileName)
                            skipTarPadding(gzipStream, fileSize)
                        }
                    }
                }
            }

            // Move extracted contents from stagingDir to targetDir
            mergeStagingIntoTarget(stagingDir, targetDir)
            onPercent?.invoke(100)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun applyTarPermissions(file: File, mode: Int, relativePath: String) {
        val isExecutable = (mode and 0b001_000_000) != 0 ||
                (mode and 0b000_001_000) != 0 ||
                (mode and 0b000_000_001) != 0 ||
                relativePath.startsWith("bin/") ||
                relativePath.contains("/bin/") ||
                file.parentFile?.name == "bin"

        if (isExecutable) {
            file.setExecutable(true, false)
        }

        val isWritable = (mode and 0b010_000_000) != 0
        if (isWritable) {
            file.setWritable(true, false)
        }

        val isReadable = (mode and 0b100_000_000) != 0
        if (isReadable) {
            file.setReadable(true, false)
        }
    }

    private fun mergeStagingIntoTarget(stagingDir: File, targetDir: File) {
        targetDir.mkdirs()
        stagingDir.listFiles()?.forEach { file ->
            val destFile = File(targetDir, file.name)
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                destFile.delete()
                val targetLink = java.nio.file.Files.readSymbolicLink(file.toPath())
                try {
                    java.nio.file.Files.createSymbolicLink(destFile.toPath(), targetLink)
                } catch (_: Exception) {}
            } else if (file.isDirectory) {
                mergeStagingIntoTarget(file, destFile)
            } else {
                destFile.parentFile?.mkdirs()
                file.copyTo(destFile, overwrite = true)
                if (file.canExecute()) {
                    destFile.setExecutable(true, false)
                }
            }
        }
    }

    private fun readEntryData(input: InputStream, size: Long): ByteArray {
        val buffer = ByteArray(size.toInt())
        readFully(input, buffer, buffer.size)
        return buffer
    }

    private fun skipTarData(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val toRead = Math.min(remaining, buffer.size.toLong()).toInt()
            val count = input.read(buffer, 0, toRead)
            if (count <= 0) break
            remaining -= count
        }
        skipTarPadding(input, size)
    }

    private fun skipTarPadding(input: InputStream, fileSize: Long) {
        val padding = (512 - (fileSize % 512)) % 512
        if (padding > 0) {
            var remaining = padding
            val buffer = ByteArray(padding.toInt())
            while (remaining > 0) {
                val count = input.read(buffer, 0, remaining.toInt())
                if (count <= 0) break
                remaining -= count
            }
        }
    }

    private fun parsePaxExtendedHeader(data: ByteArray): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        val text = String(data, Charsets.UTF_8)
        var index = 0
        while (index < text.length) {
            val spaceIndex = text.indexOf(' ', index)
            if (spaceIndex == -1) break
            val lengthStr = text.substring(index, spaceIndex).trim()
            val length = lengthStr.toIntOrNull() ?: break
            val nextRecordIndex = index + length
            val record = text.substring(spaceIndex + 1, minOf(nextRecordIndex - 1, text.length))
            val equalIndex = record.indexOf('=')
            if (equalIndex != -1) {
                val key = record.substring(0, equalIndex).trim()
                val value = record.substring(equalIndex + 1)
                result[key] = value
            }
            index = nextRecordIndex
        }
        return result
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val count = input.read(buffer, total, length - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun parseTarString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && bytes[end] != 0.toByte()) {
            end++
        }
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }
}
