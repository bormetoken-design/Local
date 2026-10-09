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
     * Zero-dependency streaming POSIX Tar.gz extractor.
     */
    private fun extractTarGz(inputStream: InputStream, targetDir: File, onPercent: (Int) -> Unit) {
        GZIPInputStream(inputStream).use { gzipStream ->
            val headerBuffer = ByteArray(512)
            var totalRead = 0L

            while (true) {
                val readHeaderBytes = readFully(gzipStream, headerBuffer, 512)
                if (readHeaderBytes < 512) break

                // Check for end of archive (two zero-filled 512-byte blocks)
                if (headerBuffer.all { it == 0.toByte() }) {
                    break
                }

                // Parse TAR header
                val fileName = parseTarString(headerBuffer, 0, 100).trim()
                if (fileName.isEmpty()) continue

                val sizeOctal = parseTarString(headerBuffer, 124, 12).trim()
                val fileSize = sizeOctal.toLongOrNull(8) ?: 0L
                val typeFlag = headerBuffer[156].toInt().toChar()

                val destFile = File(targetDir, fileName).canonicalFile
                // Security check against directory traversal
                if (!destFile.path.startsWith(targetDir.canonicalPath + File.separator) &&
                    destFile.path != targetDir.canonicalPath
                ) {
                    throw SecurityException("Tar entry is outside destination: $fileName")
                }

                if (typeFlag == '5' || fileName.endsWith("/")) {
                    destFile.mkdirs()
                } else {
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
                            totalRead += count
                        }
                    }

                    // Files in bin/ get executable rights
                    if (fileName.startsWith("bin/") || destFile.parentFile?.name == "bin") {
                        destFile.setExecutable(true, false)
                    }
                }

                // TAR files pad entries to 512-byte boundaries
                val padding = (512 - (fileSize % 512)) % 512
                if (padding > 0) {
                    gzipStream.skip(padding)
                }
            }
        }
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
