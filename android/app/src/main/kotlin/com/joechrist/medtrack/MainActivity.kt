package com.joechrist.medtrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.joechrist.medtrack.ui.navigation.MedTrackNavHost
import com.joechrist.medtrack.ui.theme.MedTrackTheme
import com.joechrist.medtrack.ui.auth.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels

// =============================================================================
// MedTrack – MainActivity
// Single-activity architecture; all navigation is handled in Compose.
// =============================================================================

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen: keep it visible until auth state resolves
        val splash = installSplashScreen()
        // DEBUG: Force false to bypass splash hang and see if app is alive
        splash.setKeepOnScreenCondition { false }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MedTrackTheme {
                val authState by authViewModel.authState.collectAsState()
                MedTrackNavHost(authState = authState)
            }
        }
    }
}
