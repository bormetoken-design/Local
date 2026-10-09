package com.local.deploy.ui.screens.deploy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.local.deploy.ui.theme.PrimaryBlue

enum class DeployStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

data class DeployStepItem(
    val stepNumber: Int,
    val title: String,
    val description: String,
    val status: DeployStepStatus = DeployStepStatus.PENDING
)

data class AutoDeployState(
    val isVisible: Boolean = false,
    val isRunning: Boolean = false,
    val projectName: String = "",
    val steps: List<DeployStepItem> = emptyList(),
    val terminalLogs: List<String> = emptyList(),
    val deployedProjectId: String? = null,
    val deployedPort: Int? = null,
    val isSuccess: Boolean = false,
    val error: String? = null,
    val isDiscordBotWithoutToken: Boolean = false
)

@Composable
fun RailwayDeployDialog(
    state: AutoDeployState,
    onDismiss: () -> Unit,
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isVisible) return

    val listState = rememberLazyListState()

    // Auto-scroll terminal logs to bottom
    LaunchedEffect(state.terminalLogs.size) {
        if (state.terminalLogs.isNotEmpty()) {
            listState.animateScrollToItem(state.terminalLogs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = {
            if (!state.isRunning) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !state.isRunning,
            dismissOnClickOutside = !state.isRunning,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            modifier = modifier
                .fillMaxWidth(0.95f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = PrimaryBlue.copy(alpha = 0.15f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Railway Deploy",
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Railway Auto-Deploy",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (state.isSuccess) Color(0xFF22C55E).copy(alpha = 0.2f) else PrimaryBlue.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = if (state.isSuccess) "LIVE" else "1-CLICK",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.isSuccess) Color(0xFF22C55E) else PrimaryBlue,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = state.projectName.ifBlank { "Deploying project..." },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    if (!state.isRunning) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Pipeline Step Indicators
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    state.steps.forEach { step ->
                        StepRow(step = step)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Terminal / Build Output Card
                Text(
                    text = "DEPLOY LOGS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A))
                        .padding(10.dp)
                ) {
                    if (state.terminalLogs.isEmpty()) {
                        Text(
                            text = "Initializing pipeline...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(state.terminalLogs) { logLine ->
                                Text(
                                    text = logLine,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = when {
                                        logLine.contains("[OK]") || logLine.contains("SUCCESS") -> Color(0xFF4ADE80)
                                        logLine.contains("[ERROR]") || logLine.contains("FAILED") -> Color(0xFFF87171)
                                        logLine.contains("[WARN]") -> Color(0xFFFBBF24)
                                        logLine.contains("[PORT]") || logLine.contains("[NET]") || logLine.contains("[PROXY]") -> Color(0xFF38BDF8)
                                        else -> Color(0xFFE2E8F0)
                                    },
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }

                // Discord Token Alert Notice
                AnimatedVisibility(visible = state.isDiscordBotWithoutToken) {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF59E0B).copy(alpha = 0.15f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = "Token Needed",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Bot detected! If required, add DISCORD_TOKEN in Project Settings.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Bottom Action Buttons
                if (state.isRunning) {
                    Button(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = PrimaryBlue
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Deploying Project...")
                    }
                } else if (state.isSuccess && state.deployedProjectId != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Done")
                        }
                        Button(
                            onClick = {
                                onOpenProject(state.deployedProjectId)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Live Dashboard")
                        }
                    }
                } else {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: DeployStepItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (step.status) {
            DeployStepStatus.RUNNING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = PrimaryBlue
                )
            }
            DeployStepStatus.COMPLETED -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Completed",
                    tint = Color(0xFF22C55E),
                    modifier = Modifier.size(18.dp)
                )
            }
            DeployStepStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Failed",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(18.dp)
                )
            }
            DeployStepStatus.PENDING -> {
                Icon(
                    imageVector = Icons.Default.HourglassEmpty,
                    contentDescription = "Pending",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column {
            Text(
                text = "${step.stepNumber}. ${step.title}",
                fontSize = 13.sp,
                fontWeight = if (step.status == DeployStepStatus.RUNNING) FontWeight.Bold else FontWeight.Medium,
                color = when (step.status) {
                    DeployStepStatus.RUNNING -> PrimaryBlue
                    DeployStepStatus.COMPLETED -> MaterialTheme.colorScheme.onSurface
                    DeployStepStatus.FAILED -> Color(0xFFEF4444)
                    DeployStepStatus.PENDING -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                }
            )
            Text(
                text = step.description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}
