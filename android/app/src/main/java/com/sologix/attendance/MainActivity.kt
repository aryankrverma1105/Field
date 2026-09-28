package com.sologix.attendance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.ui.SyncViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SyncStatusScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(viewModel: SyncViewModel = viewModel()) {
    val queueItems by viewModel.queueItems.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sologix Offline Sync Foundation") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (pendingCount == 0) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (pendingCount == 0) "All Systems Synced" else "$pendingCount Items Awaiting Sync",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "StateFlow automatically updated via Room Flow",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(onClick = { viewModel.triggerManualRetry() }) {
                        Text("Sync Now")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Sync Queue Records:", style = MaterialTheme.typography.titleSmall)

            Spacer(modifier = Modifier.height(8.dp))

            if (queueItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Queue is empty. Offline mutations will appear here.", color = Color.Gray)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(queueItems, key = { it.operationId }) { item ->
                        QueueItemRow(item)
                    }
                }
            }
        }
    }
}

@Composable
fun QueueItemRow(item: SyncQueueEntity) {
    val statusColor = when (item.status) {
        QueueStatus.SYNCED -> Color(0xFF2E7D32)
        QueueStatus.PENDING -> Color(0xFFF57C00)
        QueueStatus.IN_PROGRESS -> Color(0xFF1976D2)
        QueueStatus.FAILED -> Color(0xFFD32F2F)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${item.operationType} (${item.entityType})",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = item.status.name,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "OpId: ${item.operationId}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            if (item.attemptCount > 0) {
                Text(
                    text = "Attempts: ${item.attemptCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Red
                )
            }
        }
    }
}
