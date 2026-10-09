package com.local.deploy.antikill

import com.local.deploy.model.HealthFixAction
import com.local.deploy.model.SystemHealthItem
import com.local.deploy.model.SystemHealthReport

enum class DeviceManufacturer(val brandName: String) {
    XIAOMI("Xiaomi"),
    SAMSUNG("Samsung"),
    OPPO("Oppo"),
    VIVO("Vivo"),
    HUAWEI("Huawei"),
    ONEPLUS("OnePlus"),
    REALME("Realme"),
    GENERIC("Generic")
}

data class IntentResolution(
    val action: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val description: String
)

object KeepAliveHelper {

    fun detectManufacturer(): DeviceManufacturer {
        val manufacturer = System.getProperty("ro.product.manufacturer")
            ?: System.getenv("MANUFACTURER")
            ?: "Generic"

        val lower = manufacturer.lowercase()
        return when {
            lower.contains("xiaomi") || lower.contains("redmi") || lower.contains("poco") -> DeviceManufacturer.XIAOMI
            lower.contains("samsung") -> DeviceManufacturer.SAMSUNG
            lower.contains("oppo") -> DeviceManufacturer.OPPO
            lower.contains("vivo") || lower.contains("iqoo") -> DeviceManufacturer.VIVO
            lower.contains("huawei") || lower.contains("honor") -> DeviceManufacturer.HUAWEI
            lower.contains("oneplus") -> DeviceManufacturer.ONEPLUS
            lower.contains("realme") -> DeviceManufacturer.REALME
            else -> DeviceManufacturer.GENERIC
        }
    }

    /**
     * Returns known OEM Autostart settings intents for vendor background management.
     */
    fun getAutostartIntent(manufacturer: DeviceManufacturer = detectManufacturer()): IntentResolution {
        return when (manufacturer) {
            DeviceManufacturer.XIAOMI -> IntentResolution(
                packageName = "com.miui.securitycenter",
                className = "com.miui.permcenter.autostart.AutoStartManagementActivity",
                description = "Xiaomi Security -> Autostart Management"
            )
            DeviceManufacturer.OPPO, DeviceManufacturer.REALME -> IntentResolution(
                packageName = "com.coloros.safecenter",
                className = "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                description = "ColorOS / Realme Security -> Startup Manager"
            )
            DeviceManufacturer.VIVO -> IntentResolution(
                packageName = "com.iqoo.secure",
                className = "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                description = "Vivo iManager -> Background White-list"
            )
            DeviceManufacturer.HUAWEI -> IntentResolution(
                packageName = "com.huawei.systemmanager",
                className = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                description = "Huawei Phone Manager -> Launch Manager"
            )
            DeviceManufacturer.SAMSUNG -> IntentResolution(
                action = "android.settings.APPLICATION_DETAILS_SETTINGS",
                description = "Samsung Device Care -> Never Sleeping Apps"
            )
            else -> IntentResolution(
                action = "android.settings.SETTINGS",
                description = "System Settings"
            )
        }
    }

    /**
     * Script and instructions to disable Phantom Process Killer on Android 12+ via Wireless ADB.
     */
    fun getPhantomProcessKillerAdbCommand(): String {
        return """
            # Run via Wireless Debugging / Shizuku / Termux / PC ADB:
            /system/bin/device_config set_sync_disabled_for_tests persistent
            /system/bin/device_config put activity_manager max_phantom_processes 2147483647
            echo "Phantom Process Killer successfully disabled!"
        """.trimIndent()
    }

    /**
     * Evaluates comprehensive health score and checklist items based on environment states.
     */
    fun evaluateSystemHealth(
        isBatteryOptimizedExempt: Boolean,
        isNotificationPermissionGranted: Boolean,
        isAutostartEnabled: Boolean,
        isPhantomKillerDisabled: Boolean,
        availableStorageBytes: Long,
        availableRamBytes: Long
    ): SystemHealthReport {
        val items = mutableListOf<SystemHealthItem>()

        // 1. Battery Optimization
        items.add(
            SystemHealthItem(
                id = "battery_optimization",
                title = "Battery Optimization Exemption",
                description = if (isBatteryOptimizedExempt) "App is exempt from Doze and deep sleep."
                else "App may be suspended when screen turns off. Exemption required.",
                isPassed = isBatteryOptimizedExempt,
                severity = "HIGH",
                fixAction = if (isBatteryOptimizedExempt) HealthFixAction.NONE else HealthFixAction.OPEN_BATTERY_SETTINGS
            )
        )

        // 2. Notification Permission
        items.add(
            SystemHealthItem(
                id = "notification_permission",
                title = "Notification Permission",
                description = if (isNotificationPermissionGranted) "Foreground Service notification can display."
                else "Android requires notification permission to run persistent background services.",
                isPassed = isNotificationPermissionGranted,
                severity = "HIGH",
                fixAction = if (isNotificationPermissionGranted) HealthFixAction.NONE else HealthFixAction.REQUEST_NOTIFICATION_PERMISSION
            )
        )

        // 3. OEM Autostart
        items.add(
            SystemHealthItem(
                id = "oem_autostart",
                title = "OEM Background Autostart (${detectManufacturer().brandName})",
                description = if (isAutostartEnabled) "Autostart configured for this device manufacturer."
                else "Custom manufacturer skins may aggressively kill processes upon memory pressure.",
                isPassed = isAutostartEnabled,
                severity = "MEDIUM",
                fixAction = if (isAutostartEnabled) HealthFixAction.NONE else HealthFixAction.OPEN_AUTOSTART_SETTINGS
            )
        )

        // 4. Phantom Process Killer
        items.add(
            SystemHealthItem(
                id = "phantom_process_killer",
                title = "Phantom Process Killer",
                description = if (isPhantomKillerDisabled) "Child processes will not be killed by Android 12+ limit."
                else "Android 12+ limits background child processes to 32. Can be disabled via Wireless ADB.",
                isPassed = isPhantomKillerDisabled,
                severity = "MEDIUM",
                fixAction = if (isPhantomKillerDisabled) HealthFixAction.NONE else HealthFixAction.RUN_ADB_PAIRING
            )
        )

        // 5. Storage (requires at least 500MB)
        val hasEnoughStorage = availableStorageBytes >= 500L * 1024 * 1024
        items.add(
            SystemHealthItem(
                id = "disk_storage",
                title = "Internal Storage Space",
                description = if (hasEnoughStorage) "Adequate storage available for runtime and project files."
                else "Low storage (<500MB). Projects or log writes may fail.",
                isPassed = hasEnoughStorage,
                severity = "HIGH",
                fixAction = if (hasEnoughStorage) HealthFixAction.NONE else HealthFixAction.CLEAN_DISK_SPACE
            )
        )

        val passedCount = items.count { it.isPassed }
        val score = (passedCount * 100) / items.size

        return SystemHealthReport(
            score = score,
            totalChecks = items.size,
            passedChecks = passedCount,
            items = items
        )
    }
}
