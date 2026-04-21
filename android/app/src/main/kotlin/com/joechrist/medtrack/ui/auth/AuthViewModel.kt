package com.joechrist.medtrack.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.joechrist.medtrack.AuthState
import com.joechrist.medtrack.data.remote.MedTrackApiService
import com.joechrist.medtrack.data.remote.dto.RegisterRequest
import com.joechrist.medtrack.data.session.SessionManager
import com.joechrist.medtrack.domain.model.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

// =============================================================================
// MedTrack – AuthViewModel
// Manages the full Firebase auth lifecycle:
//   Email/Password sign-in & sign-up
//   Google Sign-In (credential exchange → Firebase)
//   Token refresh → Ktor backend registration
//   Auth state persistence via DataStore SessionManager
// =============================================================================

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val apiService: MedTrackApiService,
    private val sessionManager: SessionManager
) : ViewModel() {

    // ── Auth state ────────────────────────────────────────────────────────────

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // ── UI state ──────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        // Listen to Firebase auth state changes and resolve role from session
        viewModelScope.launch {
            val currentUser = firebaseAuth.currentUser
            if (currentUser == null) {
                _authState.value = AuthState.Unauthenticated
            } else {
                resolveAuthState(currentUser)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EMAIL / PASSWORD
    // ─────────────────────────────────────────────────────────────────────────

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
                result.user ?: error("Firebase returned null user")
            }.onSuccess { user ->
                resolveAuthState(user)
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun createAccountWithEmail(
        email: String,
        password: String,
        fullName: String,
        role: UserRole
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                // 1. Create Firebase account
                val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
                val user = result.user ?: error("Firebase returned null user")

                // 2. Register with our Ktor backend (creates user row + assigns role)
                apiService.register(
                    body = RegisterRequest(fullName = fullName, role = role.name)
                )

                // 4. Persist session
                sessionManager.saveSession(
                    firebaseUid = user.uid,
                    role        = role,
                    displayName = fullName,
                    email       = email
                )
                role
            }.onSuccess { role ->
                _uiState.update { it.copy(isLoading = false) }
                _authState.value = AuthState.Authenticated(role = role)
            }.onFailure { e ->
                // Clean up Firebase account if backend registration failed
                firebaseAuth.currentUser?.delete()?.await()
                _uiState.update { it.copy(isLoading = false, error = e.toUserMessage()) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GOOGLE SIGN-IN
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called after the Google Sign-In intent completes in the Activity/Composable.
     * Exchanges the Google credential for a Firebase credential, then resolves state.
     */
    fun handleGoogleSignIn(account: GoogleSignInAccount, defaultRole: UserRole = UserRole.PATIENT) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                val result = firebaseAuth.signInWithCredential(credential).await()
                val user = result.user ?: error("Firebase returned null user")
                val isNewUser = result.additionalUserInfo?.isNewUser == true

                if (isNewUser) {
                    // New Google user: register with backend
                    apiService.register(
                        body = RegisterRequest(
                            fullName = account.displayName ?: user.email ?: "User",
                            role     = defaultRole.name
                        )
                    )
                    sessionManager.saveSession(
                        firebaseUid = user.uid,
                        role        = defaultRole,
                        displayName = account.displayName ?: "",
                        email       = account.email ?: ""
                    )
                }
                user
            }.onSuccess { user ->
                resolveAuthState(user)
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.toUserMessage()) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SIGN OUT
    // ─────────────────────────────────────────────────────────────────────────

    fun signOut() {
        viewModelScope.launch {
            firebaseAuth.signOut()
            sessionManager.clearSession()
            _authState.value = AuthState.Unauthenticated
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun resolveAuthState(user: FirebaseUser) {
        runCatching {
            // Try session cache first (avoids network on every cold start)
            val cached = sessionManager.getSession()
            if (cached != null) {
                _uiState.update { it.copy(isLoading = false) }
                _authState.value = AuthState.Authenticated(role = cached.role)
                return
            }

            // Cache miss: fetch from backend
            val profile = apiService.getMe()

            val role = UserRole.fromName(profile.role)
            sessionManager.saveSession(
                firebaseUid = user.uid,
                role        = role,
                displayName = profile.fullName,
                email       = profile.email
            )
            _uiState.update { it.copy(isLoading = false) }
            _authState.value = AuthState.Authenticated(role = role)
        }.onFailure { e ->
            _uiState.update { it.copy(isLoading = false, error = e.toUserMessage()) }
            _authState.value = AuthState.Unauthenticated
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun Throwable.toUserMessage(): String {
        val msg = this.message?.lowercase() ?: ""
        return when {
            msg.contains("password") -> "Incorrect password. Please try again."
            msg.contains("email") -> "No account found with this email."
            msg.contains("network") || msg.contains("timeout") || msg.contains("resolve") || msg.contains("connect") ->
                "Network error: ${this.localizedMessage ?: "Check your connection"}"
            msg.contains("weak_password") -> "Password must be at least 6 characters."
            msg.contains("email_exists") -> "An account with this email already exists."
            else -> "Something went wrong: ${this.localizedMessage ?: "Unknown error"}"
        }
    }
}

// ── UI state ──────────────────────────────────────────────────────────────────

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String?     = null
)
