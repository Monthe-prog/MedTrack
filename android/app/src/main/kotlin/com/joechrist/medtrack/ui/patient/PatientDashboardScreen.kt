package com.joechrist.medtrack.ui.patient

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.work.WorkManager
import com.joechrist.medtrack.data.local.entity.IntakeLogEntity
import com.joechrist.medtrack.data.local.entity.PrescriptionEntity
import com.joechrist.medtrack.data.repository.SyncRepository
import com.joechrist.medtrack.data.session.SessionManager
import com.joechrist.medtrack.worker.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// =============================================================================
// MedTrack – PatientViewModel
// =============================================================================

@HiltViewModel
class PatientViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val session: SessionManager,
    private val workManager: WorkManager
) : ViewModel() {

    private val _patientId = MutableStateFlow("")
    val todayLogs: StateFlow<List<IntakeLogEntity>> = _patientId
        .filter { it.isNotBlank() }
        .flatMapLatest { syncRepository.observeTodayLogs(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _events = MutableSharedFlow<PatientEvent>()
    val events: SharedFlow<PatientEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val cached = session.getSession() ?: return@launch
            _patientId.value = cached.firebaseUid
            // Auto-miss any overdue doses on app open
            syncRepository.autoMissExpired()
            // Kick off a one-shot sync
            SyncWorker.enqueueOneShot(workManager)
            // Start periodic background sync
            SyncWorker.enqueuePeriodic(workManager)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            SyncWorker.enqueueOneShot(workManager)
            // Brief delay to let WorkManager kick off
            kotlinx.coroutines.delay(1500)
            _isRefreshing.value = false
        }
    }

    fun markTaken(logId: String) {
        viewModelScope.launch {
            syncRepository.markTaken(logId, notes = null)
            _events.emit(PatientEvent.ShowSnackbar("Dose marked as taken ✓"))
        }
    }

    fun markMissed(logId: String) {
        viewModelScope.launch {
            syncRepository.markMissed(logId)
            _events.emit(PatientEvent.ShowSnackbar("Dose marked as missed"))
        }
    }
}

sealed class PatientEvent {
    data class ShowSnackbar(val message: String) : PatientEvent()
}

// =============================================================================
// MedTrack – Patient Dashboard Screen
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDashboardScreen(
    navController: NavHostController,
    viewModel: PatientViewModel = hiltViewModel()
) {
    val logs         by viewModel.todayLogs.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val snackbarState = remember { SnackbarHostState() }
    val context       = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is PatientEvent.ShowSnackbar -> snackbarState.showSnackbar(event.message)
            }
        }
    }

    // Group logs by HH:mm label for the timeline
    val grouped = remember(logs) {
        logs.groupBy { log ->
            Instant.ofEpochMilli(log.scheduledTimeMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        }.entries.sortedBy { it.key }
    }

    val taken  = logs.count { it.status == "TAKEN" }
    val total  = logs.size
    val missed = logs.count { it.status == "MISSED" }

    Scaffold(snackbarHost = { SnackbarHost(snackbarState) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.padding(padding)
        ) {

            // ── Header ────────────────────────────────────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF085041), Color(0xFF1DB98A))
                            )
                        )
                        .padding(24.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "Today's Schedule",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        // Compliance ring
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ComplianceRing(taken = taken, total = total)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (total == 0) "No doses today"
                                    else "$taken of $total doses taken",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (missed > 0) {
                                    Text(
                                        "$missed missed",
                                        color = Color(0xFFF4C0D1),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Pull-to-refresh hint ──────────────────────────────────────────
            item {
                if (isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // ── Permission nudge for exact alarms (API 31+) ───────────────────
            item {
                ExactAlarmPermissionBanner(context = context)
            }

            // ── Empty state ───────────────────────────────────────────────────
            if (total == 0) {
                item {
                    Column(
                        modifier            = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.CheckCircleOutline, null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.secondary)
                        Text("All clear for today!", style = MaterialTheme.typography.titleMedium)
                        Text("No doses scheduled", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@LazyColumn
            }

            // ── Timeline ──────────────────────────────────────────────────────
            grouped.forEach { (timeLabel, dosesAtTime) ->
                item(key = "header_$timeLabel") {
                    TimelineHeader(timeLabel)
                }
                items(dosesAtTime, key = { it.id }) { log ->
                    DoseCard(
                        log      = log,
                        onTaken  = { viewModel.markTaken(log.id) },
                        onMissed = { viewModel.markMissed(log.id) }
                    )
                }
            }
        }
    }
}

// ── Compliance ring (Canvas-drawn arc) ────────────────────────────────────────

@Composable
private fun ComplianceRing(taken: Int, total: Int) {
    val fraction = if (total > 0) taken.toFloat() / total else 0f
    Box(
        modifier         = Modifier.size(72.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(72.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 8.dp.toPx(),
                cap   = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // Background arc
            drawArc(
                color      = Color.White.copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter  = false,
                style      = stroke
            )
            // Progress arc
            if (fraction > 0f) {
                drawArc(
                    color      = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter  = false,
                    style      = stroke
                )
            }
        }
        Text(
            "${(fraction * 100).toInt()}%",
            color      = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize   = 14.sp
        )
    }
}

// ── Timeline header ───────────────────────────────────────────────────────────

@Composable
private fun TimelineHeader(time: String) {
    Row(
        modifier              = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            time,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.primary
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        )
    }
}

// ── Dose card ─────────────────────────────────────────────────────────────────

@Composable
private fun DoseCard(
    log: IntakeLogEntity,
    onTaken: () -> Unit,
    onMissed: () -> Unit
) {
    val isPending   = log.status == "PENDING"
    val isTaken     = log.status == "TAKEN"
    val isMissed    = log.status == "MISSED"

    val borderColor = when {
        isTaken  -> Color(0xFF1D9E75)
        isMissed -> Color(0xFFA32D2D)
        else     -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    val leftBarColor = when {
        isTaken  -> Color(0xFF1D9E75)
        isMissed -> Color(0xFFA32D2D)
        else     -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape     = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Status bar
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(leftBarColor)
            )

            Row(
                modifier              = Modifier
                    .weight(1f)
                    .padding(14.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Medication", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Prescription ${log.prescriptionId.take(8)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        when {
                            isTaken  -> "✓ Taken"
                            isMissed -> "✗ Missed"
                            else     -> "Pending"
                        },
                        style      = MaterialTheme.typography.labelSmall,
                        color      = leftBarColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Action buttons — only for PENDING doses
                if (isPending) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick  = onTaken,
                            colors   = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFF1D9E75).copy(alpha = 0.12f)
                            ),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp),
                                tint = Color(0xFF1D9E75))
                            Spacer(Modifier.width(4.dp))
                            Text("Taken", color = Color(0xFF1D9E75),
                                style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick  = onMissed,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Miss", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ── Exact alarm permission banner (API 31+) ───────────────────────────────────

@Composable
private fun ExactAlarmPermissionBanner(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return

    val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
    if (alarmManager.canScheduleExactAlarms()) return   // permission granted — hide banner

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    ) {
        Row(
            modifier              = Modifier.padding(14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.AlarmOff, null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable exact alarms",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Text("Required for precise medication reminders",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f))
            }
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    (context as? Activity)?.startActivity(intent)
                }
            ) { Text("Enable") }
        }
    }
}
