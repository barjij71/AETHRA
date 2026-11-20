package org.aethra.core.tools

import org.aethra.core.domain.RiskClass

/**
 * Native Android tools that run the app's own platform APIs. These are the first
 * tier of the unified tool registry. They are thin wrappers: this module declares
 * the tool, but the actual dispatch is done by the platform layer to keep the
 * core free of Android imports.
 *
 * Each entry is security-classified. Actions that change device state (SMS/call,
 * settings, contacts, install) require explicit approval even though they are
 * presented here.
 */
class NativeTools {
    val tools: List<Tool> = listOf(
        Tool(
            id = "app.launch",
            description = "Launch an installed Android application",
            category = "app",
            requiredCapabilities = listOf("app.launch"),
            inputSchema = mapOf("package" to "android package name"),
            outputSchema = mapOf("ok" to "boolean"),
            riskClass = RiskClass.REVERSIBLE,
            approvalRequired = false,
            reversible = true,
            timeoutSeconds = 20
        ),
        Tool(
            id = "app.query",
            description = "Query installed apps and device state",
            category = "app",
            requiredCapabilities = listOf("app.query"),
            inputSchema = mapOf("query" to "string"),
            outputSchema = mapOf("results" to "list"),
            riskClass = RiskClass.READ_ONLY,
            approvalRequired = false,
            timeoutSeconds = 15
        ),
        Tool(
            id = "file.read",
            description = "Read a text file or directory listing",
            category = "file",
            requiredCapabilities = listOf("file.read"),
            outputSchema = mapOf("content" to "string"),
            riskClass = RiskClass.READ_ONLY,
            approvalRequired = false,
            reversible = true,
            timeoutSeconds = 20
        ),
        Tool(
            id = "file.write",
            description = "Write a text file in an authorised directory",
            category = "file",
            requiredCapabilities = listOf("file.write"),
            inputSchema = mapOf("path" to "string", "content" to "string"),
            riskClass = RiskClass.REVERSIBLE,
            approvalRequired = true,
            reversible = true,
            timeoutSeconds = 30
        ),
        Tool(
            id = "clipboard.read",
            description = "Read the clipboard text",
            category = "system",
            requiredCapabilities = listOf("clipboard.read"),
            outputSchema = mapOf("text" to "string"),
            riskClass = RiskClass.READ_ONLY,
            approvalRequired = false,
            timeoutSeconds = 10
        ),
        Tool(
            id = "clipboard.write",
            description = "Write text to the clipboard",
            category = "system",
            requiredCapabilities = listOf("clipboard.write"),
            inputSchema = mapOf("text" to "string"),
            riskClass = RiskClass.REVERSIBLE,
            approvalRequired = true,
            reversible = true,
            timeoutSeconds = 10
        ),
        Tool(
            id = "notification.read",
            description = "Inspect app notifications",
            category = "system",
            requiredCapabilities = listOf("notification.read"),
            outputSchema = mapOf("notifications" to "list"),
            riskClass = RiskClass.READ_ONLY,
            approvalRequired = false,
            timeoutSeconds = 20
        ),
        Tool(
            id = "memory.remember",
            description = "Persist a user-provided memory entry locally",
            category = "memory",
            requiredCapabilities = listOf("memory.write"),
            inputSchema = mapOf("text" to "memory text"),
            outputSchema = mapOf("id" to "memory id", "text" to "stored text"),
            riskClass = RiskClass.REVERSIBLE,
            approvalRequired = false,
            reversible = true,
            timeoutSeconds = 10
        ),
        Tool(
            id = "device.info",
            description = "Read battery, network and device identity",
            category = "system",
            requiredCapabilities = listOf("device.info"),
            outputSchema = mapOf("battery" to "map"),
            riskClass = RiskClass.READ_ONLY,
            approvalRequired = false,
            timeoutSeconds = 15,
            aliases = listOf("info device", "device info")
        )
    )
}
