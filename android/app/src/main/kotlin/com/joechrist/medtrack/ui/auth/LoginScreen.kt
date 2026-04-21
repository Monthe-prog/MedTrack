package com.joechrist.medtrack.ui.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.joechrist.medtrack.domain.model.UserRole

// =============================================================================
// MedTrack – Login Screen
// Aesthetic: Clean clinical whites with deep navy header.
// Features: Email/Password sign-in · Google One-Tap · Error snackbar · Loading
// =============================================================================

@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    onLoginSuccess: (UserRole) -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
    googleSignInClient: GoogleSignInClient? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    var email    by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Navigate on successful auth
    LaunchedEffect(authState) {
        val state = authState
        if (state is com.joechrist.medtrack.AuthState.Authenticated) {
            onLoginSuccess(state.role)
        }
    }

    // Show error snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    // Google Sign-In launcher
    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                viewModel.handleGoogleSignIn(account)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
        ) {

            // ── Navy header panel ─────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFF0A2647), Color(0xFF0F3460))
                        )
                    ),
                contentAlignment = Alignment.BottomStart
            ) {
                Column(modifier = Modifier.padding(28.dp)) {
                    // App logo / mark
                    Surface(
                        shape  = RoundedCornerShape(12.dp),
                        color  = Color(0xFF1DB98A),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Rx", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Welcome back",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Sign in to MedTrack",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
            }

            // ── Form card ─────────────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color    = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    // Email field
                    OutlinedTextField(
                        value         = email,
                        onValueChange = { email = it.trim() },
                        label         = { Text("Email address") },
                        leadingIcon   = { Icon(Icons.Default.Email, null) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction    = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        singleLine = true,
                        modifier   = Modifier.fillMaxWidth(),
                        shape      = RoundedCornerShape(12.dp)
                    )

                    // Password field
                    OutlinedTextField(
                        value         = password,
                        onValueChange = { password = it },
                        label         = { Text("Password") },
                        leadingIcon   = { Icon(Icons.Default.Lock, null) },
                        trailingIcon  = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide" else "Show"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction    = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (email.isNotBlank() && password.isNotBlank())
                                    viewModel.signInWithEmail(email, password)
                            }
                        ),
                        singleLine = true,
                        modifier   = Modifier.fillMaxWidth(),
                        shape      = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.height(4.dp))

                    // Primary CTA
                    Button(
                        onClick  = { viewModel.signInWithEmail(email, password) },
                        enabled  = email.isNotBlank() && password.isNotBlank() && !uiState.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        AnimatedContent(
                            targetState = uiState.isLoading,
                            label = "login_button"
                        ) { loading ->
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color    = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Sign in", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    // Divider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Divider(Modifier.weight(1f))
                        Text(
                            "  or  ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Divider(Modifier.weight(1f))
                    }

                    // Google Sign-In button
                    OutlinedButton(
                        onClick  = {
                            googleSignInClient?.let {
                                googleLauncher.launch(it.signInIntent)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape    = RoundedCornerShape(12.dp)
                    ) {
                        // Google "G" icon (text fallback; replace with actual asset)
                        Text(
                            "G",
                            fontWeight = FontWeight.Bold,
                            fontSize   = 16.sp,
                            color      = Color(0xFF4285F4)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Continue with Google",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Register link
                    TextButton(
                        onClick  = onNavigateToRegister,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Don't have an account? ",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Create one",
                            color      = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold,
                            style      = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
