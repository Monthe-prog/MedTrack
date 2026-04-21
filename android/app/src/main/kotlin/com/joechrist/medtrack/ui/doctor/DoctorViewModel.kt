package com.joechrist.medtrack.ui.doctor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joechrist.medtrack.data.remote.MedTrackApiService
import com.joechrist.medtrack.data.remote.dto.*
import com.joechrist.medtrack.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// =============================================================================
// MedTrack – DoctorViewModel
// Single ViewModel backing all Doctor screens:
//   • Patient list (anonymised)
//   • Patient detail (full PHI drill-down)
//   • Prescription list + status management
//   • Prescription creation form
//   • Medication search (debounced openFDA + local cache)
//   • PDF download URL fetch
// =============================================================================

@HiltViewModel
class DoctorViewModel @Inject constructor(
    private val api: MedTrackApiService,
    private val session: SessionManager
) : ViewModel() {

    // ── Dashboard UI state ────────────────────────────────────────────────────

    private val _dashboardState = MutableStateFlow(DoctorDashboardState())
    val dashboardState: StateFlow<DoctorDashboardState> = _dashboardState.asStateFlow()

    // ── Prescription creation form state ──────────────────────────────────────

    private val _formState = MutableStateFlow(PrescriptionFormState())
    val formState: StateFlow<PrescriptionFormState> = _formState.asStateFlow()

    // ── Medication search ─────────────────────────────────────────────────────

    private val _medicationQuery = MutableStateFlow("")
    val medicationQuery: StateFlow<String> = _medicationQuery.asStateFlow()

    private val _medicationResults = MutableStateFlow<List<MedicationResponse>>(emptyList())
    val medicationResults: StateFlow<List<MedicationResponse>> = _medicationResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // ── One-off events ────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<DoctorEvent>()
    val events: SharedFlow<DoctorEvent> = _events.asSharedFlow()

    init {
        loadDashboard()
        setupMedicationSearchDebounce()
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    fun loadDashboard() {
        viewModelScope.launch {
            _dashboardState.update { it.copy(isLoadingPatients = true, error = null) }
            runCatching {
                val patients      = api.getMyPatients()
                val prescriptions = api.getDoctorPrescriptions()
                val cached        = session.getSession()
                Triple(patients, prescriptions, cached)
            }.onSuccess { (patients, prescriptions, cached) ->
                _dashboardState.update {
                    it.copy(
                        isLoadingPatients   = false,
                        patients            = patients,
                        prescriptions       = prescriptions,
                        doctorName          = cached?.displayName ?: "",
                        activeRxCount       = prescriptions.count { p -> p.status == "ACTIVE" },
                        patientCount        = patients.size
                    )
                }
            }.onFailure { e ->
                _dashboardState.update {
                    it.copy(isLoadingPatients = false, error = e.message)
                }
            }
        }
    }

    // ── Patient detail ────────────────────────────────────────────────────────

    fun loadPatientDetail(patientId: String) {
        viewModelScope.launch {
            _dashboardState.update { it.copy(isLoadingDetail = true, selectedPatient = null) }
            runCatching {
                val profile = api.getUserById(patientId)
                val rxList  = api.getPrescriptionsForPatient(patientId)
                Pair(profile, rxList)
            }.onSuccess { (profile, rxList) ->
                _dashboardState.update {
                    it.copy(
                        isLoadingDetail   = false,
                        selectedPatient   = profile,
                        patientRxList     = rxList
                    )
                }
            }.onFailure { e ->
                _dashboardState.update { it.copy(isLoadingDetail = false, error = e.message) }
            }
        }
    }

    // ── Prescription status update ────────────────────────────────────────────

    fun updatePrescriptionStatus(rxId: String, newStatus: String) {
        viewModelScope.launch {
            runCatching {
                api.updatePrescriptionStatus(rxId, UpdateStatusRequest(newStatus))
            }.onSuccess {
                loadDashboard()
                _events.emit(DoctorEvent.ShowSnackbar("Prescription $newStatus"))
            }.onFailure { e ->
                _events.emit(DoctorEvent.ShowSnackbar("Failed: ${e.message}"))
            }
        }
    }

    // ── PDF download ──────────────────────────────────────────────────────────

    fun fetchPdfUrl(rxId: String) {
        viewModelScope.launch {
            runCatching { api.getPrescriptionPdfUrl(rxId) }
                .onSuccess { _events.emit(DoctorEvent.OpenUrl(it.pdfUrl)) }
                .onFailure { _events.emit(DoctorEvent.ShowSnackbar("PDF not ready yet")) }
        }
    }

    // ── Medication search (debounced 400 ms) ──────────────────────────────────

    private fun setupMedicationSearchDebounce() {
        viewModelScope.launch {
            _medicationQuery
                .debounce(400)
                .filter { it.length >= 2 }
                .distinctUntilChanged()
                .collectLatest { query ->
                    _isSearching.value = true
                    runCatching { api.searchMedications(query) }
                        .onSuccess { _medicationResults.value = it }
                        .onFailure { _medicationResults.value = emptyList() }
                    _isSearching.value = false
                }
        }
    }

    fun onMedicationQueryChange(query: String) {
        _medicationQuery.value = query
        if (query.isBlank()) _medicationResults.value = emptyList()
    }

    fun selectMedication(med: MedicationResponse) {
        _formState.update {
            it.copy(
                selectedMedication    = med,
                medicationSearchQuery = med.brandName ?: med.genericName
            )
        }
        _medicationResults.value = emptyList()
    }

    // ── Form field updates ────────────────────────────────────────────────────

    fun onFormPatientChange(patientId: String, name: String) =
        _formState.update { it.copy(selectedPatientId = patientId, selectedPatientName = name) }

    fun onDosageAmountChange(v: String) =
        _formState.update { it.copy(dosageAmount = v) }

    fun onDosageUnitChange(v: String) =
        _formState.update { it.copy(dosageUnit = v) }

    fun onFrequencyChange(v: Int) {
        // Auto-generate evenly-spaced schedule times based on frequency
        val times = generateScheduleTimes(v)
        _formState.update { it.copy(frequencyPerDay = v, scheduleTimes = times) }
    }

    fun onScheduleTimeChange(index: Int, time: String) {
        _formState.update {
            val updated = it.scheduleTimes.toMutableList()
            if (index < updated.size) updated[index] = time
            it.copy(scheduleTimes = updated)
        }
    }

    fun onDurationChange(v: String) =
        _formState.update { it.copy(durationDays = v) }

    fun onInstructionsChange(v: String) =
        _formState.update { it.copy(instructions = v) }

    fun onStartDateChange(v: String) =
        _formState.update { it.copy(startDate = v) }

    fun onMedicationSearchQueryChange(v: String) {
        _formState.update { it.copy(medicationSearchQuery = v) }
        onMedicationQueryChange(v)
    }

    // ── Prescription submission ───────────────────────────────────────────────

    fun submitPrescription() {
        val form = _formState.value
        val med  = form.selectedMedication ?: return

        viewModelScope.launch {
            _formState.update { it.copy(isSubmitting = true, submitError = null) }
            runCatching {
                api.createPrescription(
                    CreatePrescriptionRequest(
                        patientId             = form.selectedPatientId,
                        medicationId          = med.id,
                        medicationGenericName = med.genericName,
                        medicationBrandName   = med.brandName,
                        dosageAmount          = form.dosageAmount.toDoubleOrNull() ?: 1.0,
                        dosageUnit            = form.dosageUnit,
                        frequencyPerDay       = form.frequencyPerDay,
                        durationDays          = form.durationDays.toIntOrNull(),
                        instructions          = form.instructions.takeIf { it.isNotBlank() },
                        scheduleTimes         = form.scheduleTimes,
                        startDate             = form.startDate,
                        endDate               = null
                    )
                )
            }.onSuccess { rx ->
                _formState.update { it.copy(isSubmitting = false) }
                _dashboardState.update {
                    it.copy(prescriptions = it.prescriptions + rx)
                }
                _events.emit(DoctorEvent.PrescriptionCreated(rx.id))
                resetForm()
                loadDashboard()
            }.onFailure { e ->
                _formState.update {
                    it.copy(isSubmitting = false, submitError = e.message ?: "Failed to create prescription")
                }
            }
        }
    }

    fun resetForm() {
        _formState.value = PrescriptionFormState()
        _medicationResults.value = emptyList()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun generateScheduleTimes(frequency: Int): List<String> {
        if (frequency <= 0) return emptyList()
        val intervalHours = 24 / frequency
        return (0 until frequency).map { i ->
            LocalTime.of((8 + i * intervalHours) % 24, 0)
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        }
    }
}

// ── State models ──────────────────────────────────────────────────────────────

data class DoctorDashboardState(
    val isLoadingPatients: Boolean            = true,
    val isLoadingDetail: Boolean              = false,
    val patients: List<PatientSummaryResponse> = emptyList(),
    val prescriptions: List<PrescriptionResponse> = emptyList(),
    val selectedPatient: UserProfileResponse? = null,
    val patientRxList: List<PrescriptionResponse> = emptyList(),
    val doctorName: String                    = "",
    val activeRxCount: Int                    = 0,
    val patientCount: Int                     = 0,
    val error: String?                        = null
)

data class PrescriptionFormState(
    val selectedPatientId: String             = "",
    val selectedPatientName: String           = "",
    val medicationSearchQuery: String         = "",
    val selectedMedication: MedicationResponse? = null,
    val dosageAmount: String                  = "1",
    val dosageUnit: String                    = "tablet",
    val frequencyPerDay: Int                  = 1,
    val scheduleTimes: List<String>           = listOf("08:00"),
    val durationDays: String                  = "30",
    val instructions: String                  = "",
    val startDate: String                     = LocalDate.now().toString(),
    val isSubmitting: Boolean                 = false,
    val submitError: String?                  = null
) {
    val isValid get() = selectedPatientId.isNotBlank() &&
        selectedMedication != null &&
        dosageAmount.toDoubleOrNull() != null &&
        dosageUnit.isNotBlank()
}

// ── One-off events ────────────────────────────────────────────────────────────

sealed class DoctorEvent {
    data class ShowSnackbar(val message: String) : DoctorEvent()
    data class OpenUrl(val url: String) : DoctorEvent()
    data class PrescriptionCreated(val rxId: String) : DoctorEvent()
}
