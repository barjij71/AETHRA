package org.aethra.assistant.aethra.tools

import android.content.Context
import org.aethra.core.domain.RiskClass
import org.aethra.core.tools.Tool
import java.io.File

/**
 * Real file reader, restricted to the app's own private storage
 * (filesDir + cacheDir) so READ_ONLY execution stays safe without any
 * privileged Android permission. Mirrors the NativeTools declaration
 * `file.read` (READ_ONLY; read a text file or a directory listing).
 */
class FileReadTool(private val context: Context) {
    val tool = Tool(
        id = "file.read",
        description = "Read a text file or directory listing inside the app's private storage",
        category = "file",
        requiredCapabilities = listOf("file.read"),
        inputSchema = mapOf("path" to "string"),
        outputSchema = mapOf("content" to "string"),
        riskClass = RiskClass.READ_ONLY,
        approvalRequired = false,
        reversible = true,
        timeoutSeconds = 20,
        native = true
    )

    suspend fun execute(path: String): Result<Map<String, Any>> {
        val authorisedRoots = listOfNotNull(
            canonicalOrNull(context.filesDir)?.absolutePath,
            canonicalOrNull(context.cacheDir)?.absolutePath
        )
        if (authorisedRoots.isEmpty()) {
            return Result.failure(IllegalStateException("app private storage is unavailable"))
        }

        if (path.isBlank()) {
            return Result.success(listing(context.filesDir))
        }

        val target = try {
            File(path).canonicalFile
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val inside = authorisedRoots.any { root ->
            target.path == root || target.path.startsWith(root + File.separator)
        }
        if (!inside) {
            return Result.failure(IllegalArgumentException(
                "file.read is authorised only inside the app's private files and cache directories"
            ))
        }
        if (!target.exists()) {
            return Result.failure(java.io.FileNotFoundException(target.path))
        }
        return if (target.isDirectory) {
            Result.success(listing(target))
        } else {
            Result.success(
                mapOf(
                    "path" to target.path,
                    "content" to target.readText()
                )
            )
        }
    }

    private fun listing(dir: File): Map<String, Any> {
        val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        return mapOf(
            "path" to (canonicalOrNull(dir)?.path ?: dir.path),
            "entries" to entries.map { if (it.isDirectory) it.name + "/" else it.name }
        )
    }

    private fun canonicalOrNull(file: File): File? = try {
        file.canonicalFile
    } catch (e: Exception) {
        null
    }
}
