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
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.CustomerEntity
import com.sologix.attendance.data.local.entity.ExpenseEntity
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.local.entity.TaskEntity
import com.sologix.attendance.data.local.entity.VisitEntity
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
                        MainScreen()
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
fun MainScreen(viewModel: SyncViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Queue & Attendance", "Tasks", "Customers", "Visits", "Expenses")

    val queueItems by viewModel.queueItems.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val latestAttendance by viewModel.latestAttendance.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val uiError by viewModel.uiError.collectAsState()

    val tasks by viewModel.tasks.collectAsState()
    val customers by viewModel.customers.collectAsState()
    val visits by viewModel.visits.collectAsState()
    val expenses by viewModel.expenses.collectAsState()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Sologix Field & Attendance") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    actions = {
                        TextButton(onClick = { viewModel.refreshHydration() }, enabled = !isRefreshing) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Hydrate")
                            }
                        }
                        IconButton(onClick = { viewModel.triggerManualRetry() }) {
                            Text("Sync")
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Global Sync Status Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (pendingCount == 0) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (pendingCount == 0) "All Queues Synced" else "$pendingCount Mutations Queued",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(onClick = { viewModel.triggerManualRetry() }) {
                        Text("Sync Now")
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

            Spacer(modifier = Modifier.height(12.dp))

            when (selectedTab) {
                0 -> QueueAndAttendanceView(
                    latestAttendance = latestAttendance,
                    queueItems = queueItems,
                    onCheckIn = { viewModel.simulateCheckIn() },
                    onCheckOut = { viewModel.simulateCheckOut() },
                    onRetryItem = { viewModel.retryItem(it) }
                )
                1 -> TasksView(
                    tasks = tasks,
                    onCreateTask = { title, desc, prio -> viewModel.createTask(title, desc, priority = prio) }
                )
                2 -> CustomersView(
                    customers = customers,
                    onCreateCustomer = { name, phone, addr -> viewModel.createCustomer(name, phone, addr) }
                )
                3 -> VisitsView(
                    visits = visits,
                    customers = customers,
                    onCheckInVisit = { custId -> viewModel.checkInVisit(custId) },
                    onCompleteVisit = { vId, outcome -> viewModel.completeVisit(vId, outcome) }
                )
                4 -> ExpensesView(
                    expenses = expenses,
                    onCreateExpense = { amt, cat -> viewModel.createExpense(amt, cat) }
                )
            }
        }
    }
}

@Composable
fun QueueAndAttendanceView(
    latestAttendance: AttendanceEntity?,
    queueItems: List<SyncQueueEntity>,
    onCheckIn: () -> Unit,
    onCheckOut: () -> Unit,
    onRetryItem: (String) -> Unit
) {
    Column {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Shift Status: " + if (latestAttendance == null) "Not Checked In"
                    else if (latestAttendance.checkOutAt == null) "Checked In (${latestAttendance.syncState})"
                    else "Checked Out (${latestAttendance.syncState})",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCheckIn,
                        modifier = Modifier.weight(1f),
                        enabled = latestAttendance == null || latestAttendance.checkOutAt != null
                    ) {
                        Text("Check-In")
                    }
                    Button(
                        onClick = onCheckOut,
                        modifier = Modifier.weight(1f),
                        enabled = latestAttendance != null && latestAttendance.checkOutAt == null
                    ) {
                        Text("Check-Out")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Sync Queue:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        if (queueItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Queue is empty. Offline mutations appear here.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(queueItems, key = { it.operationId }) { item ->
                    QueueItemRow(item = item, onRetry = { onRetryItem(item.operationId) })
                }
            }
        }
    }
}

