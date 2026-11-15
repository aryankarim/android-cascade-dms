package com.example.driver_drowziness.declarations

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.driver_drowziness.hooks.DetectionFeedback
import com.example.driver_drowziness.hooks.DrowsinessProcessor
import com.example.driver_drowziness.hooks.TestRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * ViewModel managing UI state, zip extraction, frame-by-frame video calibration, and evaluation metrics.
 */
class VideoTestingViewModel : ViewModel() {

    // UI States
    var workflowState by mutableStateOf(Declarations.TestingWorkflowState.IDLE)
        private set
    var progressStatus by mutableStateOf("Select a .zip file containing 'sleepy' and 'awake' folders.")
        private set
    var availableVideos = mutableStateOf<List<File>>(emptyList())
        private set
    var calibrationFrames = mutableStateOf<List<Declarations.CalibrationFrame>>(emptyList())
        private set
    var selectedFrame by mutableStateOf<Declarations.CalibrationFrame?>(null)
    var selectedManualEt by mutableFloatStateOf(0.25f)

    // Live Evaluation States
    var livePreviewBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var livePreviewFeedback by mutableStateOf<DetectionFeedback?>(null)
        private set
    var currentProcessingVideo by mutableStateOf("")
        private set
    val liveResults = mutableStateListOf<Declarations.VideoTestResult>()

    var liveTp by mutableIntStateOf(0)
    var liveFp by mutableIntStateOf(0)
    var liveTn by mutableIntStateOf(0)
    var liveFn by mutableIntStateOf(0)

    var calibratedEarThresh by mutableFloatStateOf(0f)
        private set
    var calibratedConfThresh by mutableFloatStateOf(0f)
        private set

    var videoProgress by mutableFloatStateOf(0f)
        private set

    private val testRecorder = TestRecorder()
    var reportFilePath by mutableStateOf("")
        private set

    private var extractedDatasetDir: File? = null

