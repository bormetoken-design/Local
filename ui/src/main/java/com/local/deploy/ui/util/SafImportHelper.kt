package com.local.deploy.ui.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.local.deploy.projects.manager.ProjectFileManager
import java.io.File

object SafImportHelper {

    fun getDisplayName(context: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        name = cursor.getString(nameIndex)
                    }
                }
            } catch (_: Exception) {}
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "uploaded-project"
    }

    fun unpackZip(
        context: Context,
        zipUri: Uri,
        targetDir: File,
        onProgress: ((entryName: String, bytesExtracted: Long) -> Unit)? = null
    ) {
        val inputStream = context.contentResolver.openInputStream(zipUri)
            ?: throw IllegalStateException("Unable to open input stream for URI: $zipUri")
        inputStream.use { stream ->
            ProjectFileManager.unpackZipSafely(stream, targetDir, onProgress)
        }
    }

    fun copyFolderTree(
        context: Context,
        treeUri: Uri,
        targetDir: File,
        onProgress: ((String) -> Unit)? = null
    ) {
        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Unable to open document tree for URI: $treeUri")

        targetDir.mkdirs()
        copyDocumentFileRecursively(context, documentFile, targetDir, onProgress)
    }

    private fun copyDocumentFileRecursively(
        context: Context,
        doc: DocumentFile,
        targetDir: File,
        onProgress: ((String) -> Unit)?
    ) {
        if (doc.isDirectory) {
            val subDir = File(targetDir, doc.name ?: "folder").apply { mkdirs() }
            doc.listFiles().forEach { child ->
                copyDocumentFileRecursively(context, child, subDir, onProgress)
            }
        } else if (doc.isFile) {
            val fileName = doc.name ?: "file"
            val destFile = File(targetDir, fileName)
            destFile.parentFile?.mkdirs()
            onProgress?.invoke(fileName)

            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
