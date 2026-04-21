package com.joechrist.medtrack.ui.doctor

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.joechrist.medtrack.data.remote.dto.MedicationResponse

// =============================================================================
// MedTrack – New Prescription Tab
// Multi-step wizard: Patient → Medication → Dosage → Schedule → Review
// Live openFDA search with debounce, schedule time pickers, validation.
// =============================================================================

private enum class RxStep(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    PATIENT   ("Patient",    Icons.Default.Person),
    MEDICATION("Medication", Icons.Default.Medication),
    DOSAGE    ("Dosage",     Icons.Default.Scale),
    SCHEDULE  ("Schedule",   Icons.Default.Schedule),
    REVIEW    ("Review",     Icons.Default.CheckCircle)
}

@Composable
fun NewPrescriptionTab(
    viewModel: DoctorViewModel,
    onSuccess: () -> Unit
) {
    val formState  by viewModel.formState.collectAsState()
    val dashState  by viewModel.dashboardState.collectAsState()
    val medResults by viewModel.medicationResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    var currentStep by remember { mutableStateOf(RxStep.PATIENT) }
    val snackbarState = remember { SnackbarHostState() }

    LaunchedEffect(formState.submitError) {
        formState.submitError?.let { snackbarState.showSnackbar(it) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Step progress bar ─────────────────────────────────────────────
            TopAppBar(
                title = { Text("New Prescription") }
            )
            StepProgressBar(
                steps       = RxStep.entries,
                currentStep = currentStep
            )
            HorizontalDivider()

            // ── Step content (animated transitions) ───────────────────────────
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    slideInHorizontally { dir * it / 3 } + fadeIn() togetherWith
                    slideOutHorizontally { -dir * it / 3 } + fadeOut()
                },
                modifier = Modifier.weight(1f),
                label = "rx_step"
            ) { step ->
                when (step) {
                    RxStep.PATIENT    -> StepPatient(
                        patients      = dashState.patients,
                        selectedId    = formState.selectedPatientId,
                        onSelect      = { id, name -> viewModel.onFormPatientChange(id, name) }
                    )
                    RxStep.MEDICATION -> StepMedication(
                        query         = formState.medicationSearchQuery,
                        onQueryChange = { viewModel.onMedicationSearchQueryChange(it) },
                        results       = medResults,
                        isSearching   = isSearching,
                        selected      = formState.selectedMedication,
                        onSelect      = { viewModel.selectMedication(it) }
                    )
                    RxStep.DOSAGE     -> StepDosage(
                        amount        = formState.dosageAmount,
                        unit          = formState.dosageUnit,
                        duration      = formState.durationDays,
                        instructions  = formState.instructions,
                        onAmountChange      = { viewModel.onDosageAmountChange(it) },
                        onUnitChange        = { viewModel.onDosageUnitChange(it) },
                        onDurationChange    = { viewModel.onDurationChange(it) },
                        onInstructionsChange = { viewModel.onInstructionsChange(it) }
                    )
                    RxStep.SCHEDULE   -> StepSchedule(
                        frequency     = formState.frequencyPerDay,
                        times         = formState.scheduleTimes,
                        onFrequencyChange = { viewModel.onFrequencyChange(it) },
                        onTimeChange  = { i, t -> viewModel.onScheduleTimeChange(i, t) }
                    )
                    RxStep.REVIEW     -> StepReview(
                        formState     = formState,
                        isSubmitting  = formState.isSubmitting,
                        onSubmit      = { viewModel.submitPrescription() }
                    )
                }
            }

            // ── Navigation buttons ────────────────────────────────────────────
            StepNavButtons(
                currentStep  = currentStep,
                canAdvance   = canAdvance(currentStep, formState),
                isSubmitting = formState.isSubmitting,
                onBack    = {
                    val prev = RxStep.entries.getOrNull(currentStep.ordinal - 1)
                    if (prev != null) currentStep = prev
                },
                onNext    = {
                    val next = RxStep.entries.getOrNull(currentStep.ordinal + 1)
                    if (next != null) currentStep = next
                    else {
                        viewModel.submitPrescription()
                    }
                }
            )
        }
    }
}

// ── Step 1 · Patient selection ────────────────────────────────────────────────

