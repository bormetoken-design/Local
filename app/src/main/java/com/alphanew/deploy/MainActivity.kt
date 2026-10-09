package com.alphanew.deploy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.local.deploy.ui.navigation.MainApp
import com.local.deploy.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as LocalApplication

        val viewModelFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(
                    filesDir = app.filesDir,
                    projectRepository = app.projectRepository,
                    processSupervisor = app.processSupervisor,
                    packageManager = app.packageManager
                ) as T
            }
        }

        val viewModel = ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]

        setContent {
            MainApp(
                viewModel = viewModel,
                appVersion = BuildConfig.VERSION_NAME
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // Safely start supervisor service now that the Activity is actively in foreground
        (application as? LocalApplication)?.startSupervisorServiceSafely()
    }
}
