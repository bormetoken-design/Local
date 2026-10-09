package com.local.deploy.backup

import com.local.deploy.projects.manager.ProjectFileManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupMetadata(
    val projectId: String,
    val projectName: String,
    val timestamp: Long,
    val backupFile: File,
    val isEncrypted: Boolean
)

object BackupManager {

    private const val AES_GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 65536
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12

    /**
     * Creates a zip backup containing app/, data/, project.json, and .env
     */
    fun createBackup(
        projectDir: File,
        projectId: String,
        projectName: String,
        destinationDir: File,
        password: String? = null
    ): BackupMetadata {
        destinationDir.mkdirs()
        val timestamp = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        val rawZipFile = File(destinationDir, "${projectId}_backup_$timeStr.tmp.zip")

        ZipOutputStream(FileOutputStream(rawZipFile)).use { zos ->
            // Include app/
            val appDir = File(projectDir, "app")
            if (appDir.exists()) {
                zipFolderRecursively(appDir, "app", zos)
            }

            // Include data/
            val dataDir = File(projectDir, "data")
            if (dataDir.exists()) {
                zipFolderRecursively(dataDir, "data", zos)
            }

            // Include project.json
            val projectJson = File(projectDir, "project.json")
            if (projectJson.exists()) {
                zipSingleFile(projectJson, "project.json", zos)
            }

            // Include .env
            val envFile = File(projectDir, ".env")
            if (envFile.exists()) {
                zipSingleFile(envFile, ".env", zos)
            }
        }

        val finalBackupFile: File
        val isEncrypted = !password.isNullOrBlank()

        if (isEncrypted) {
            finalBackupFile = File(destinationDir, "${projectId}_backup_$timeStr.bak.enc")
            encryptFile(rawZipFile, finalBackupFile, password!!)
            rawZipFile.delete()
        } else {
            finalBackupFile = File(destinationDir, "${projectId}_backup_$timeStr.bak.zip")
            rawZipFile.renameTo(finalBackupFile)
        }

        return BackupMetadata(
            projectId = projectId,
            projectName = projectName,
            timestamp = timestamp,
            backupFile = finalBackupFile,
            isEncrypted = isEncrypted
        )
    }

    /**
     * Restores project from backup file into target destination.
     */
    fun restoreBackup(
        backupFile: File,
        destinationProjectDir: File,
        password: String? = null
    ) {
        val tempZipToExtract: File

        if (backupFile.name.endsWith(".enc")) {
            if (password.isNullOrBlank()) {
                throw IllegalArgumentException("Password required to restore encrypted backup.")
            }
            tempZipToExtract = File(destinationProjectDir.parentFile, "restore_temp_${System.currentTimeMillis()}.zip")
            decryptFile(backupFile, tempZipToExtract, password)
        } else {
            tempZipToExtract = backupFile
        }

        try {
            FileInputStream(tempZipToExtract).use { fis ->
                ProjectFileManager.unpackZipSafely(fis, destinationProjectDir)
            }
        } finally {
            if (tempZipToExtract != backupFile && tempZipToExtract.exists()) {
                tempZipToExtract.delete()
            }
        }
    }

    private fun zipFolderRecursively(folder: File, basePath: String, zos: ZipOutputStream) {
        folder.listFiles()?.forEach { file ->
            val entryPath = "$basePath/${file.name}"
            if (file.isDirectory) {
                zos.putNextEntry(ZipEntry("$entryPath/"))
                zos.closeEntry()
                zipFolderRecursively(file, entryPath, zos)
            } else {
                zipSingleFile(file, entryPath, zos)
            }
        }
    }

    private fun zipSingleFile(file: File, entryPath: String, zos: ZipOutputStream) {
        zos.putNextEntry(ZipEntry(entryPath))
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var len: Int
            while (fis.read(buf).also { len = it } > 0) {
                zos.write(buf, 0, len)
            }
        }
        zos.closeEntry()
    }

    private fun encryptFile(inputFile: File, outputFile: File, pass: String) {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }

        val keySpec = PBEKeySpec(pass.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec)
        val aesKey = SecretKeySpec(secretKey.encoded, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))

        FileOutputStream(outputFile).use { fos ->
            fos.write(salt)
            fos.write(iv)
            val inputBytes = inputFile.readBytes()
            val encryptedBytes = cipher.doFinal(inputBytes)
            fos.write(encryptedBytes)
        }
    }

    private fun decryptFile(inputFile: File, outputFile: File, pass: String) {
        FileInputStream(inputFile).use { fis ->
            val salt = ByteArray(SALT_LENGTH)
            fis.read(salt)
            val iv = ByteArray(IV_LENGTH)
            fis.read(iv)

            val cipherBytes = fis.readBytes()

            val keySpec = PBEKeySpec(pass.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
            val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec)
            val aesKey = SecretKeySpec(secretKey.encoded, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))

            val decrypted = cipher.doFinal(cipherBytes)
            outputFile.writeBytes(decrypted)
        }
    }
}
