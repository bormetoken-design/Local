package com.local.deploy.ui.screens.runtimes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.local.deploy.model.RuntimePackage
import com.local.deploy.model.RuntimeType
import com.local.deploy.ui.theme.PrimaryBlue
import com.local.deploy.ui.theme.StatusFailed
import com.local.deploy.ui.theme.StatusRunning

@Composable
fun RuntimesScreen(
    packages: List<RuntimePackage>,
    deviceAbi: String,
    installingPackageId: String? = null,
    installProgressPercent: Int = 0,
    installStage: String? = null,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onInstallPackage: (RuntimePackage) -> Unit,
    onUninstallPackage: (RuntimePackage) -> Unit,
    onUpdatePackage: (RuntimePackage) -> Unit,
    onInstallFromFile: () -> Unit = {},
    onRefreshIndex: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Runtime Packages",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Device Architecture: $deviceAbi",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefreshIndex) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Packages")
                }
                Spacer(modifier = Modifier.width(4.dp))
                OutlinedButton(
                    onClick = onInstallFromFile,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Install from File", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(packages, key = { it.id }) { pkg ->
                val isCurrentlyInstalling = installingPackageId == pkg.id

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = pkg.name,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "v${pkg.version} • ${pkg.sizeFormatted}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }

                            if (pkg.isInstalled) {
                                val label = if (!pkg.installedVersion.isNullOrBlank()) {
                                    "Installed (${pkg.installedVersion})"
                                } else {
                                    "Installed"
                                }
                                Text(
                                    text = label,
                                    color = StatusRunning,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (isCurrentlyInstalling) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { installProgressPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = PrimaryBlue
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val displayStage = if (!installStage.isNullOrBlank()) {
                                "$installStage ($installProgressPercent%)"
                            } else {
                                "Installing: $installProgressPercent%"
                            }
                            Text(
                                text = displayStage,
                                fontSize = 12.sp,
                                color = PrimaryBlue
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (pkg.isInstalled) {
                                if (pkg.isUpdateAvailable) {
                                    Button(
                                        onClick = { onUpdatePackage(pkg) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                    ) {
                                        Icon(Icons.Default.Upgrade, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Update", fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                OutlinedButton(
                                    onClick = { onUninstallPackage(pkg) },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = StatusFailed, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Uninstall", fontSize = 12.sp, color = StatusFailed)
                                }
                            } else {
                                Button(
                                    onClick = { onInstallPackage(pkg) },
                                    enabled = !isCurrentlyInstalling,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Install", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Dialog(onDismissRequest = onDismissError) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Installation Error",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = StatusFailed
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorMessage,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onDismissError,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }
}
