package com.example.driver_drowziness.screens

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.driver_drowziness.declarations.DefaultParameters
import com.example.driver_drowziness.hooks.DetectionFeedback
import com.example.driver_drowziness.hooks.DrowsinessProcessor
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Composable that hosts a live CameraX front (selfie) feed, processes real-time eye tracking, and renders overlays.
 */
@Composable
fun LiveMonitorScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var feedback by remember { mutableStateOf(DetectionFeedback(0.0f, false, emptyList(), emptyList())) }

    val processor = remember { DrowsinessProcessor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var earThreshold by remember { mutableFloatStateOf(DefaultParameters.DEFAULT_EAR_THRESH) }
    var onnxConfidence by remember { mutableFloatStateOf(DefaultParameters.DEFAULT_CONF_THRESHOLD) }

    // Keep a persistent PreviewView instance (for smoother display of the camera)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Bind camera to the Front Camera
    LaunchedEffect(Unit) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val preview = androidx.camera.core.Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            var timeUs = 0L

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val currentTimeUs = System.nanoTime() / 1_000L

                if (currentTimeUs < timeUs) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                timeUs = currentTimeUs + DefaultParameters.EVALUATION_FRAME_STEP_US

                processor.processImageProxy(imageProxy) { updatedFeedback ->
                    feedback = updatedFeedback
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis
            )
            Log.d("CameraInit", "Successfully bound to Front (Selfie) camera using microsecond interval stepping")
        } catch (e: Exception) {
            Log.e("CameraInit", "Failed to bind front camera: ${e.message}", e)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Host the persistent PreviewView
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Scales, mirrors horizontally (for front cam), and draws eye-tracking points over the camera feed
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            val imageWidth = feedback.frameWidth.toFloat()
            val imageHeight = feedback.frameHeight.toFloat()

            if (imageWidth <= 0f || imageHeight <= 0f) return@Canvas

            val scale = maxOf(canvasWidth / imageWidth, canvasHeight / imageHeight)
            val scaledWidth = imageWidth * scale
            val scaledHeight = imageHeight * scale

            val offsetX = (canvasWidth - scaledWidth) / 2f
            val offsetY = (canvasHeight - scaledHeight) / 2f

            fun mapPointToCanvas(normX: Float, normY: Float): Offset {
                // Front camera is always mirrored horizontally
                val x = (1.0f - normX) * scaledWidth + offsetX
                val y = normY * scaledHeight + offsetY
                return Offset(x, y)
            }

            // Draw Eye Landmarks
            (feedback.leftEyePoints + feedback.rightEyePoints).forEach { pt ->
                drawCircle(
                    color = Color.Yellow,
                    radius = 4f,
                    center = mapPointToCanvas(pt.first, pt.second)
                )
            }

            // Draw Pose Orientation Vector (Google Gemini was used to generate drawing logic)
            feedback.centerPoint?.let { center ->
                val centerOffset = mapPointToCanvas(center.first, center.second)
                val centerX = centerOffset.x
                val centerY = centerOffset.y

                val pitchRad = Math.toRadians(feedback.pitch.toDouble())
                val yawRad = -1.0 * Math.toRadians(feedback.yaw.toDouble())  // Invert yaw multiplier for front camera mirroring
                val rollRad = Math.toRadians(feedback.roll.toDouble())

                val cosP = cos(pitchRad); val sinP = sin(pitchRad)
                val cosY = cos(yawRad);   val sinY = sin(yawRad)
                val cosR = cos(rollRad);  val sinR = sin(rollRad)

                val axisLength = 80f * scale

                fun project3DAxis(x: Double, y: Double, z: Double): Offset {
                    val x1 = x * cosR - y * sinR
                    val y1 = x * sinR + y * cosR
                    val y2 = y1 * cosP - z * sinP
                    val z2 = y1 * sinP + z * cosP
                    val x3 = x1 * cosY + z2 * sinY

                    return Offset(
                        x = centerX - (x3 * axisLength).toFloat(),
                        y = centerY - (y2 * axisLength).toFloat()
                    )
                }

                fun drawArrow(color: Color, endPoint: Offset, strokeWidth: Float = 5f, headLength: Float = 24f, headAngleDeg: Float = 30f) {
                    drawLine(
                        color = color,
                        start = centerOffset,
                        end = endPoint,
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )

                    val angle = atan2(endPoint.y - centerY, endPoint.x - centerX)
                    val angleRad = Math.toRadians(headAngleDeg.toDouble())

                    val leftWingX = endPoint.x - headLength * cos(angle - angleRad).toFloat()
                    val leftWingY = endPoint.y - headLength * sin(angle - angleRad).toFloat()
                    val rightWingX = endPoint.x - headLength * cos(angle + angleRad).toFloat()
                    val rightWingY = endPoint.y - headLength * sin(angle + angleRad).toFloat()

                    drawLine(color = color, start = endPoint, end = Offset(leftWingX, leftWingY), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                    drawLine(color = color, start = endPoint, end = Offset(rightWingX, rightWingY), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                }

                drawArrow(Color.Red, project3DAxis(1.0, 0.0, 0.0))    // Pitch Axis
                drawArrow(Color.Green, project3DAxis(0.0, 1.0, 0.0))  // Yaw Axis
                drawArrow(Color.Blue, project3DAxis(0.0, 0.0, 1.0))   // Roll Axis
            }
        }

        // Calibrate Baseline Button
        Button(
            onClick = {
                feedback.rawLandmarks?.let { landmarks ->
                    processor.calibrateHeadOrientation(landmarks)
                    processor.calibrateEARandCONF(landmarks)
                    earThreshold = processor.getDefaultValues().earThreshold
                    onnxConfidence = processor.getDefaultValues().confThreshold
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 110.dp, end = 16.dp)
        ) {
            Text("Calibrate")
        }

        // Displays current real-time numeric EAR indicator and Head Pose Angles
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = String.format("EAR Value: %.2f", feedback.ear),
                color = if (feedback.ear < earThreshold) Color.Red else Color.Green,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = String.format("Yaw: %.1f° | Pitch: %.1f° | Roll: %.1f°", feedback.yaw, feedback.pitch, feedback.roll),
                color = Color.Yellow,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // Feedback banner displayed only during verified drowsy state
        if (feedback.isDrowsyConfirmed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(Color.Red.copy(alpha = 0.85f))
                    .align(Alignment.BottomCenter),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚠️ DROWSINESS VERIFIED ⚠️",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Syncs user interface threshold sliders with the core analyzer
        LaunchedEffect(earThreshold, onnxConfidence) {
            processor.updateThresholds(
                newEar = earThreshold,
                newConf = onnxConfidence
            )
        }

        // Adds and controls sensitivity configurations for eye and facial processing
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(6.dp)
        ) {
            Text(
                text = "EAR Threshold: ${String.format("%.2f", earThreshold)}",
                color = Color.White
            )
            Slider(
                value = earThreshold,
                onValueChange = { earThreshold = it },
                valueRange = 0.05f..0.45f,
                modifier = Modifier.height(20.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "YOLO Confidence: ${String.format("%.2f", onnxConfidence)}",
                color = Color.White
            )
            Slider(
                value = onnxConfidence,
                onValueChange = { onnxConfidence = it },
                valueRange = 0.1f..0.9f,
                modifier = Modifier.height(20.dp)
            )
        }
    }
}