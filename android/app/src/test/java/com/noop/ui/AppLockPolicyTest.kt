package com.noop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test
    fun `enabled app locks whenever activity leaves foreground`() {
        assertTrue(AppLockPolicy.shouldLockOnStop(enabled = true, isChangingConfigurations = false))
    }

    @Test
    fun `disabled app never locks`() {
        assertFalse(AppLockPolicy.shouldLockOnStop(enabled = false, isChangingConfigurations = false))
    }

    @Test
    fun `configuration change does not lock app`() {
        assertFalse(AppLockPolicy.shouldLockOnStop(enabled = true, isChangingConfigurations = true))
    }
}
