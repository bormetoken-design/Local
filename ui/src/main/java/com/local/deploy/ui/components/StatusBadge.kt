package com.local.deploy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.deploy.model.ProjectStatus
import com.local.deploy.ui.theme.StatusDegraded
import com.local.deploy.ui.theme.StatusFailed
import com.local.deploy.ui.theme.StatusRecovering
import com.local.deploy.ui.theme.StatusRunning
import com.local.deploy.ui.theme.StatusStarting
import com.local.deploy.ui.theme.StatusStopped

@Composable
fun StatusBadge(
    status: ProjectStatus,
    modifier: Modifier = Modifier
) {
    val (color, icon) = when (status) {
        ProjectStatus.RUNNING -> StatusRunning to Icons.Default.CheckCircle
        ProjectStatus.STARTING -> StatusStarting to Icons.Default.HourglassEmpty
        ProjectStatus.DEGRADED -> StatusDegraded to Icons.Default.Warning
        ProjectStatus.RECOVERING -> StatusRecovering to Icons.Default.Refresh
        ProjectStatus.FAILED -> StatusFailed to Icons.Default.Error
        ProjectStatus.STOPPED -> StatusStopped to Icons.Default.Stop
    }

    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = status.label,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = status.label,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
