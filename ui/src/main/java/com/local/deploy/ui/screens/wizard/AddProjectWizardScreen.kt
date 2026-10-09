package com.local.deploy.ui.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.deploy.model.RuntimeType
import com.local.deploy.projects.detector.DetectionResult
import com.local.deploy.projects.template.ProjectTemplate
import com.local.deploy.projects.template.ProjectTemplates
import com.local.deploy.ui.theme.PrimaryBlue

enum class WizardSourceType {
    FOLDER,
    ZIP,
    TEMPLATE,
    GIT
}

@Composable
fun AddProjectWizardScreen(
    onDismiss: () -> Unit,
    onPickFolder: () -> Unit,
    onPickZip: () -> Unit,
    onSelectTemplate: (ProjectTemplate) -> Unit,
    detectionResult: DetectionResult?,
    onCreateAndStart: (name: String, command: String, port: Int, autostart: Boolean, env: Map<String, String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentStep by remember { mutableStateOf(1) }
    var selectedSource by remember { mutableStateOf<WizardSourceType?>(null) }

    // Form states
    var projectName by remember(detectionResult) { mutableStateOf(detectionResult?.suggestedName ?: "my-project") }
    var startCommand by remember(detectionResult) { mutableStateOf(detectionResult?.suggestedCommand ?: "") }
    var projectPort by remember(detectionResult) { mutableStateOf((detectionResult?.suggestedPort ?: 8080).toString()) }
    var autostart by remember { mutableStateOf(false) }

    val envValues = remember(detectionResult) {
        mutableStateMapOf<String, String>().apply {
            detectionResult?.detectedEnvVars?.forEach { put(it.key, it.defaultValue) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (currentStep > 1) currentStep-- else onDismiss() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Add Project (Step $currentStep/4)",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Stepper Progress
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { currentStep / 4f },
            modifier = Modifier.fillMaxWidth(),
            color = PrimaryBlue
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Content per step
        when (currentStep) {
            1 -> {
                // Step 1: Select Source
                Text("Select Project Source", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(14.dp))

                SourceOptionCard(
                    title = "Choose Local Folder",
                    subtitle = "Select an existing project folder via Storage Access Framework",
                    icon = Icons.Default.Folder,
                    onClick = {
                        selectedSource = WizardSourceType.FOLDER
                        onPickFolder()
                        currentStep = 2
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                SourceOptionCard(
                    title = "Import ZIP File",
                    subtitle = "Safely unpacks archive with path traversal protection",
                    icon = Icons.Default.Archive,
                    onClick = {
                        selectedSource = WizardSourceType.ZIP
                        onPickZip()
                        currentStep = 2
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                SourceOptionCard(
                    title = "Start from Starter Template",
                    subtitle = "Discord.js, Discord.py, PHP Web, or Static site",
                    icon = Icons.Default.Code,
                    onClick = {
                        selectedSource = WizardSourceType.TEMPLATE
                        currentStep = 2
                    }
                )
            }

            2 -> {
                // Step 2: Inspection & Verification
                if (selectedSource == WizardSourceType.TEMPLATE && detectionResult == null) {
                    Text("Choose a Starter Template", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ProjectTemplates.allTemplates) { template ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectTemplate(template)
                                        projectName = template.id
                                        startCommand = template.startCommand
                                        projectPort = template.defaultPort.toString()
                                        currentStep = 3
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(template.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(template.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                } else {
                    Text("Inspection Results", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Detected Type: ${detectionResult?.description ?: "Custom"}", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = projectName,
                                onValueChange = { projectName = it },
                                label = { Text("Project Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = startCommand,
                                onValueChange = { startCommand = it },
                                label = { Text("Start Command") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = projectPort,
                                onValueChange = { projectPort = it },
                                label = { Text("Port") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { currentStep = 3 },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Text("Next: Dependencies & Config")
                    }
                }
            }

            3 -> {
                // Step 3: Dependencies & Environment Variables
                Text("Environment Variables (.env)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))

                val envVars = detectionResult?.detectedEnvVars ?: emptyList()
                if (envVars.isEmpty()) {
                    Text("No environment variables required by this project.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(envVars) { envVar ->
                            var isVisible by remember { mutableStateOf(!envVar.isSecret) }
                            OutlinedTextField(
                                value = envValues[envVar.key] ?: "",
                                onValueChange = { envValues[envVar.key] = it },
                                label = { Text(envVar.key) },
                                visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    if (envVar.isSecret) {
                                        IconButton(onClick = { isVisible = !isVisible }) {
                                            Icon(
                                                imageVector = if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = null
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { currentStep = 4 },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text("Next: Launch Settings")
                }
            }

            4 -> {
                // Step 4: Final Launch Settings
                Text("Ready to Deploy", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(14.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Service: $projectName", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Command: $startCommand", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Text("Port: $projectPort", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Autostart on Boot", fontWeight = FontWeight.SemiBold)
                                Text("Start service after device reboot", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Switch(checked = autostart, onCheckedChange = { autostart = it })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        onCreateAndStart(
                            projectName,
                            startCommand,
                            projectPort.toIntOrNull() ?: 8080,
                            autostart,
                            envValues
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create & Start Service")
                }
            }
        }
    }
}

@Composable
private fun SourceOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PrimaryBlue,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}
