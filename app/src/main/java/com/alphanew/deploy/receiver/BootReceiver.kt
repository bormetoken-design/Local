package com.alphanew.deploy.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.alphanew.deploy.LocalApplication
import com.alphanew.deploy.service.SupervisorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val serviceIntent = Intent(context, SupervisorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            val app = context.applicationContext as? LocalApplication ?: return
            CoroutineScope(Dispatchers.IO).launch {
                delay(3000L)
                val autostartProjects = app.projectRepository.getAutostartProjects()
                autostartProjects.forEach { proj ->
                    app.processSupervisor.startProject(proj.id)
                    delay(2000L)
                }
            }
        }
    }
}
