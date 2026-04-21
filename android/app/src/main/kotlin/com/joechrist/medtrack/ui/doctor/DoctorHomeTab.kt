package com.joechrist.medtrack.ui.doctor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joechrist.medtrack.data.remote.dto.PrescriptionResponse

// =============================================================================
// MedTrack – Doctor Home Tab
// • Greeting header with stats cards
// • Recent prescriptions list with status chips + action menus
// • PDF download trigger
// =============================================================================

@Composable
fun DoctorHomeTab(
    viewModel: DoctorViewModel,
    onTabChange: (DoctorTab) -> Unit
) {
    val state    by viewModel.dashboardState.collectAsState()
    val uriHandler = LocalUriHandler.current

    // Collect PDF URL events here for URI opening
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is DoctorEvent.OpenUrl) {
                runCatching { uriHandler.openUri(event.url) }
            }
        }
    }

    if (state.isLoadingPatients) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {

        // ── Greeting header ───────────────────────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF0A2647), Color(0xFF0F3460)))
                    )
                    .padding(24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Good morning,",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Dr. ${state.doctorName.split(" ").lastOrNull() ?: state.doctorName}",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(16.dp))

                    // ── Stats row ──────────────────────────────────────────────
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatChip(
                            modifier = Modifier.weight(1f),
                            icon     = Icons.Default.People,
                            value    = "${state.patientCount}",
                            label    = "Patients",
                            tint     = Color(0xFF9FE1CB)
                        )
                        StatChip(
                            modifier = Modifier.weight(1f),
                            icon     = Icons.Default.Assignment,
                            value    = "${state.activeRxCount}",
                            label    = "Active Rx",
                            tint     = Color(0xFFFAC775)
                        )
                        StatChip(
                            modifier = Modifier.weight(1f),
                            icon     = Icons.Default.CheckCircle,
                            value    = "${state.prescriptions.count { it.status == "COMPLETED" }}",
                            label    = "Completed",
                            tint     = Color(0xFF85B7EB)
                        )
                    }
                }
            }
        }

        // ── Quick action ──────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Recent Prescriptions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = { onTabChange(DoctorTab.NEW_RX) }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New Rx")
                }
            }
        }

        // ── Empty state ───────────────────────────────────────────────────────
        if (state.prescriptions.isEmpty()) {
            item {
                EmptyState(
                    icon    = Icons.Default.Assignment,
                    title   = "No prescriptions yet",
                    message = "Create your first prescription using the New Rx tab",
                    onAction = { onTabChange(DoctorTab.NEW_RX) },
                    actionLabel = "Create prescription"
                )
            }
        }

        // ── Prescription cards ────────────────────────────────────────────────
        itemsIndexed(
            items    = state.prescriptions,
            key      = { _, rx -> rx.id }
        ) { _, rx ->
            PrescriptionCard(
                rx         = rx,
                onStatusChange = { newStatus -> viewModel.updatePrescriptionStatus(rx.id, newStatus) },
                onDownloadPdf  = { viewModel.fetchPdfUrl(rx.id) }
            )
        }
    }
}

// ── Prescription card ─────────────────────────────────────────────────────────

@Composable
private fun PrescriptionCard(
    rx: PrescriptionResponse,
    onStatusChange: (String) -> Unit,
    onDownloadPdf: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val statusColor = when (rx.status) {
        "ACTIVE"    -> Color(0xFF1D9E75)
        "PAUSED"    -> Color(0xFFBA7517)
        "COMPLETED" -> Color(0xFF378ADD)
        "CANCELLED" -> Color(0xFFA32D2D)
        else        -> Color.Gray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header row: drug name + status + menu ─────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        rx.genericName,
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!rx.brandName.isNullOrBlank()) {
                        Text(
                            rx.brandName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    // Status chip
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            rx.status,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style  = MaterialTheme.typography.labelSmall,
                            color  = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Actions menu
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "Options",
                                modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (rx.status == "ACTIVE") {
                                DropdownMenuItem(
                                    text = { Text("Pause") },
                                    leadingIcon = { Icon(Icons.Default.Pause, null) },
                                    onClick = { menuExpanded = false; onStatusChange("PAUSED") }
                                )
                                DropdownMenuItem(
                                    text = { Text("Mark completed") },
                                    leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                                    onClick = { menuExpanded = false; onStatusChange("COMPLETED") }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cancel") },
                                    leadingIcon = { Icon(Icons.Default.Cancel, null) },
                                    onClick = { menuExpanded = false; onStatusChange("CANCELLED") }
                                )
                            }
                            if (rx.status == "PAUSED") {
                                DropdownMenuItem(
                                    text = { Text("Resume") },
                                    leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                    onClick = { menuExpanded = false; onStatusChange("ACTIVE") }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Download PDF") },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                onClick = { menuExpanded = false; onDownloadPdf() }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Detail row ─────────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RxDetailChip(
                    icon  = Icons.Default.Scale,
                    label = "${rx.dosageAmount} ${rx.dosageUnit}"
                )
                RxDetailChip(
                    icon  = Icons.Default.Repeat,
                    label = "${rx.frequencyPerDay}×/day"
                )
                if (rx.durationDays != null) {
                    RxDetailChip(
                        icon  = Icons.Default.CalendarToday,
                        label = "${rx.durationDays}d"
                    )
                }
            }

            // ── Patient alias ──────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Person,
                    null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    // Full name shown to doctor (DOCTOR role from API)
                    rx.patientDisplayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "· ${rx.patientAnonAlias}",
                    style  = MaterialTheme.typography.bodySmall,
                    color  = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }

            // ── Schedule times ─────────────────────────────────────────────────
            if (rx.scheduleTimes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rx.scheduleTimes.forEach { time ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                time,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Small reusable composables ────────────────────────────────────────────────

@Composable
private fun StatChip(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color
) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(12.dp),
        color    = Color.White.copy(alpha = 0.1f)
    ) {
        Column(
            modifier            = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(label, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun RxDetailChip(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon, null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    onAction: (() -> Unit)? = null,
    actionLabel: String = ""
) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (onAction != null) {
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
