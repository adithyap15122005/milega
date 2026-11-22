package com.milega.app.ui.shop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.OcrLineDto
import com.milega.app.ui.Routes
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.theme.BrandColors
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.util.Media
import com.milega.app.util.Ocr
import com.milega.app.util.errorText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

private enum class Step { Preparing, Reading, Uploading, Identifying }

/** Kept so a failed upload can be retried without re-taking the photo. */
private class PreparedScan(val file: File, val ocr: List<OcrLineDto>, val requestId: String)

@Composable
fun ScanScreen(container: AppContainer, nav: NavController, initialMode: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var step by remember { mutableStateOf<Step?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<PreparedScan?>(null) }
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val provider = rememberLoadable { container.api.providerStatus() }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }

    DisposableEffect(Unit) { onDispose { Media.cleanup(context) } }

    fun upload(scan: PreparedScan) = scope.launch {
        error = null
        try {
            step = Step.Uploading
            var job = container.api.uploadScan(scan.file, mode, scan.ocr, scan.requestId)
            step = Step.Identifying
            var polls = 0
            while (job.status == "queued" || job.status == "processing") {
                if (polls++ > 80) throw IllegalStateException("timeout")
                delay(1500)
                job = container.api.job(job.id)
            }
            step = null
            prepared = null
            nav.navigate(Routes.review(job.id))
        } catch (e: Exception) {
            step = null
            error = if (e is IllegalStateException) context.getString(R.string.scan_timeout) else errorText(context, e)
        }
    }

    fun process(uri: Uri) = scope.launch {
        error = null
        try {
            step = Step.Preparing
            val (file, bitmap) = Media.prepareUpload(context, uri)
            step = Step.Reading
            val ocr = Ocr.read(bitmap)
            val scan = PreparedScan(file, ocr, UUID.randomUUID().toString())
            prepared = scan
            upload(scan)
        } catch (e: Exception) {
            step = null
            error = context.getString(R.string.error_bad_photo)
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) process(uri) }
    LaunchedEffect(Unit) { if (!hasCamera) cameraPermission.launch(Manifest.permission.CAMERA) }

    ShopkeeperTheme {
        AppScaffold(title = stringResource(R.string.tile_scan), onBack = { nav.popBackStack() }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val modes = listOf("incoming" to R.string.mode_incoming, "shelf" to R.string.mode_shelf, "invoice" to R.string.mode_invoice)
                    items(modes) { (key, label) ->
                        FilterChip(selected = mode == key, enabled = step == null, onClick = { mode = key }, label = { Text(stringResource(label)) })
                    }
                }
                Text(
                    stringResource(when (mode) {
                        "shelf" -> R.string.mode_shelf_help
                        "invoice" -> R.string.mode_invoice_help
                        else -> R.string.mode_incoming_help
                    }),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasCamera) CameraPreview(imageCapture) else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text(stringResource(R.string.camera_permission_needed), color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            FilledTonalButton(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) {
                                Text(stringResource(R.string.allow_camera))
                            }
                        }
                    }
                    val current = step
                    if (current != null) ProgressOverlay(current, mode)
                }
                error?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.width(8.dp))
                                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            Row {
                                prepared?.let { scan -> TextButton(onClick = { upload(scan) }) { Text(stringResource(R.string.retry_upload)) } }
                                TextButton(onClick = { nav.navigate(Routes.S_ADD) }) { Text(stringResource(R.string.add_manually)) }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        enabled = step == null,
                        onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.gallery))
                    }
                    Spacer(Modifier.weight(1f))
                    // Shutter
                    Surface(
                        onClick = { capture(context, imageCapture, onSaved = { process(it) }, onError = { error = context.getString(R.string.capture_failed) }) },
                        enabled = hasCamera && step == null,
                        shape = CircleShape,
                        color = BrandColors.Green,
                        border = BorderStroke(5.dp, MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.size(78.dp),
                    ) {}
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(96.dp))
                }
                provider.data?.let {
                    Text(
                        stringResource(if (it.canCount) R.string.provider_ai else R.string.provider_ocr),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(imageCapture: ImageCapture) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        val cameraProvider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        } catch (e: Exception) {
            // No back camera (some emulators): gallery upload still works.
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    // Framing guide
    Box(Modifier.fillMaxSize().padding(28.dp).border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp)))
}

private fun capture(context: Context, imageCapture: ImageCapture, onSaved: (Uri) -> Unit, onError: () -> Unit) {
    val file = Media.newCaptureFile(context)
    val savedCallback = onSaved
    val errorCallback = onError
    imageCapture.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                savedCallback(Uri.fromFile(file))
            }

            override fun onError(exception: ImageCaptureException) {
                errorCallback()
            }
        },
    )
}

@Composable
private fun ProgressOverlay(step: Step, mode: String) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp)) {
            val steps = listOf(
                Step.Preparing to R.string.step_preparing,
                Step.Reading to R.string.step_reading,
                Step.Uploading to R.string.step_uploading,
                Step.Identifying to if (mode == "invoice") R.string.step_reading_bill else R.string.step_identifying,
            )
            steps.forEach { (s, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    when {
                        s.ordinal < step.ordinal -> Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF6FD3AA))
                        s == step -> CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        else -> Icon(Icons.Filled.RadioButtonUnchecked, null, tint = Color.White.copy(alpha = 0.4f))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(label), color = if (s.ordinal <= step.ordinal) Color.White else Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
