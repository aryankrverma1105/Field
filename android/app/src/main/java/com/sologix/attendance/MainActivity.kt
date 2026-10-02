package com.sologix.attendance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
                    PermissionGuard {
                        SyncStatusScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionGuard(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    content()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(viewModel: SyncViewModel = viewModel()) {
    val queueItems by viewModel.queueItems.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val latestAttendance by viewModel.latestAttendance.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val uiError by viewModel.uiError.collectAsState()

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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (pendingCount == 0) "All Systems Synced" else "$pendingCount Items Awaiting Sync",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "StateFlow automatically updated via Room Flow",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.refreshHydration() },
                            enabled = !isRefreshing
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Hydrate")
                            }
                        }
                        Button(onClick = { viewModel.triggerManualRetry() }) {
                            Text("Sync Now")
                        }
                    }
                }
            }

            uiError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Debug Simulation Controls",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.simulateCheckIn() },
                                modifier = Modifier.weight(1f),
                                enabled = latestAttendance == null || latestAttendance?.checkOutAt != null
                            ) {
                                Text("Check-In")
                            }
                            Button(
                                onClick = { viewModel.simulateCheckOut() },
                                modifier = Modifier.weight(1f),
                                enabled = latestAttendance != null && latestAttendance?.checkOutAt == null
                            ) {
                                Text("Check-Out")
                            }
                        }
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
                        QueueItemRow(
                            item = item,
                            onRetry = { viewModel.retryItem(item.operationId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QueueItemRow(item: SyncQueueEntity, onRetry: () -> Unit) {
    val statusColor = when (item.status) {
        QueueStatus.SYNCED -> Color(0xFF2E7D32)
        QueueStatus.PENDING -> Color(0xFFF57C00)
        QueueStatus.IN_PROGRESS -> Color(0xFF1976D2)
        QueueStatus.FAILED -> Color(0xFFD32F2F)
        QueueStatus.DEAD -> Color(0xFF616161)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
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
            if (!item.lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Error: ${item.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (item.status == QueueStatus.FAILED || item.status == QueueStatus.DEAD) {
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Retry")
                }
            }
        }
    }
}
