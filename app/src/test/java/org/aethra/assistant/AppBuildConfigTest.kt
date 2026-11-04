package org.aethra.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Verifies the debug build type is wired into [BuildConfig] the way the
 * build scripts declare (see [app/src/main/java/org/aethra/assistant/MainActivity.kt]
 * and [build.gradle.kts] in the same module).
 *
 * BuildConfig is generated per variant, so these assertions pin down that the
 * debug variant applies its documented applicationIdSuffix and versionNameSuffix.
 */
@RunWith(JUnit4::class)
class AppBuildConfigTest {

    @Test
    fun debugVariantAppliesApplicationIdSuffix() {
        assertEquals("debug build type must append .debug to the applicationId",
            "org.aethra.assistant.debug", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun debugVariantAppliesVersionNameSuffix() {
        assertEquals("debug build type must append -debug to the versionName",
            "0.1.0-debug", BuildConfig.VERSION_NAME)
    }

    @Test
    fun versionCodeIsOneForFirstRelease() {
        assertEquals("versionCode is shared across build types, no suffix here",
            1, BuildConfig.VERSION_CODE)
    }
}
