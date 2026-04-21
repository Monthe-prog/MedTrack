package com.joechrist.medtrack.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.joechrist.medtrack.data.remote.MedTrackApiService
import com.joechrist.medtrack.data.remote.dto.UserProfileResponse
import com.joechrist.medtrack.data.session.CachedSession
import com.joechrist.medtrack.data.session.SessionManager
import com.joechrist.medtrack.domain.model.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// =============================================================================
// MedTrack – ProfileScreen + ProfileViewModel
// Displays: avatar (Coil-loaded from MinIO signed URL) + user info + sign-out
// Camera FAB → navigates to CameraScreen for avatar update
// =============================================================================

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val api: MedTrackApiService,
    private val session: SessionManager
) : ViewModel() {

    private val _profile = MutableStateFlow<UserProfileResponse?>(null)
    val profile: StateFlow<UserProfileResponse?> = _profile.asStateFlow()

    private val _cachedSession = MutableStateFlow<CachedSession?>(null)
    val cachedSession: StateFlow<CachedSession?> = _cachedSession.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _events = MutableSharedFlow<ProfileEvent>()
    val events: SharedFlow<ProfileEvent> = _events.asSharedFlow()

    init { loadProfile() }

    fun loadProfile() {
        viewModelScope.launch {
            _isLoading.value = true
            _cachedSession.value = session.getSession()
            runCatching { api.getMe() } // token injected by AuthInterceptor
                .onSuccess { _profile.value = it }
                .onFailure { } // silently fall back to cached session
            _isLoading.value = false
        }
    }

    fun onAvatarUpdated(newUrl: String) {
        _profile.update { it?.copy(avatarUrl = newUrl) }
    }

    fun signOut(onComplete: () -> Unit) {
        viewModelScope.launch {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
            session.clearSession()
            onComplete()
        }
    }
}

sealed class ProfileEvent {
    data class ShowError(val message: String) : ProfileEvent()
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateToCamera: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val profile  by viewModel.profile.collectAsState()
    val cached   by viewModel.cachedSession.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val displayName  = profile?.fullName ?: cached?.displayName ?: ""
    val email        = profile?.email    ?: cached?.email ?: ""
    val role         = profile?.role     ?: cached?.role?.name ?: ""
    val anonAlias    = profile?.anonAlias ?: ""
    val avatarUrl    = profile?.avatarUrl

    Scaffold(
        topBar = { TopAppBar(title = { Text("My Profile") }) }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
        ) {
            // ── Avatar header ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0A2647), Color(0xFF0F3460))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box {
                        // Avatar image or initials fallback
                        if (avatarUrl != null) {
                            AsyncImage(
                                model             = avatarUrl,
                                contentDescription = "Profile picture",
                                contentScale      = ContentScale.Crop,
                                modifier          = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .border(3.dp, Color(0xFF1DB98A), CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1DB98A).copy(alpha = 0.2f))
                                    .border(3.dp, Color(0xFF1DB98A), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                    style = MaterialTheme.typography.displaySmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Camera FAB
                        SmallFloatingActionButton(
                            onClick         = onNavigateToCamera,
                            modifier        = Modifier
                                .align(Alignment.BottomEnd)
                                .size(32.dp),
                            containerColor  = Color(0xFF1DB98A),
                            contentColor    = Color.White
                        ) {
                            Icon(Icons.Default.CameraAlt, "Change photo",
                                modifier = Modifier.size(16.dp))
                        }
                    }

                    Text(
                        displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ── Info cards ────────────────────────────────────────────────────
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                ProfileInfoCard {
                    ProfileInfoRow(Icons.Default.Email,   "Email",        email)
                    HorizontalDivider(modifier = Modifier.padding(start = 40.dp))
                    ProfileInfoRow(Icons.Default.Badge,   "Role",         role)
                    if (anonAlias.isNotBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(start = 40.dp))
                        ProfileInfoRow(
                            icon  = Icons.Default.Shield,
                            label = "Privacy alias",
                            value = anonAlias,
                            mono  = true
                        )
                    }
                }

                // Firebase project info (useful during development)
                ProfileInfoCard {
                    ProfileInfoRow(
                        icon  = Icons.Default.Cloud,
                        label = "Firebase project",
                        value = "medtrack-8b6a1",
                        mono  = true
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 40.dp))
                    ProfileInfoRow(
                        icon  = Icons.Default.Android,
                        label = "App package",
                        value = "com.joechrist.medtrack",
                        mono  = true
                    )
                }

                // Change photo button
                OutlinedButton(
                    onClick  = onNavigateToCamera,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Change profile photo")
                }

                // Sign out
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick  = {
                        viewModel.signOut { onSignOut() }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor   = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign out", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── Reusable composables ──────────────────────────────────────────────────────

@Composable
private fun ProfileInfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape     = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun ProfileInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    mono: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon, null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style      = if (mono) MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ) else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