@Composable
fun TasksView(
    tasks: List<TaskEntity>,
    onCreateTask: (String, String?, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    Column {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Create Offline Task", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (title.isNotBlank()) {
                            onCreateTask(title, description.ifBlank { null }, "HIGH")
                            title = ""
                            description = ""
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                    enabled = title.isNotBlank()
                ) {
                    Text("Save Task")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Task List (${tasks.size}):", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium)
                            task.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("Status: ${task.status} | Priority: ${task.priority}", style = MaterialTheme.typography.labelSmall)
                        }
                        SyncBadge(syncState = task.syncState)
                    }
                }
            }
        }
    }
}

@Composable
fun CustomersView(
    customers: List<CustomerEntity>,
    onCreateCustomer: (String, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    Column {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Create Offline Customer", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Customer Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            onCreateCustomer(name, phone.ifBlank { null }, address.ifBlank { null })
                            name = ""
                            phone = ""
                            address = ""
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                    enabled = name.isNotBlank()
                ) {
                    Text("Save Customer")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Customer List (${customers.size}):", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(customers, key = { it.id }) { customer ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(customer.name, style = MaterialTheme.typography.titleMedium)
                            customer.phone?.let { Text("Phone: $it", style = MaterialTheme.typography.bodySmall) }
                            customer.address?.let { Text("Address: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        SyncBadge(syncState = customer.syncState)
                    }
                }
            }
        }
    }
}

@Composable
fun VisitsView(
    visits: List<VisitEntity>,
    customers: List<CustomerEntity>,
    onCheckInVisit: (String) -> Unit,
    onCompleteVisit: (String, String) -> Unit
) {
    Column {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Start Visit for Customer", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                if (customers.isEmpty()) {
                    Text("No customers found. Create a customer first.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onCheckInVisit(customers.first().id) }) {
                            Text("Check-In Visit (${customers.first().name})")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Visit Log (${visits.size}):", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visits, key = { it.id }) { visit ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Visit: ${visit.status}", style = MaterialTheme.typography.titleMedium)
                            SyncBadge(syncState = visit.syncState)
                        }
                        Text("Customer: ${visit.customerId}", style = MaterialTheme.typography.bodySmall)
                        visit.meetingOutcome?.let { Text("Outcome: $it", style = MaterialTheme.typography.bodySmall) }

                        if (visit.status != "COMPLETED") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { onCompleteVisit(visit.id, "Meeting concluded successfully") },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Complete Visit")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExpensesView(
    expenses: List<ExpenseEntity>,
    onCreateExpense: (Double, String) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("TRAVEL") }

    Column {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Create Offline Expense", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (INR)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (e.g. TRAVEL, MEALS)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val amt = amountText.toDoubleOrNull()
                        if (amt != null && amt > 0) {
                            onCreateExpense(amt, category)
                            amountText = ""
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                    enabled = amountText.toDoubleOrNull() != null
                ) {
                    Text("Save Expense")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Expense Log (${expenses.size}):", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(expenses, key = { it.id }) { expense ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("INR %.2f".format(expense.amount), style = MaterialTheme.typography.titleMedium)
                            Text("Category: ${expense.category}", style = MaterialTheme.typography.bodySmall)
                            Text("Status: ${expense.status}", style = MaterialTheme.typography.labelSmall)
                        }
                        SyncBadge(syncState = expense.syncState)
                    }
                }
            }
        }
    }
}

@Composable
fun SyncBadge(syncState: SyncState) {
    val (bg, label) = when (syncState) {
        SyncState.SYNCED -> Color(0xFF2E7D32) to "SYNCED"
        SyncState.PENDING -> Color(0xFFF57C00) to "PENDING"
        SyncState.AWAITING_SERVER -> Color(0xFF1976D2) to "AWAITING"
        SyncState.FAILED -> Color(0xFFD32F2F) to "FAILED"
    }
    Surface(
        color = bg,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(4.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${item.entityType}: ${item.operationType}",
                    style = MaterialTheme.typography.titleSmall
                )
                Surface(
                    color = statusColor,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = item.status.name,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Op: ${item.operationId}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            item.lastError?.let { error ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (item.status == QueueStatus.DEAD) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Force Retry")
                }
            }
        }
    }
}
