package com.local.deploy.projects.manager

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class SecurityException(message: String) : RuntimeException(message)

object ProjectFileManager {

    /**
     * Prepares standard sandbox directories for a project under /data/data/<package>/files/projects/<id>
     */
    fun createProjectDirectoryStructure(projectBaseDir: File): ProjectDirectories {
        val appDir = File(projectBaseDir, "app").apply { mkdirs() }
        val dataDir = File(projectBaseDir, "data").apply { mkdirs() }
        val logsDir = File(projectBaseDir, "logs").apply { mkdirs() }
        val projectJson = File(projectBaseDir, "project.json")
        val envFile = File(projectBaseDir, ".env")

        return ProjectDirectories(
            baseDir = projectBaseDir,
            appDir = appDir,
            dataDir = dataDir,
            logsDir = logsDir,
            projectJson = projectJson,
            envFile = envFile
        )
    }

    /**
     * Unpacks a ZIP file into the destination directory with strict Zip-Slip protection.
     * Also detects single root folder nesting and unwraps if appropriate.
     */
    fun unpackZipSafely(
        zipInputStream: InputStream,
        targetDir: File,
        onProgress: ((entryName: String, bytesExtracted: Long) -> Unit)? = null
    ) {
        targetDir.mkdirs()
        val canonicalDestination = targetDir.canonicalFile

        ZipInputStream(zipInputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            var totalBytes: Long = 0

            while (entry != null) {
                val newFile = File(targetDir, entry.name)

                // CRITICAL SECURITY CHECK: Zip-Slip protection
                val canonicalDestFile = newFile.canonicalFile
                if (!canonicalDestFile.path.startsWith(canonicalDestination.path + File.separator) &&
                    canonicalDestFile.path != canonicalDestination.path
                ) {
                    throw SecurityException("Zip entry is outside of target directory: ${entry.name}")
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                            totalBytes += len
                        }
                    }
                }

                onProgress?.invoke(entry.name, totalBytes)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Unwrap single top-level directory if present
        unwrapSingleFolderIfPresent(targetDir)
    }

    /**
     * If the ZIP extracted all files into a single folder (e.g. repo-main/), moves contents up.
     */
    private fun unwrapSingleFolderIfPresent(targetDir: File) {
        val files = targetDir.listFiles() ?: return
        if (files.size == 1 && files[0].isDirectory) {
            val singleDir = files[0]
            if (singleDir.name == "app" || singleDir.name == "data" || singleDir.name == "logs") {
                return // Standard project layout, do not unwrap
            }
            val innerFiles = singleDir.listFiles() ?: return
            innerFiles.forEach { child ->
                val dest = File(targetDir, child.name)
                child.renameTo(dest)
            }
            singleDir.delete()
        }
    }

    /**
     * Copies a directory tree from source to destination recursively.
     */
    fun copyDirectory(source: File, destination: File) {
        if (!source.exists()) return
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles()?.forEach { file ->
                copyDirectory(file, File(destination, file.name))
            }
        } else {
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }
}

data class ProjectDirectories(
    val baseDir: File,
    val appDir: File,
    val dataDir: File,
    val logsDir: File,
    val projectJson: File,
    val envFile: File
)