    /**
     * Extracts an uploaded dataset ZIP file and collects available MP4 files.
     */
    fun handleZipSelection(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            progressStatus = "Extracting zip file..."
            val extractedDir = unzipFile(context, uri)

            if (extractedDir != null) {
                extractedDatasetDir = extractedDir
                val videoFiles = mutableListOf<File>()
                extractedDir.listFiles { file -> file.isDirectory }?.forEach { folder ->
                    folder.listFiles { f -> f.extension.lowercase() == "mp4" }?.let { vids ->
                        videoFiles.addAll(vids)
                    }
                }
                availableVideos.value = videoFiles
                if (availableVideos.value.isNotEmpty()) {
                    workflowState = Declarations.TestingWorkflowState.SELECTING_VIDEO
                    progressStatus = "Select a video to extract calibration frames from."
                } else {
                    progressStatus = "No MP4 videos found in zip subfolders."
                    workflowState = Declarations.TestingWorkflowState.IDLE
                }
            } else {
                progressStatus = "Failed to extract zip file."
                workflowState = Declarations.TestingWorkflowState.IDLE
            }
        }
    }

    /**
     * Extracts frame snapshots across a sample video to allow manual threshold selection.
     */
    fun loadCalibrationFrames(processor: DrowsinessProcessor, videoFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            progressStatus = "Extracting and analyzing frames from ${videoFile.name}..."
            workflowState = Declarations.TestingWorkflowState.IDLE
            val frames = mutableListOf<Declarations.CalibrationFrame>()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var timeUs = 0L
                while (timeUs < durationMs * 1000L) {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { bmp ->
                        val calculatedEt = processor.calibrateEARThresholdFromBitmap(bmp) ?: DefaultParameters.DEFAULT_EAR_THRESH
                        frames.add(Declarations.CalibrationFrame(bmp, calculatedEt))
                    }
                    timeUs += 1_000_000L
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }

            calibrationFrames.value = frames

            if (frames.isNotEmpty()) {
                selectedFrame = frames.first()
                selectedManualEt = frames.first().earThreshold
            }

            workflowState = Declarations.TestingWorkflowState.SELECTING_FRAME
            progressStatus = "Select a frame and adjust manual threshold if desired."
        }
    }

    /**
     * Confirms chosen calibration parameters and triggers the automated dataset evaluation run.
     */
    fun confirmCalibrationAndEvaluate(context: Context, processor: DrowsinessProcessor) {
        val frame = selectedFrame ?: return
        val rootDir = extractedDatasetDir ?: return

        viewModelScope.launch(Dispatchers.IO) {
            progressStatus = "Calibrating using manual override..."
            val calibrated = processor.calibrateFromBitmap(frame.bitmap)
            if (calibrated) {
                val currentConf = processor.getDefaultValues().confThreshold
                processor.updateThresholds(selectedManualEt, currentConf)

                // Consists UI on VideoTestingScreen
                val defaults = processor.getDefaultValues()
                calibratedEarThresh = defaults.earThreshold
                calibratedConfThresh = defaults.confThreshold

                progressStatus = "Calibration successful! Starting evaluation..."
                workflowState = Declarations.TestingWorkflowState.EVALUATING

                runLiveEvaluation(context, rootDir, processor)
            } else {
                progressStatus = "No face detected in this frame! Choose another video."
            }
        }
    }

    /**
     * Iterates through extracted videos frame-by-frame and calculates classification metrics.
     */
    private suspend fun runLiveEvaluation(context: Context, rootFolder: File, processor: DrowsinessProcessor) = withContext(Dispatchers.IO) {
        testRecorder.clear()
        val folders = rootFolder.listFiles { file -> file.isDirectory } ?: emptyArray()

        for (folder in folders) {
            val isSleepy = folder.name.lowercase().contains("sleepy")
            val isAwake = folder.name.lowercase().contains("awake")
            if (!isSleepy && !isAwake) continue

            val videoFiles = folder.listFiles { file -> file.extension.lowercase() == "mp4" } ?: emptyArray()
            for (videoFile in videoFiles) {
                withContext(Dispatchers.Main) {
                    currentProcessingVideo = videoFile.name
                    videoProgress = 0f
                }
                processor.resetState()

                var videoDetectedDrowsy = false
                var triggerTimestampSec: Float? = null
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(videoFile.absolutePath)
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
                    var timeUs = 0L

                    while (timeUs < durationMs * 1000L) {
                        val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (bitmap != null) {
                            var currentFeedback: DetectionFeedback? = null
                            var frameDrowsy = false
                            processor.processBitmap(bitmap, 0) { feedback ->
                                currentFeedback = feedback
                                if (feedback.isDrowsyConfirmed) frameDrowsy = true
                            }

                            currentFeedback?.let { fb ->
                                withContext(Dispatchers.Main) {
                                    livePreviewBitmap = bitmap
                                    livePreviewFeedback = fb
                                    videoProgress = (timeUs / (durationMs * 1000f)).coerceIn(0f, 1f)
                                }
                            }

                            if (frameDrowsy) {
                                videoDetectedDrowsy = true
                                triggerTimestampSec = timeUs / 1_000_000f
                                break
                            }
                        }
                        timeUs += DefaultParameters.EVALUATION_FRAME_STEP_US
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    retriever.release()
                }

                val truthLabel = if (isSleepy) "sleepy" else "awake"

                // Record result for report generation
                testRecorder.addResult(videoFile.name, truthLabel, videoDetectedDrowsy, triggerTimestampSec)

                withContext(Dispatchers.Main) {
                    videoProgress = 1.0f
                    liveResults.add(Declarations.VideoTestResult(videoFile.name, truthLabel, videoDetectedDrowsy))
                    if (isSleepy) {
                        if (videoDetectedDrowsy) liveTp++ else liveFn++
                    } else {
                        if (videoDetectedDrowsy) liveFp++ else liveTn++
                    }
                }
            }
        }

        // Generate report.txt after all files are processed
        val generatedFile = testRecorder.generateReport(context)
        withContext(Dispatchers.Main) {
            reportFilePath = generatedFile?.absolutePath ?: "Failed to generate report"
            progressStatus = "Evaluation Complete! Report saved to app storage."
        }
    }

    /**
     * Unzips dataset archives into app cache directory.
     */
    private fun unzipFile(context: Context, zipUri: Uri): File? {
        val outputDir = File(context.cacheDir, "video_dataset")
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()
        try {
            // Unzips using InputStream and ZipInputStream
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val newFile = File(outputDir, entry.name)
                        if (entry.isDirectory) newFile.mkdirs()
                        else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                val buffer = ByteArray(1024)
                                var bytesRead: Int
                                while (zip.read(buffer).also { bytesRead = it } > 0) fos.write(buffer, 0, bytesRead)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            return outputDir
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}