package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.ui.theme.SpotifyGreen
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import timber.log.Timber
import java.util.concurrent.Executors

@Composable
fun CameraQrScannerDialog(
    onDismiss: () -> Unit,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalStrings.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (hasCameraPermission) {
                val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
                var isScanned by remember { mutableStateOf(false) }

                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                if (isScanned) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                processImageProxy(imageProxy) { qrContent ->
                                    if (!isScanned) {
                                        isScanned = true
                                        onQrScanned(qrContent)
                                        onDismiss()
                                    }
                                }
                            }

                            val lifecycleOwner = (ctx as? androidx.lifecycle.LifecycleOwner)
                                ?: (context as? androidx.lifecycle.LifecycleOwner)

                            if (lifecycleOwner != null) {
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    Timber.e(e, "Camera binding failed")
                                }
                            } else {
                                Timber.w("Cannot bind camera: LifecycleOwner not found")
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                DisposableEffect(Unit) {
                    onDispose {
                        cameraExecutor.shutdown()
                    }
                }

                // Scanning frame overlay
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .align(Alignment.Center)
                        .border(2.dp, SpotifyGreen, RoundedCornerShape(16.dp))
                )

                Text(
                    text = "Point camera at HexShard profile QR",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = 160.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = strings.permissionRequired,
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen)
                    ) {
                        Text(strings.camera, color = Color.Black)
                    }
                }
            }

            // Close button in top-start corner
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .padding(24.dp)
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Scanner",
                    tint = Color.White
                )
            }
        }
    }
}

private fun processImageProxy(imageProxy: ImageProxy, onQrFound: (String) -> Unit) {
    try {
        val buffer = imageProxy.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val width = imageProxy.width
        val height = imageProxy.height

        val source = PlanarYUVLuminanceSource(
            data,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader()
        val result = reader.decodeWithState(binaryBitmap)
        if (result != null && result.text.isNotBlank()) {
            onQrFound(result.text)
        }
    } catch (_: Exception) {
        // Normal if frame contains no QR code
    } finally {
        imageProxy.close()
    }
}
