package com.joechrist.medtrack.ui.doctor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joechrist.medtrack.data.remote.dto.PatientSummaryResponse

// =============================================================================
// MedTrack – Patients Tab
// List view: anonymised (anonAlias + masked name) for privacy-safe overview.
// Detail sheet: full PHI is loaded on drill-down (DOCTOR role sees real name).
// Updated: Minor UI tweaks included.
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientsTab(viewModel: DoctorViewModel) {
    val state by viewModel.dashboardState.collectAsState()
    var selectedPatientId by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Load patient detail when a patient is tapped
    LaunchedEffect(selectedPatientId) {
        selectedPatientId?.let { viewModel.loadPatientDetail(it) }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top bar ───────────────────────────────────────────────────────────
        TopAppBar(
            title = {
                Column {
                    Text("My Patients", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.patientCount} patient${if (state.patientCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        HorizontalDivider()

        when {
            state.isLoadingPatients -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.patients.isEmpty() -> EmptyState(
                icon    = Icons.Default.PersonOff,
                title   = "No patients linked",
                message = "Patients will appear here once they are assigned to your account"
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(state.patients, key = { it.id }) { patient ->
                    PatientListItem(
                        patient = patient,
                        onClick = { selectedPatientId = patient.id }
                    )
                }
            }
        }
    }

    // ── Patient detail bottom sheet ───────────────────────────────────────────
    if (selectedPatientId != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedPatientId = null },
            sheetState       = sheetState
        ) {
            PatientDetailSheet(
                viewModel = viewModel,
                onDismiss = { selectedPatientId = null }
            )
        }
    }
}

// ── Patient list row ──────────────────────────────────────────────────────────

@Composable
private fun PatientListItem(
    patient: PatientSummaryResponse,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Avatar: initial letter in a coloured circle
        val avatarColor = remember(patient.id) {
            val palette = listOf(
                Color(0xFF0F3460), Color(0xFF1DB98A), Color(0xFFE8593C),
                Color(0xFF378ADD), Color(0xFF639922), Color(0xFFBA7517)
            )
            palette[patient.id.hashCode().and(0x7FFFFFFF) % palette.size]
        }
        Box(
            modifier         = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(avatarColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (patient.maskedName.firstOrNull()?.toString() ?: "?").uppercase(),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color      = avatarColor
            )
        }

        // Name + alias
        Column(modifier = Modifier.weight(1f)) {
            // In list view we show the masked name (privacy-safe overview)
            Text(patient.maskedName, style = MaterialTheme.typography.bodyLarge)
            Text(
                patient.anonAlias,
                style      = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color      = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 74.dp))
}

// ── Patient detail bottom sheet ───────────────────────────────────────────────

@Composable
private fun PatientDetailSheet(
    viewModel: DoctorViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.dashboardState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        if (state.isLoadingDetail) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        val patient = state.selectedPatient ?: return@Column

        // ── PHI header (full name visible here because DOCTOR requested it) ──
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    patient.fullName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column {
                Text(
                    patient.fullName,
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    patient.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Alias badge
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Shield, null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Privacy alias: ${patient.anonAlias}",
                    style      = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Prescriptions (${state.patientRxList.size})",
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))

        if (state.patientRxList.isEmpty()) {
            Text(
                "No prescriptions for this patient.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.patientRxList.forEach { rx ->
                PatientRxRow(rx.genericName, rx.status, rx.scheduleTimes)
                if (rx != state.patientRxList.last()) HorizontalDivider()
            }
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Close")
        }
    }
}

@Composable
private fun PatientRxRow(drugName: String, status: String, times: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(drugName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (times.isNotEmpty()) {
                Text(
                    times.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val statusColor = when (status) {
            "ACTIVE"    -> Color(0xFF1D9E75)
            "PAUSED"    -> Color(0xFFBA7517)
            "COMPLETED" -> Color(0xFF378ADD)
            else        -> Color.Gray
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = statusColor.copy(alpha = 0.1f)
        ) {
            Text(
                status,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
