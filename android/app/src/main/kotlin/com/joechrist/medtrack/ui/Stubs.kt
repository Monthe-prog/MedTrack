// =============================================================================
// MedTrack – Remaining Screen Stubs (built in next step)
// =============================================================================

package com.joechrist.medtrack.ui.patient

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController

@Composable
fun PatientDashboardScreen(navController: NavHostController) {
    Scaffold { _ ->
        Text("Patient Dashboard — built in Step 3")
    }
}

@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
