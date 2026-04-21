package com.joechrist.medtrack.ui.doctor

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.collectLatest

// =============================================================================
// MedTrack – Doctor Dashboard Screen
// Three-tab layout: Home · Patients · New Rx
// Events (snackbar, URL open) are collected here at the scaffold level.
// =============================================================================

enum class DoctorTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("Home",       Icons.Filled.Home,       Icons.Outlined.Home),
    PATIENTS("Patients", Icons.Filled.People,   Icons.Outlined.People),
    NEW_RX("New Rx",   Icons.Filled.AddCircle,  Icons.Outlined.AddCircleOutline)
}

@Composable
fun DoctorDashboardScreen(
    navController: NavHostController,
    viewModel: DoctorViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(DoctorTab.HOME) }

    // Collect one-off events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is DoctorEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is DoctorEvent.OpenUrl -> {
                    // Open PDF in system browser — handled below via LocalContext
                }
                is DoctorEvent.PrescriptionCreated -> {
                    snackbarHostState.showSnackbar("Prescription created ✓ PDF generating…")
                    selectedTab = DoctorTab.HOME
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                DoctorTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected  = selectedTab == tab,
                        onClick   = { selectedTab = tab },
                        icon = {
                            Icon(
                                if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "doctor_tab"
            ) { tab ->
                when (tab) {
                    DoctorTab.HOME     -> DoctorHomeTab(viewModel, onTabChange = { selectedTab = it })
                    DoctorTab.PATIENTS -> PatientsTab(viewModel)
                    DoctorTab.NEW_RX   -> NewPrescriptionTab(
                        viewModel  = viewModel,
                        onSuccess  = { selectedTab = DoctorTab.HOME }
                    )
                }
            }
        }
    }
}
