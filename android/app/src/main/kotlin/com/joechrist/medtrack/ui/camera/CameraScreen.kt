package com.joechrist.medtrack.ui.camera

import android.Manifest
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.flow.collectLatest

// =============================================================================
// MedTrack – Camera Screen
//
// States:
//   1. PERMISSION REQUEST  — camera permission not yet granted
//   2. PREVIEW             — live CameraX viewfinder with shutter button
//   3. CONFIRM             — captured image shown, Retake / Use Photo buttons
//   4. UPLOADING           — spinner while sending to Ktor → MinIO
//   5. DONE                — success with new avatar URL
// =============================================================================

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onAvatarUploaded: (String) -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val cameraState   by viewModel.cameraState.collectAsState()
    val snackbarState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera permission
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    // Collect one-off events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is CameraEvent.ShowError     -> snackbarState.showSnackbar(event.message)
                is CameraEvent.UploadSuccess -> onAvatarUploaded(event.avatarUrl)
            }
        }
    }

    // Bind camera when permission is granted and we're in Preview state
    LaunchedEffect(cameraPermission.status.isGranted) {
        if (cameraPermission.status.isGranted) {
            viewModel.bindCamera(lifecycleOwner, CameraSelector.LENS_FACING_FRONT)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarState) },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                !cameraPermission.status.isGranted -> {
                    CameraPermissionRequest(
                        shouldShowRationale = cameraPermission.status.shouldShowRationale,
                        onRequest = { cameraPermission.launchPermissionRequest() },
                        onBack = onBack
                    )
                }

                cameraState is CameraUiState.Confirm -> {
                    ConfirmScreen(
                        uri      = (cameraState as CameraUiState.Confirm).uri,
                        onRetake = viewModel::retake,
                        onConfirm = viewModel::confirmAndUpload
                    )
                }

                cameraState is CameraUiState.Uploading -> {
                    UploadingScreen()
                }

                cameraState is CameraUiState.Done -> {
                    DoneScreen(
                        avatarUrl = (cameraState as CameraUiState.Done).avatarUrl,
                        onFinish  = onBack
                    )
                }

                else -> {
                    // Preview + Capture state
                    PreviewScreen(
                        viewModel = viewModel,
                        onBack    = onBack
                    )
                }
            }
        }
    }
}

// ── 1. Permission request ─────────────────────────────────────────────────────

@Composable
private fun CameraPermissionRequest(
    shouldShowRationale: Boolean,
    onRequest: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CameraAlt, null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Camera access needed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (shouldShowRationale)
                "Camera access is required to take your profile picture. Please grant permission in Settings."
            else
                "MedTrack needs camera access to set your profile picture.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
            Text("Grant Camera Permission")
        }
        TextButton(onClick = onBack) { Text("Cancel") }
    }
}

// ── 2. Live preview ───────────────────────────────────────────────────────────

@Composable
private fun PreviewScreen(viewModel: CameraViewModel, onBack: () -> Unit) {
    val isCapturing = viewModel.cameraState.collectAsState().value is CameraUiState.Capturing
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {

        // CameraX PreviewView embedded via AndroidView
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Square crop overlay guide
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(280.dp)
                .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
        )

        // Top bar
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(16.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Profile Photo",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp)) // balance
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Position your face in the frame",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall
            )

            // Shutter button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(4.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(36.dp),
                        color       = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                } else {
                    IconButton(
                        onClick  = viewModel::takePhoto,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            Icons.Default.Camera, "Take photo",
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ── 3. Confirm captured image ─────────────────────────────────────────────────

@Composable
private fun ConfirmScreen(uri: Uri, onRetake: () -> Unit, onConfirm: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.weight(1f))

        // Preview circle
        AsyncImage(
            model         = uri,
            contentDescription = "Captured photo",
            contentScale  = ContentScale.Crop,
            modifier      = Modifier
                .size(260.dp)
                .clip(CircleShape)
                .border(3.dp, Color.White.copy(alpha = 0.4f), CircleShape)
        )

        Spacer(Modifier.weight(1f))

        // Actions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick  = onConfirm,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Use this photo", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick  = onRetake,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Retake")
            }
        }
    }
}

// ── 4. Uploading ──────────────────────────────────────────────────────────────

@Composable
private fun UploadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            "Uploading photo…",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

// ── 5. Done ───────────────────────────────────────────────────────────────────

@Composable
private fun DoneScreen(avatarUrl: String, onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AsyncImage(
            model             = avatarUrl,
            contentDescription = "New profile picture",
            contentScale      = ContentScale.Crop,
            modifier          = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .border(4.dp, MaterialTheme.colorScheme.secondary, CircleShape)
        )
        Spacer(Modifier.height(24.dp))
        Icon(
            Icons.Default.CheckCircle, null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Profile photo updated!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick  = onFinish,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(12.dp)
        ) {
            Text("Done")
        }
    }
}