@Composable
private fun StepPatient(
    patients: List<com.joechrist.medtrack.data.remote.dto.PatientSummaryResponse>,
    selectedId: String,
    onSelect: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StepHeader("Select patient", "Choose who this prescription is for")

        if (patients.isEmpty()) {
            EmptyState(
                icon = Icons.Default.PersonOff,
                title = "No patients linked",
                message = "Link patients to your account first"
            )
        } else {
            patients.forEach { patient ->
                val isSelected = patient.id == selectedId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelect(patient.id, patient.maskedName) },
                    color = if (isSelected)
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isSelected)
                            Icon(Icons.Default.CheckCircle, null,
                                tint = MaterialTheme.colorScheme.secondary)
                        else
                            Icon(Icons.Default.RadioButtonUnchecked, null,
                                tint = MaterialTheme.colorScheme.outline)
                        Column {
                            Text(patient.maskedName, fontWeight = FontWeight.Medium)
                            Text(
                                patient.anonAlias,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Step 2 · Medication search ────────────────────────────────────────────────

@Composable
private fun StepMedication(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<MedicationResponse>,
    isSearching: Boolean,
    selected: MedicationResponse?,
    onSelect: (MedicationResponse) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StepHeader("Search medication", "Type a drug name to search local DB + openFDA")

        // Search field
        OutlinedTextField(
            value         = query,
            onValueChange = onQueryChange,
            label         = { Text("Drug name (generic or brand)") },
            leadingIcon   = { Icon(Icons.Default.Search, null) },
            trailingIcon  = {
                if (isSearching) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else if (query.isNotBlank()) IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, null)
                }
            },
            singleLine = true,
            modifier   = Modifier.fillMaxWidth(),
            shape      = RoundedCornerShape(12.dp)
        )

        // Selected drug confirmation
        AnimatedVisibility(visible = selected != null) {
            selected?.let { med ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Selected:", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                            Text(med.genericName, fontWeight = FontWeight.SemiBold)
                            if (!med.brandName.isNullOrBlank())
                                Text(med.brandName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!med.strength.isNullOrBlank())
                                Text(med.strength, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.CheckCircle, null,
                            tint = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }

        // Results list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (results.isEmpty() && query.length >= 2 && !isSearching) {
                item {
                    Text(
                        "No results found. Try a different name.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            items(results) { med ->
                MedicationResultCard(med = med, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun MedicationResultCard(med: MedicationResponse, onSelect: (MedicationResponse) -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth().clickable { onSelect(med) },
        shape     = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(med.genericName, fontWeight = FontWeight.SemiBold)
                if (!med.brandName.isNullOrBlank())
                    Text(med.brandName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!med.strength.isNullOrBlank())
                        SourceTag(med.strength!!, MaterialTheme.colorScheme.primaryContainer)
                    if (!med.dosageForm.isNullOrBlank())
                        SourceTag(med.dosageForm!!, MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceTag(
                    med.source,
                    if (med.source == "openFDA") MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                )
                Icon(Icons.Default.AddCircleOutline, "Add", tint = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun SourceTag(label: String, bgColor: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
        Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}

// ── Step 3 · Dosage ───────────────────────────────────────────────────────────

@Composable
private fun StepDosage(
    amount: String, unit: String, duration: String, instructions: String,
    onAmountChange: (String) -> Unit, onUnitChange: (String) -> Unit,
    onDurationChange: (String) -> Unit, onInstructionsChange: (String) -> Unit
) {
    val unitOptions = listOf("tablet", "capsule", "ml", "mg", "drop", "patch", "puff")
    var unitMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StepHeader("Dosage details", "Specify amount, unit and duration")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = amount, onValueChange = onAmountChange,
                label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            )
            // Unit dropdown
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = unit, onValueChange = {},
                    label = { Text("Unit") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { unitMenuExpanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                DropdownMenu(
                    expanded = unitMenuExpanded,
                    onDismissRequest = { unitMenuExpanded = false }
                ) {
                    unitOptions.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt) },
                            onClick = { onUnitChange(opt); unitMenuExpanded = false }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = duration, onValueChange = onDurationChange,
            label = { Text("Duration (days, leave blank for ongoing)") },
            leadingIcon = { Icon(Icons.Default.CalendarToday, null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
            value = instructions, onValueChange = onInstructionsChange,
            label = { Text("Special instructions (optional)") },
            leadingIcon = { Icon(Icons.Default.Notes, null) },
            maxLines = 4, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
    }
}

// ── Step 4 · Schedule ─────────────────────────────────────────────────────────

@Composable
private fun StepSchedule(
    frequency: Int,
    times: List<String>,
    onFrequencyChange: (Int) -> Unit,
    onTimeChange: (Int, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepHeader("Set schedule", "Times per day and alarm schedule")

        // Frequency stepper
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Times per day", style = MaterialTheme.typography.bodyLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalIconButton(
                    onClick  = { if (frequency > 1) onFrequencyChange(frequency - 1) },
                    enabled  = frequency > 1
                ) { Icon(Icons.Default.Remove, null) }
                Text(
                    "$frequency",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                FilledTonalIconButton(
                    onClick  = { if (frequency < 6) onFrequencyChange(frequency + 1) },
                    enabled  = frequency < 6
                ) { Icon(Icons.Default.Add, null) }
            }
        }

        HorizontalDivider()

        Text(
            "Alarm times",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        // Time slots — one per frequency count
        times.forEachIndexed { index, time ->
            TimeSlotRow(
                index  = index + 1,
                time   = time,
                onChange = { newTime -> onTimeChange(index, newTime) }
            )
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AlarmOn, null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Exact alarms will be set on the patient's device for each time slot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSlotRow(index: Int, time: String, onChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val (hourStr, minStr) = time.split(":").let {
        (it.getOrElse(0) { "08" }) to (it.getOrElse(1) { "00" })
    }
    val timePickerState = rememberTimePickerState(
        initialHour   = hourStr.toIntOrNull() ?: 8,
        initialMinute = minStr.toIntOrNull() ?: 0,
        is24Hour      = true
    )

    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Alarm, null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
            Text("Dose $index", style = MaterialTheme.typography.bodyMedium)
        }
        Surface(
            modifier = Modifier.clickable { showPicker = true },
            shape    = RoundedCornerShape(10.dp),
            color    = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                time,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val h = timePickerState.hour.toString().padStart(2, '0')
                    val m = timePickerState.minute.toString().padStart(2, '0')
                    onChange("$h:$m")
                    showPicker = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
            text = {
                TimePicker(state = timePickerState)
            }
        )
    }
}

// ── Step 5 · Review ───────────────────────────────────────────────────────────

@Composable
private fun StepReview(
    formState: PrescriptionFormState,
    isSubmitting: Boolean,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StepHeader("Review prescription", "Confirm all details before creating")

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ReviewRow("Patient",    formState.selectedPatientName)
                HorizontalDivider()
                ReviewRow("Drug",       formState.selectedMedication?.genericName ?: "—")
                ReviewRow("Brand",      formState.selectedMedication?.brandName ?: "—")
                ReviewRow("Strength",   formState.selectedMedication?.strength ?: "—")
                HorizontalDivider()
                ReviewRow("Dosage",     "${formState.dosageAmount} ${formState.dosageUnit}")
                ReviewRow("Frequency",  "${formState.frequencyPerDay}× per day")
                ReviewRow("Duration",   if (formState.durationDays.isBlank()) "Ongoing"
                                        else "${formState.durationDays} days")
                ReviewRow("Start date", formState.startDate)
                if (formState.instructions.isNotBlank()) {
                    HorizontalDivider()
                    ReviewRow("Instructions", formState.instructions)
                }
                HorizontalDivider()
                ReviewRow("Schedule",   formState.scheduleTimes.joinToString(" · "))
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PictureAsPdf, null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary)
                Text(
                    "A PDF prescription will be generated automatically and stored securely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f))
    }
}

// ── Shared composables ────────────────────────────────────────────────────────

@Composable
private fun StepHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepProgressBar(steps: List<RxStep>, currentStep: RxStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
            val isDone    = step.ordinal < currentStep.ordinal
            val isCurrent = step == currentStep
            val color = when {
                isDone    -> MaterialTheme.colorScheme.secondary
                isCurrent -> MaterialTheme.colorScheme.primary
                else      -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            }
            // Step dot
            Box(
                modifier = Modifier
                    .size(if (isCurrent) 10.dp else 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
            // Connector line
            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (isDone) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                )
            }
        }
    }
}

@Composable
private fun StepNavButtons(
    currentStep: RxStep,
    canAdvance: Boolean,
    isSubmitting: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val isLast = currentStep == RxStep.REVIEW
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (currentStep != RxStep.PATIENT) {
                OutlinedButton(
                    onClick  = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Back")
                }
            }
            Button(
                onClick  = onNext,
                enabled  = canAdvance && !isSubmitting,
                modifier = Modifier.weight(if (currentStep == RxStep.PATIENT) 2f else 1f)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = androidx.compose.ui.graphics.Color.White
                    )
                } else {
                    Text(if (isLast) "Create Prescription" else "Next")
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (isLast) Icons.Default.Check else Icons.Default.ArrowForward,
                        null, modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun canAdvance(step: RxStep, form: PrescriptionFormState): Boolean = when (step) {
    RxStep.PATIENT    -> form.selectedPatientId.isNotBlank()
    RxStep.MEDICATION -> form.selectedMedication != null
    RxStep.DOSAGE     -> form.dosageAmount.toDoubleOrNull() != null && form.dosageUnit.isNotBlank()
    RxStep.SCHEDULE   -> form.scheduleTimes.isNotEmpty()
    RxStep.REVIEW     -> form.isValid
}
