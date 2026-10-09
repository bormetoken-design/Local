package com.local.deploy.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.local.deploy.ui.screens.details.ProjectDetailScreen
import com.local.deploy.ui.screens.health.SystemHealthScreen
import com.local.deploy.ui.screens.onboarding.OnboardingScreen
import com.local.deploy.ui.screens.projects.ProjectsScreen
import com.local.deploy.ui.screens.runtimes.RuntimesScreen
import com.local.deploy.ui.screens.settings.SettingsScreen
import com.local.deploy.ui.screens.wizard.AddProjectWizardScreen
import com.local.deploy.ui.theme.LocalDeployTheme
import com.local.deploy.ui.viewmodel.MainViewModel

enum class MainTab(val label: String, val icon: ImageVector) {
    PROJECTS("Projects", Icons.Default.Layers),
    RUNTIMES("Runtimes", Icons.Default.Widgets),
    HEALTH("Health", Icons.Default.Favorite),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun MainApp(
    viewModel: MainViewModel,
    appVersion: String = "1.0.1",
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(MainTab.PROJECTS) }
    var isWizardOpen by remember { mutableStateOf(false) }

    LocalDeployTheme {
        if (!state.isOnboardingCompleted) {
            OnboardingScreen(
                onRequestNotificationPermission = {},
                onRequestBatteryOptimizationExemption = {},
                onCompleteOnboarding = {}
            )
        } else if (state.selectedProjectId != null) {
            val selected = state.projects.firstOrNull { it.id == state.selectedProjectId }
            if (selected != null) {
                ProjectDetailScreen(
                    project = selected,
                    logs = state.activeLogs,
                    onBack = { viewModel.selectProject(null) },
                    onStart = { viewModel.startProject(selected.id) },
                    onStop = { viewModel.stopProject(selected.id) },
                    onRestart = { viewModel.restartProject(selected.id) },
                    onOpenWeb = {},
                    onClearLogs = { viewModel.clearLogs(selected.id) },
                    onSaveEnv = { viewModel.saveEnv(selected.id, it) },
                    onDeleteProject = { viewModel.deleteProject(selected.id) }
                )
            }
        } else if (isWizardOpen) {
            AddProjectWizardScreen(
                onDismiss = { isWizardOpen = false },
                onPickFolder = {},
                onPickZip = {},
                onSelectTemplate = { viewModel.createProjectFromTemplate(it) },
                detectionResult = state.activeDetectionResult,
                onCreateAndStart = { name, cmd, port, auto, env ->
                    viewModel.finalizeCreateProject(name, cmd, port, auto, env)
                    isWizardOpen = false
                }
            )
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        MainTab.values().forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            ) { paddingValues ->
                when (selectedTab) {
                    MainTab.PROJECTS -> ProjectsScreen(
                        projects = state.projects,
                        healthScore = state.healthReport.score,
                        onProjectClick = { viewModel.selectProject(it) },
                        onStartProject = { viewModel.startProject(it) },
                        onStopProject = { viewModel.stopProject(it) },
                        onRestartProject = { viewModel.restartProject(it) },
                        onOpenWeb = {},
                        onViewLogs = { viewModel.selectProject(it) },
                        onAddProjectClick = { isWizardOpen = true },
                        onHealthClick = { selectedTab = MainTab.HEALTH },
                        modifier = Modifier.padding(paddingValues)
                    )

                    MainTab.RUNTIMES -> RuntimesScreen(
                        packages = state.runtimePackages,
                        deviceAbi = "aarch64",
                        installingPackageId = state.isInstallingPackageId,
                        installProgressPercent = state.installProgressPercent,
                        onInstallPackage = {},
                        onUninstallPackage = {},
                        onUpdatePackage = {},
                        modifier = Modifier.padding(paddingValues)
                    )

                    MainTab.HEALTH -> SystemHealthScreen(
                        report = state.healthReport,
                        onFixAction = {},
                        modifier = Modifier.padding(paddingValues)
                    )

                    MainTab.SETTINGS -> SettingsScreen(
                        discordWebhookUrl = state.discordWebhookUrl,
                        onSaveWebhookUrl = {},
                        isBiometricLockEnabled = state.isBiometricEnabled,
                        onToggleBiometric = {},
                        isAutoBackupEnabled = state.isAutoBackupEnabled,
                        onToggleAutoBackup = {},
                        appVersion = appVersion,
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }
        }
    }
}
