package com.joechrist.medtrack.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joechrist.medtrack.data.remote.MedTrackApiService
import com.joechrist.medtrack.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// =============================================================================
// MedTrack – CameraViewModel
// Manages:
//   • CameraX bind/unbind lifecycle
//   • Photo capture via ImageCapture use-case
//   • JPEG compression + square crop
//   • Multipart upload to Ktor /users/{id}/avatar
//   • Preview URI state for in-app confirm/retake flow
// =============================================================================

@HiltViewModel
class CameraViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MedTrackApiService,
    private val session: SessionManager
) : ViewModel() {

    // ── Camera state ──────────────────────────────────────────────────────────
    private val _cameraState = MutableStateFlow<CameraUiState>(CameraUiState.Preview)
    val cameraState: StateFlow<CameraUiState> = _cameraState.asStateFlow()

    // ── Captured image (shown in confirm screen) ──────────────────────────────
    private val _capturedUri = MutableStateFlow<Uri?>(null)
    val capturedUri: StateFlow<Uri?> = _capturedUri.asStateFlow()

    // ── Upload result ─────────────────────────────────────────────────────────
    private val _uploadedAvatarUrl = MutableStateFlow<String?>(null)
    val uploadedAvatarUrl: StateFlow<String?> = _uploadedAvatarUrl.asStateFlow()

    // ── Events ────────────────────────────────────────────────────────────────
    private val _events = MutableSharedFlow<CameraEvent>()
    val events: SharedFlow<CameraEvent> = _events.asSharedFlow()

    // ── CameraX internals ─────────────────────────────────────────────────────
    private var imageCapture: ImageCapture? = null
    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── Bind camera use-cases to lifecycle ────────────────────────────────────

    fun bindCamera(lifecycleOwner: LifecycleOwner, lensFacing: Int = CameraSelector.LENS_FACING_FRONT) {
        viewModelScope.launch(Dispatchers.Main) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cameraProviderFuture.addListener({
                    cont.resume(cameraProviderFuture.get())
                }, ContextCompat.getMainExecutor(context))
            }.let { cameraProvider: ProcessCameraProvider ->

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)  // Use 4:3, crop to square later
                    .build()

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setJpegQuality(90)
                    .build()

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } catch (e: Exception) {
                    viewModelScope.launch {
                        _events.emit(CameraEvent.ShowError("Camera failed to start: ${e.message}"))
                    }
                }
            }
        }
    }

    // ── Take photo ────────────────────────────────────────────────────────────

    fun takePhoto() {
        val capture = imageCapture ?: return
        _cameraState.value = CameraUiState.Capturing

        val outputFile = File(context.cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    viewModelScope.launch {
                        val processedUri = processImage(outputFile)
                        _capturedUri.value = processedUri
                        _cameraState.value = CameraUiState.Confirm(processedUri)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    viewModelScope.launch {
                        _cameraState.value = CameraUiState.Preview
                        _events.emit(CameraEvent.ShowError("Capture failed: ${exception.message}"))
                    }
                }
            }
        )
    }

    // ── Retake ────────────────────────────────────────────────────────────────

    fun retake() {
        _capturedUri.value?.let { uri ->
            runCatching { File(uri.path ?: "").delete() }
        }
        _capturedUri.value = null
        _cameraState.value = CameraUiState.Preview
    }

    // ── Confirm & upload ──────────────────────────────────────────────────────

    fun confirmAndUpload() {
        val uri = _capturedUri.value ?: return
        viewModelScope.launch {
            _cameraState.value = CameraUiState.Uploading

            val cached = session.getSession() ?: run {
                _events.emit(CameraEvent.ShowError("Not signed in"))
                _cameraState.value = CameraUiState.Preview
                return@launch
            }

            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    File(uri.path ?: error("No file path")).readBytes()
                }
                val requestBody = bytes.toRequestBody("image/jpeg".toMediaType())
                val part = MultipartBody.Part.createFormData(
                    "image", "avatar.jpg", requestBody
                )
                api.uploadAvatar(cached.firebaseUid, part)
            }.onSuccess { response ->
                _uploadedAvatarUrl.value = response.avatarUrl
                _cameraState.value = CameraUiState.Done(response.avatarUrl)
                _events.emit(CameraEvent.UploadSuccess(response.avatarUrl))
            }.onFailure { e ->
                _cameraState.value = CameraUiState.Confirm(_capturedUri.value!!)
                _events.emit(CameraEvent.ShowError("Upload failed: ${e.message}"))
            }
        }
    }

    // ── Image processing: square-crop + compress ──────────────────────────────

    private suspend fun processImage(file: File): Uri = withContext(Dispatchers.IO) {
        val original = BitmapFactory.decodeFile(file.absolutePath)

        // Square crop from centre
        val size = minOf(original.width, original.height)
        val xOffset = (original.width - size) / 2
        val yOffset = (original.height - size) / 2
        val cropped = Bitmap.createBitmap(original, xOffset, yOffset, size, size)
        original.recycle()

        // Scale to 512×512 for storage efficiency
        val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
        cropped.recycle()

        // Re-compress to JPEG at 85% quality
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()

        // Write processed bytes back to file
        file.writeBytes(out.toByteArray())
        Uri.fromFile(file)
    }

    override fun onCleared() {
        super.onCleared()
        cameraExecutor.shutdown()
    }
}

// ── UI state ──────────────────────────────────────────────────────────────────

sealed class CameraUiState {
    data object Preview                     : CameraUiState()
    data object Capturing                   : CameraUiState()
    data class  Confirm(val uri: Uri)       : CameraUiState()
    data object Uploading                   : CameraUiState()
    data class  Done(val avatarUrl: String) : CameraUiState()
}

sealed class CameraEvent {
    data class ShowError(val message: String)       : CameraEvent()
    data class UploadSuccess(val avatarUrl: String) : CameraEvent()
}
