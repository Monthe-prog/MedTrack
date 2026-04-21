package com.joechrist.medtrack.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.joechrist.medtrack.AuthState
import com.joechrist.medtrack.domain.model.UserRole
import com.joechrist.medtrack.ui.auth.LoginScreen
import com.joechrist.medtrack.ui.auth.RegisterScreen
import com.joechrist.medtrack.ui.doctor.DoctorDashboardScreen
import com.joechrist.medtrack.ui.patient.PatientDashboardScreen
import com.joechrist.medtrack.ui.patient.SplashScreen

// =============================================================================
// MedTrack – Navigation Graph
// =============================================================================

// ── Route destinations ────────────────────────────────────────────────────────
sealed class Screen(val route: String) {
    data object Splash          : Screen("splash")
    data object Login           : Screen("login")
    data object Register        : Screen("register")
    data object DoctorDashboard : Screen("doctor/dashboard")
    data object PatientDashboard: Screen("patient/dashboard")

    // Nested destinations (used by child NavHosts inside dashboards)
    data object Prescriptions   : Screen("prescriptions")
    data object PrescriptionDetail : Screen("prescriptions/{prescriptionId}") {
        fun route(id: String) = "prescriptions/$id"
    }
    data object MedicationSearch: Screen("medications/search")
    data object Chat            : Screen("chat/{roomId}") {
        fun route(roomId: String) = "chat/$roomId"
    }
    data object Profile         : Screen("profile")
    data object Camera          : Screen("camera/{mode}") {       // mode: avatar | scan
        fun route(mode: String) = "camera/$mode"
    }
}

// ── Root NavHost ──────────────────────────────────────────────────────────────

@Composable
fun MedTrackNavHost(
    authState: AuthState,
    navController: NavHostController = rememberNavController()
) {
    // Determine where to start based on auth resolution
    val startDestination = when (authState) {
        is AuthState.Loading        -> Screen.Splash.route
        is AuthState.Unauthenticated -> Screen.Login.route
        is AuthState.Authenticated  -> when (authState.role) {
            UserRole.DOCTOR, UserRole.ADMIN -> Screen.DoctorDashboard.route
            UserRole.PATIENT                -> Screen.PatientDashboard.route
        }
    }

    NavHost(
        navController    = navController,
        startDestination = startDestination,
        enterTransition  = { slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(280)) },
        exitTransition   = { slideOutHorizontally(tween(200)) { -it / 4 } + fadeOut(tween(200)) },
        popEnterTransition = { slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(280)) },
        popExitTransition  = { slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(200)) }
    ) {

        composable(Screen.Splash.route) {
            SplashScreen()
        }

        composable(Screen.Login.route) {
            LoginScreen(
                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                onLoginSuccess = { /* Handled by LaunchedEffect in LoginScreen */ }
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                onBack = { navController.popBackStack() },
                onRegisterSuccess = { /* Handled by LaunchedEffect in RegisterScreen */ }
            )
        }

        composable(Screen.DoctorDashboard.route) {
            DoctorDashboardScreen(navController = navController)
        }

        composable(Screen.PatientDashboard.route) {
            PatientDashboardScreen(navController = navController)
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                androidx.navigation.navArgument("roomId") {
                    type = androidx.navigation.NavType.StringType
                }
            )
        ) {
            com.joechrist.medtrack.ui.chat.ChatScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Camera.route,
            arguments = listOf(
                androidx.navigation.navArgument("mode") {
                    type = androidx.navigation.NavType.StringType
                }
            )
        ) {
            com.joechrist.medtrack.ui.camera.CameraScreen(
                onBack            = { navController.popBackStack() },
                onAvatarUploaded  = { navController.popBackStack() }
            )
        }

        composable(Screen.Profile.route) {
            com.joechrist.medtrack.ui.profile.ProfileScreen(
                onNavigateToCamera = {
                    navController.navigate(Screen.Camera.route("avatar"))
                },
                onSignOut = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
