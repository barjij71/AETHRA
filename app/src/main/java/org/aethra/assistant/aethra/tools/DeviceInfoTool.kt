package org.aethra.assistant.aethra.tools

import android.content.Context
import android.os.Build
import org.aethra.core.domain.RiskClass
import org.aethra.core.tools.Tool

class DeviceInfoTool(private val context: Context) {
    val tool = Tool(
        id = "device.info",
        description = "Return Android version, SDK, device model and application version",
        category = "device",
        requiredCapabilities = listOf("device.query", "app.query"),
        inputSchema = mapOf("query" to "string"),
        outputSchema = mapOf("info" to "object"),
        riskClass = RiskClass.READ_ONLY,
        approvalRequired = false,
        reversible = true,
        timeoutSeconds = 15,
        native = true
    )

    suspend fun execute(): Result<Map<String, Any>> {
        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val info = mapOf<String, Any>(
            "android.version" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "model" to Build.MODEL,
            "product" to Build.PRODUCT,
            "manufacturer" to Build.MANUFACTURER,
            "device.id" to Build.DEVICE,
            "app.version.name" to (packageInfo.versionName ?: "unknown"),
            "app.version.code" to packageInfo.versionCode.toString(),
            "app.id" to context.packageName,
            "architecture" to (Build.SUPPORTED_ABIS.firstOrNull()
                ?: Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
                ?: "unknown")
        )
        return Result.success(info)
    }
}
