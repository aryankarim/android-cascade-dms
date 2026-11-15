package com.example.driver_drowziness.declarations

import android.graphics.Bitmap

/**
 * Container class holding shared UI states, evaluation result structures, and calibration data classes.
 */
class Declarations {

    /**
     * Represents the outcome of an individual evaluated video test file.
     */
    data class VideoTestResult(
        val videoName: String,
        val groundTruth: String,
        val predicted: Boolean
    )

    /**
     * Represents an extracted calibration frame bitmap and its computed threshold value.
     */
    data class CalibrationFrame(
        val bitmap: Bitmap,
        val earThreshold: Float
    )

    /**
     * Workflow states for the video dataset testing and calibration process.
     */
    enum class TestingWorkflowState {
        IDLE,
        SELECTING_VIDEO,
        SELECTING_FRAME,
        EVALUATING
    }
}

data class DetailedVideoResult(
    val videoName: String,
    val actualLabel: String,         // "sleepy" or "awake"
    val predictedValue: Boolean,     // true = sleepy, false = awake
    val triggerTimestampSec: Float?  // Timestamp in seconds when drowsiness was triggered (null if awake/never triggered)
)