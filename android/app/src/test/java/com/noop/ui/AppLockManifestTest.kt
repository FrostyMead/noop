package com.noop.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockManifestTest {
    @Test
    fun `app declares permission required by platform biometric prompt`() {
        val manifest = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
            File("android/app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)

        assertTrue("Could not locate the Android manifest", manifest != null)
        assertTrue(
            "BiometricPrompt.authenticate requires USE_BIOMETRIC",
            manifest!!.readText().contains("android.permission.USE_BIOMETRIC"),
        )
    }
}
