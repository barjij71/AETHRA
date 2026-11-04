package org.aethra.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Verifies the app's build identity is wired correctly in [android.sourceSets].
 *
 * This is a thin guard against the manifest, [app/build.gradle.kts], and
 * [build.gradle.kts] drifting out of sync. It is intentionally small and does
 * not touch any Android framework code, so it runs in the standard JVM test
 * harness with no device required.
 */
@RunWith(JUnit4::class)
class AppBuildConfigTest {

    @Test
    fun applicationIdMatchesNamespace() {
        assertEquals("Namespace and applicationId must agree so the package",
            "org.aethra.assistant", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun versionNameMatchesManifestAndBuildScript() {
        assertEquals("0.1.0", BuildConfig.VERSION_NAME)
    }

    @Test
    fun versionCodeIsOneForFirstRelease() {
        assertEquals(1, BuildConfig.VERSION_CODE)
    }
}
