package org.aethra.assistant.aethra.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.aethra.core.domain.RiskClass
import org.aethra.core.tools.Tool

class AppLaunchTool(private val context: Context) {
    val tool = Tool(
        id = "app.launch",
        description = "Launch an installed Android application by package name",
        category = "app",
        requiredCapabilities = listOf("app.launch"),
        inputSchema = mapOf("package" to "android package name"),
        outputSchema = mapOf("ok" to "boolean", "package" to "package name"),
        riskClass = RiskClass.REVERSIBLE,
        approvalRequired = false,
        reversible = true,
        timeoutSeconds = 20,
        native = true
    )

    suspend fun execute(packageName: String): Result<Map<String, Any>> {
        val name = packageName.trim()
        if (name.isEmpty()) {
            return Result.failure(IllegalArgumentException("app.launch requires a package name"))
        }
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(name)
                ?: return Result.failure(IllegalArgumentException("no launchable application: $name"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result.success(mapOf("ok" to true, "package" to name))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class AppQueryTool(private val context: Context) {
    val tool = Tool(
        id = "app.query",
        description = "List installed Android applications visible to this package",
        category = "app",
        requiredCapabilities = listOf("app.query"),
        inputSchema = mapOf("query" to "optional package or application name filter"),
        outputSchema = mapOf("results" to "list"),
        riskClass = RiskClass.READ_ONLY,
        approvalRequired = false,
        timeoutSeconds = 15,
        native = true
    )

    suspend fun execute(query: String = ""): Result<Map<String, Any>> {
        return try {
            val filter = query.trim().lowercase()
            val apps = context.packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { filter.isEmpty() || matches(it, filter) }
                .sortedBy { it.packageName }
                .map { app ->
                    mapOf(
                        "package" to app.packageName,
                        "label" to context.packageManager.getApplicationLabel(app).toString(),
                        "enabled" to app.enabled
                    )
                }
                .toList()
            Result.success(mapOf("results" to apps))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun matches(app: ApplicationInfo, filter: String): Boolean {
        val label = context.packageManager.getApplicationLabel(app).toString()
        return app.packageName.lowercase().contains(filter) || label.lowercase().contains(filter)
    }
}
