package com.example.driver_drowziness.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.driver_drowziness.declarations.DefaultParameters
import com.example.driver_drowziness.declarations.DetailedVideoResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles recording video evaluation metrics and formatting/saving performance evaluation reports to disk.
 */
class TestRecorder {
    private val results = mutableListOf<DetailedVideoResult>()

    /**
     * Clears all stored video test results.
     */
    fun clear() {
        results.clear()
    }

    /**
     * Adds an individual video test result entry to the evaluation batch.
     */
    fun addResult(videoName: String, actualLabel: String, predictedValue: Boolean, triggerTimestampSec: Float?) {
        results.add(DetailedVideoResult(videoName, actualLabel, predictedValue, triggerTimestampSec))
    }

    /**
     * Generates a comprehensive performance report TXT file containing confusion matrix statistics and logs.
     */
    @SuppressLint("DefaultLocale")
    fun generateReport(context: Context): File? {
        try {
            // Saves report in app's external/internal files directory
            val reportFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "report.txt")

            var tp = 0
            var fp = 0
            var tn = 0
            var fn = 0

            val misclassifiedFiles = mutableListOf<DetailedVideoResult>()

            for (res in results) {
                val isSleepyActual = res.actualLabel.lowercase() == "sleepy"
                val isSleepyPred = res.predictedValue

                if (isSleepyActual) {
                    if (isSleepyPred) tp++ else fn++
                } else {
                    if (isSleepyPred) fp++ else tn++
                }

                val isCorrect = (isSleepyActual && isSleepyPred) || (!isSleepyActual && !isSleepyPred)
                if (!isCorrect) {
                    misclassifiedFiles.add(res)
                }
            }

            val total = results.size
            val accuracy = if (total > 0) (tp + tn).toFloat() / total else 0f
            val precision = if ((tp + fp) > 0) tp.toFloat() / (tp + fp) else 0f
            val recall = if ((tp + fn) > 0) tp.toFloat() / (tp + fn) else 0f
            val f1Score = if ((precision + recall) > 0f) 2f * (precision * recall) / (precision + recall) else 0f
            val specificity = if ((tn + fp) > 0) tn.toFloat() / (tn + fp) else 0f
            val falsePositiveRate = if ((fp + tn) > 0) fp.toFloat() / (fp + tn) else 0f
            val falseNegativeRate = if ((fn + tp) > 0) fn.toFloat() / (fn + tp) else 0f

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timestampStr = dateFormat.format(Date())

            val sb = StringBuilder().apply {
                append("====================================================================================\n")
                append("                     DRIVER DROWSINESS TEST & EVALUATION REPORT                     \n")
                append("====================================================================================\n")
                append("Generated On: $timestampStr\n")
                append("Total Videos Evaluated: $total\n\n")

                // 0. Declarations / Parameters Table (All fields included)
                append("------------------------------------------------------------------------------------\n")
                append("0. EVALUATION CONFIGURATION & DECLARATIONS\n")
                append("------------------------------------------------------------------------------------\n")
                append(String.format("%-45s | %s\n", "Parameter", "Value"))
                append("------------------------------------------------------------------------------------\n")
                append(String.format("%-45s | %.4f\n", "Default EAR Threshold", DefaultParameters.DEFAULT_EAR_THRESH))
                append(String.format("%-45s | %.4f\n", "Default Confidence Threshold", DefaultParameters.DEFAULT_CONF_THRESHOLD))
                append(String.format("%-45s | %d\n", "MediaPipe Consecutive Frames", DefaultParameters.MEDIAPIPE_CONS_FRAMES))
                append(String.format("%-45s | %d ms\n", "Sustained Drowsy Duration", DefaultParameters.CASCADE_SUSTAINED_DROWSY_DURATION_MS))
                append(String.format("%-45s | %d ms\n", "YOLO Cooldown", DefaultParameters.YOLO_COOLDOWN_MS))
                append(String.format("%-45s | %d ms\n", "Max Missing Time", DefaultParameters.MAX_MISSING_TIME_MS))
                append(String.format("%-45s | Yaw: %.1f°, Pitch: %.1f°, Roll: %.1f°\n", "Head Orientation Thresholds",
                    DefaultParameters.HEAD_ORIENTATION_THRESHOLDS["yaw"] ?: 0.0,
                    DefaultParameters.HEAD_ORIENTATION_THRESHOLDS["pitch"] ?: 0.0,
                    DefaultParameters.HEAD_ORIENTATION_THRESHOLDS["roll"] ?: 0.0
                ))
                append(String.format("%-45s | %d x %d\n", "Model Input Resolution", DefaultParameters.MODEL_INPUT_WIDTH, DefaultParameters.MODEL_INPUT_HEIGHT))
                append(String.format("%-45s | %d us\n", "Evaluation Frame Step", DefaultParameters.EVALUATION_FRAME_STEP_US))
                append("\n")

                append("------------------------------------------------------------------------------------\n")
                append("1. DETAILED RESULTS PER VIDEO\n")
                append("------------------------------------------------------------------------------------\n")
                append(String.format("%-45s | %-10s | %-10s | %s\n", "Video Name (with Parent Folder)", "Actual", "Predicted", "Trigger Time (s)"))
                append("------------------------------------------------------------------------------------\n")
                if (results.isEmpty()) {
                    append("No videos processed.\n")
                } else {
                    for (res in results) {
                        val displayPath = if (res.videoName.contains("/")) {
                            res.videoName
                        } else {
                            val fileObj = File(res.videoName)
                            val parent = fileObj.parentFile?.name ?: ""
                            if (parent.isNotEmpty()) "$parent/${fileObj.name}" else fileObj.name
                        }

                        val predStr = if (res.predictedValue) "Sleepy" else "Awake"
                        val timeStr = if (res.triggerTimestampSec != null) String.format("%.2fs", res.triggerTimestampSec) else "N/A"
                        append(String.format("%-45s | %-10s | %-10s | %s\n", displayPath, res.actualLabel, predStr, timeStr))
                    }
                }
                append("\n")

                append("------------------------------------------------------------------------------------\n")
                append("2. CONFUSION MATRIX & DETAILED EXPLANATIONS\n")
                append("------------------------------------------------------------------------------------\n")
                append(String.format("True Positives  (TP): %d\n", tp))
                append("  -> Explanation: Videos truly labeled as 'Sleepy' and correctly predicted as 'Sleepy'.\n\n")
                append(String.format("False Positives (FP): %d\n", fp))
                append("  -> Explanation: Videos truly labeled as 'Awake' but incorrectly predicted as 'Sleepy' (Type I Error).\n\n")
                append(String.format("True Negatives  (TN): %d\n", tn))
                append("  -> Explanation: Videos truly labeled as 'Awake' and correctly predicted as 'Awake'.\n\n")
                append(String.format("False Negatives (FN): %d\n", fn))
                append("  -> Explanation: Videos truly labeled as 'Sleepy' but incorrectly predicted as 'Awake' (Type II Error).\n\n")

                append("------------------------------------------------------------------------------------\n")
                append("3. PERFORMANCE STATISTICS & METRICS (PAPER-READY)\n")
                append("------------------------------------------------------------------------------------\n")
                append(String.format("%-45s | %s\n", "Metric", "Value"))
                append("------------------------------------------------------------------------------------\n")
                append(String.format("%-45s | %.2f%%\n", "Accuracy", accuracy * 100))
                append(String.format("%-45s | %.4f\n", "Precision (Positive Predictive Value)", precision))
                append(String.format("%-45s | %.4f\n", "Recall / Sensitivity (True Positive Rate)", recall))
                append(String.format("%-45s | %.4f\n", "F1-Score", f1Score))
                append(String.format("%-45s | %.4f\n", "Specificity (True Negative Rate)", specificity))
                append(String.format("%-45s | %.4f\n", "False Positive Rate (FPR)", falsePositiveRate))
                append(String.format("%-45s | %.4f\n", "False Negative Rate (FNR)", falseNegativeRate))
                append("\n")

                append("------------------------------------------------------------------------------------\n")
                append("4. MISCLASSIFIED (FALSELY PREDICTED) FILES\n")
                append("------------------------------------------------------------------------------------\n")
                if (misclassifiedFiles.isEmpty()) {
                    append("None! All test videos were classified correctly.\n")
                } else {
                    append(String.format("%-45s | %-12s | %s\n", "File Name (with Parent Folder)", "Actual", "Predicted"))
                    append("------------------------------------------------------------------------------------\n")
                    for (res in misclassifiedFiles) {
                        val displayPath = if (res.videoName.contains("/")) {
                            res.videoName
                        } else {
                            val fileObj = File(res.videoName)
                            val parent = fileObj.parentFile?.name ?: ""
                            if (parent.isNotEmpty()) "$parent/${fileObj.name}" else fileObj.name
                        }

                        val predStr = if (res.predictedValue) "Sleepy" else "Awake"
                        append(String.format("%-45s | %-12s | %s\n", displayPath, res.actualLabel, predStr))
                    }
                }
                append("====================================================================================\n")
            }

            FileOutputStream(reportFile).use { fos ->
                fos.write(sb.toString().toByteArray())
            }

            Log.d("TestRecorder", "Report successfully generated at: ${reportFile.absolutePath}")
            return reportFile
        } catch (e: Exception) {
            Log.e("TestRecorder", "Error generating report: ${e.message}", e)
            return null
        }
    }
}