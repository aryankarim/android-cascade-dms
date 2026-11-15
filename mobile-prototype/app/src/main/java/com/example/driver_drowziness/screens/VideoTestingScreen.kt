package com.example.driver_drowziness.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.driver_drowziness.declarations.Declarations
import com.example.driver_drowziness.hooks.DrowsinessProcessor
import com.example.driver_drowziness.declarations.VideoTestingViewModel

/**
 * Composable screen for uploading dataset zips, selecting calibration frames, and running video evaluation metrics.
 */
@Composable
fun VideoTestingScreen(viewModel: VideoTestingViewModel = viewModel()) {
    val context = LocalContext.current
    val processor = remember { DrowsinessProcessor(context) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.handleZipSelection(context, it) }
    }

    val totalTested = viewModel.liveTp + viewModel.liveFp + viewModel.liveTn + viewModel.liveFn
    val liveAccuracy = if (totalTested > 0) (viewModel.liveTp + viewModel.liveTn).toFloat() / totalTested else 0f
    val livePrecision = if ((viewModel.liveTp + viewModel.liveFp) > 0) viewModel.liveTp.toFloat() / (viewModel.liveTp + viewModel.liveFp) else 0f
    val liveRecall = if ((viewModel.liveTp + viewModel.liveFn) > 0) viewModel.liveTp.toFloat() / (viewModel.liveTp + viewModel.liveFn) else 0f
    val liveF1 = if ((livePrecision + liveRecall) > 0f) 2f * (livePrecision * liveRecall) / (livePrecision + liveRecall) else 0f

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Video Dataset Testing & Calibration", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        if (viewModel.workflowState == Declarations.TestingWorkflowState.IDLE) {
            Button(onClick = { zipPickerLauncher.launch("application/zip") }) {
                Text("Upload Dataset (.zip)")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(text = viewModel.progressStatus, fontSize = 13.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        // 1. SELECTING VIDEO STATE
        if (viewModel.workflowState == Declarations.TestingWorkflowState.SELECTING_VIDEO) {
            Text("Choose a sample video for calibration:", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(viewModel.availableVideos.value) { videoFile ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { viewModel.loadCalibrationFrames(processor, videoFile) }) {
                        Text(text = "${videoFile.parentFile?.name}/${videoFile.name}", modifier = Modifier.padding(12.dp), fontSize = 14.sp)
                    }
                }
            }
        }

        // 2. SELECTING FRAME STATE
        else if (viewModel.workflowState == Declarations.TestingWorkflowState.SELECTING_FRAME) {
            Text("Tap a frame and adjust the EAR threshold slider below:", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(viewModel.calibrationFrames.value) { frame ->
                    val isSelected = frame == viewModel.selectedFrame
                    Column(
                        modifier = Modifier
                            .aspectRatio(0.85f)
                            .border(width = if (isSelected) 3.dp else 1.dp, color = if (isSelected) Color.Red else Color.Blue)
                            .clickable {
                                viewModel.selectedFrame = frame
                                viewModel.selectedManualEt = frame.earThreshold
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(bitmap = frame.bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().weight(1f))
                        Surface(color = Color.Black.copy(alpha = 0.75f), modifier = Modifier.fillMaxWidth()) {
                            Text(text = "ET=${String.format("%.2f", frame.earThreshold)}", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            viewModel.selectedFrame?.let { _ ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "Manual EAR Threshold (ET): ${String.format("%.2f", viewModel.selectedManualEt)}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Slider(
                            value = viewModel.selectedManualEt,
                            onValueChange = { viewModel.selectedManualEt = it },
                            valueRange = 0.05f..0.95f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { viewModel.confirmCalibrationAndEvaluate(context, processor) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Confirm Calibration & Start Test")
                        }
                    }
                }
            }
        }

        // 3. EVALUATION / LIVE FEED STATE
        else {
            Card(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (viewModel.livePreviewBitmap != null) {
                        Image(bitmap = viewModel.livePreviewBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                            Text("Loading Video Frames...", color = Color.White, fontSize = 14.sp)
                        }
                    }

                    Column(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.65f)).padding(8.dp).align(Alignment.TopCenter)) {
                        Text(text = "Now Testing: ${viewModel.currentProcessingVideo}", color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))

                        LinearProgressIndicator(
                            progress = { viewModel.videoProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .padding(vertical = 4.dp),
                            color = Color.Red,
                            trackColor = Color.LightGray
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val ear = viewModel.livePreviewFeedback?.ear ?: 0f
                            Text(text = String.format("EAR: %.2f", ear), color = if (ear < viewModel.calibratedEarThresh && ear > 0f) Color.Red else Color.Green, fontSize = 13.sp)
                            val yaw = viewModel.livePreviewFeedback?.yaw ?: 0f
                            val pitch = viewModel.livePreviewFeedback?.pitch ?: 0f
                            val roll = viewModel.livePreviewFeedback?.roll ?: 0f
                            Text(text = String.format("Yaw: %.1f° | Pitch: %.1f° | Roll: %.1f°", yaw, pitch, roll), color = Color.Yellow, fontSize = 11.sp)
                        }
                    }

                    viewModel.livePreviewFeedback?.let { fb ->
                        if (fb.isDrowsyConfirmed) {
                            Box(modifier = Modifier.fillMaxWidth().background(Color.Red.copy(alpha = 0.85f)).align(Alignment.BottomCenter).padding(6.dp), contentAlignment = Alignment.Center) {
                                Text(text = "⚠️ SLEEPY CONFIRMED ⚠️", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Live Metrics ($totalTested videos processed)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Settings -> EAR Thresh: ${String.format("%.2f", viewModel.calibratedEarThresh)} | YOLO Conf: ${String.format("%.2f", viewModel.calibratedConfThresh)}",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Accuracy: ${String.format("%.1f%%", liveAccuracy * 100)}")
                        Text("Precision: ${String.format("%.2f", livePrecision)}")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Recall: ${String.format("%.2f", liveRecall)}")
                        Text("F1-Score: ${String.format("%.2f", liveF1)}")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("TP: ${viewModel.liveTp} | FP: ${viewModel.liveFp} | TN: ${viewModel.liveTn} | FN: ${viewModel.liveFn}", fontSize = 12.sp, color = Color.DarkGray)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Live Results Feed:", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(viewModel.liveResults) { res ->
                    val isCorrect = (res.groundTruth == "sleepy" && res.predicted) || (res.groundTruth == "awake" && !res.predicted)
                    Card(colors = CardDefaults.cardColors(containerColor = if (isCorrect) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = res.videoName, fontSize = 13.sp)
                            Text(text = "True: ${res.groundTruth} | Pred: ${if (res.predicted) "Sleepy" else "Awake"}", fontSize = 13.sp, color = if (isCorrect) Color(0xFF2E7D32) else Color(0xFFC62828))
                        }
                    }
                }
            }
        }

        if (viewModel.reportFilePath.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Report generated: ${viewModel.reportFilePath}",
                fontSize = 11.sp,
                color = Color.DarkGray
            )
        }
    }
}