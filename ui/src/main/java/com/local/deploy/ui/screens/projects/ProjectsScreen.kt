package com.local.deploy.ui.screens.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.deploy.model.Project
import com.local.deploy.model.ProjectStatus
import com.local.deploy.ui.components.ProjectCard
import com.local.deploy.ui.components.SummaryHeader
import com.local.deploy.ui.theme.PrimaryBlue

@Composable
fun ProjectsScreen(
    projects: List<Project>,
    healthScore: Int,
    onProjectClick: (String) -> Unit,
    onStartProject: (String) -> Unit,
    onStopProject: (String) -> Unit,
    onRestartProject: (String) -> Unit,
    onOpenWeb: (Project) -> Unit,
    onViewLogs: (String) -> Unit,
    onAddProjectClick: () -> Unit,
    onHealthClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val runningCount = projects.count { it.status == ProjectStatus.RUNNING }
    val totalRam = projects.sumOf { it.memoryRssBytes }
    val totalCpu = projects.sumOf { it.cpuPercent }
    val ramFormatted = String.format("%.1f MB", totalRam / (1024.0 * 1024.0))

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddProjectClick,
                containerColor = PrimaryBlue,
                contentColor = androidx.compose.ui.graphics.Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Project")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Summary Header
            SummaryHeader(
                runningCount = runningCount,
                totalCount = projects.size,
                totalRamFormatted = ramFormatted,
                totalCpuPercent = totalCpu,
                healthScore = healthScore,
                onHealthClick = onHealthClick
            )

            if (projects.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Empty",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Projects Yet",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Import a Discord bot, PHP web, or static site to get started.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onAddProjectClick,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Import First Project")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onProjectClick(project.id) },
                            onStart = { onStartProject(project.id) },
                            onStop = { onStopProject(project.id) },
                            onRestart = { onRestartProject(project.id) },
                            onOpenWeb = { onOpenWeb(project) },
                            onViewLogs = { onViewLogs(project.id) },
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(80.dp)) // padding for FAB
                    }
                }
            }
        }
    }
}
