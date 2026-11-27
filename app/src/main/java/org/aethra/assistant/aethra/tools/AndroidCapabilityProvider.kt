package org.aethra.assistant.aethra.tools

import org.aethra.core.tools.CapabilityAvailability

/**
 * Android implementation of CapabilityAvailability.
 *
 * This authority declares what capabilities the current Android execution environment
 * actually provides. It is the TRUSTED source for capability availability - tools may NOT
 * self-authorize or claim capabilities they don't have.
 *
 * Capabilities are based on actual implemented operations in AndroidTools/AppTools/
 * other Android-specific tool implementations. Only capabilities where the runtime
 * has a working implementation are declared.
 *
 * Thread safety: This provider is stateless and thread-safe.
 */
class AndroidCapabilityProvider : CapabilityAvailability {

    /**
     * Set of capabilities actually supported by the current Android runtime.
     * 
     * These capabilities correspond to real operations implemented in:
     * - Native Tools (core): app.launch, app.query, file.read, file.write, clipboard.read, clipboard.write, memory.write, notification.read
     * - AndroidTools.kt: file.read, clipboard.read
     * - AppTools.kt: app.launch, app.query, memory.write
     * - DeviceInfoTool.kt: device.query, app.query
     */
    override fun hasCapability(name: String): Boolean {
        return when (name) {
            // File I/O capabilities
            CAPABILITY_FILE_READ -> true
            CAPABILITY_FILE_WRITE -> true

            // Clipboard capabilities
            CAPABILITY_CLIPBOARD_READ -> true
            CAPABILITY_CLIPBOARD_WRITE -> true

            // Application capabilities
            CAPABILITY_APP_LAUNCH -> true
            CAPABILITY_APP_QUERY -> true

            // Device information capabilities
            CAPABILITY_DEVICE_INFO -> true
            CAPABILITY_DEVICE_QUERY -> true

            // Notification capabilities
            CAPABILITY_NOTIFICATION_READ -> false

            // Memory capabilities
            CAPABILITY_MEMORY_WRITE -> true

            // External capability bridge (InstaBox, etc.)
            CAPABILITY_EXTERNAL -> true

            else -> false
        }
    }

    companion object {
        // Capability identifiers - must match tool.requiredCapabilities declarations

        /** File system read capability */
        const val CAPABILITY_FILE_READ = "file.read"

        /** File system write capability */
        const val CAPABILITY_FILE_WRITE = "file.write"

        /** Clipboard read capability */
        const val CAPABILITY_CLIPBOARD_READ = "clipboard.read"

        /** Clipboard write capability */
        const val CAPABILITY_CLIPBOARD_WRITE = "clipboard.write"

        /** Application launch capability (intents) */
        const val CAPABILITY_APP_LAUNCH = "app.launch"

        /** Application query capability (package info) */
        const val CAPABILITY_APP_QUERY = "app.query"

        /** Device information capability */
        const val CAPABILITY_DEVICE_INFO = "device.info"

        /** Device query capability (device.info tool uses this) */
        const val CAPABILITY_DEVICE_QUERY = "device.query"

        /** Notification read capability */
        const val CAPABILITY_NOTIFICATION_READ = "notification.read"

        /** Memory write capability */
        const val CAPABILITY_MEMORY_WRITE = "memory.write"

        /** External capability bridge (remote execution) */
        const val CAPABILITY_EXTERNAL = "external_capability"
    }
}