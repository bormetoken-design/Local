package com.local.deploy.antikill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveHelperTest {

    @Test
    fun testOemAutostartIntents() {
        val xiaomiIntent = KeepAliveHelper.getAutostartIntent(DeviceManufacturer.XIAOMI)
        assertEquals("com.miui.securitycenter", xiaomiIntent.packageName)

        val oppoIntent = KeepAliveHelper.getAutostartIntent(DeviceManufacturer.OPPO)
        assertEquals("com.coloros.safecenter", oppoIntent.packageName)

        val huaweiIntent = KeepAliveHelper.getAutostartIntent(DeviceManufacturer.HUAWEI)
        assertEquals("com.huawei.systemmanager", huaweiIntent.packageName)
    }

    @Test
    fun testPhantomProcessKillerCommand() {
        val script = KeepAliveHelper.getPhantomProcessKillerAdbCommand()
        assertTrue(script.contains("max_phantom_processes 2147483647"))
    }

    @Test
    fun testSystemHealthScoreCalculation() {
        // When all conditions met: score = 100
        val perfectReport = KeepAliveHelper.evaluateSystemHealth(
            isBatteryOptimizedExempt = true,
            isNotificationPermissionGranted = true,
            isAutostartEnabled = true,
            isPhantomKillerDisabled = true,
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
            availableRamBytes = 2L * 1024 * 1024 * 1024
        )
        assertEquals(100, perfectReport.score)
        assertEquals(5, perfectReport.passedChecks)

        // When 2 conditions failed: score = 60
        val degradedReport = KeepAliveHelper.evaluateSystemHealth(
            isBatteryOptimizedExempt = false,
            isNotificationPermissionGranted = true,
            isAutostartEnabled = false,
            isPhantomKillerDisabled = true,
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
            availableRamBytes = 2L * 1024 * 1024 * 1024
        )
        assertEquals(60, degradedReport.score)
        assertEquals(3, degradedReport.passedChecks)
    }
}
