package com.alphanew.deploy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.local.deploy.database.JsonFileProjectRepository
import com.local.deploy.database.ProjectRepository
import com.local.deploy.packages.PackageManager
import com.alphanew.deploy.service.SupervisorService
import com.local.deploy.supervisor.ProcessSupervisor
import java.io.File

class LocalApplication : Application() {

    companion object {
        const val CHANNEL_ID = "alpha_new_service_channel"
        private const val TAG = "LocalApplication"
        lateinit var instance: LocalApplication
            private set
    }

    lateinit var projectRepository: ProjectRepository
        private set
    lateinit var processSupervisor: ProcessSupervisor
        private set
    lateinit var packageManager: PackageManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            createNotificationChannel()

            val dbDir = File(filesDir, "db")
            projectRepository = JsonFileProjectRepository(dbDir)
            packageManager = PackageManager(filesDir)
            processSupervisor = ProcessSupervisor(filesDir)
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)
        }
    }

    /**
     * Starts the supervisor foreground service safely from an active Activity context.
     * Prevents ForegroundServiceStartNotAllowedException on Android 12+.
     */
    fun startSupervisorServiceSafely() {
        try {
            val serviceIntent = Intent(this, SupervisorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start supervisor service: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ALPHA NEW Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ALPHA NEW background process supervisor"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }
}
