package com.local.deploy.packages

import com.local.deploy.model.RuntimePackage
import com.local.deploy.model.RuntimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class InstallProgress(
    val stage: String,
    val progressPercent: Int,
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L
)

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
