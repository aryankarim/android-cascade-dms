package com.example.driver_drowziness.declarations


object DefaultParameters {
    // EAR and ONNX Confidence Thresholds
    const val DEFAULT_EAR_THRESH: Float = 0.30f
    const val DEFAULT_CONF_THRESHOLD: Float = 0.85f

    // Frame & Time Durations (in milliseconds or frame counts)
    const val MEDIAPIPE_CONS_FRAMES: Int = 10
    const val CASCADE_SUSTAINED_DROWSY_DURATION_MS: Long = 1500L
    const val YOLO_COOLDOWN_MS: Long = 1500L
    const val MAX_MISSING_TIME_MS: Long = 2000L

    // Head Orientation Thresholds (in degrees)
    val HEAD_ORIENTATION_THRESHOLDS = mapOf(
        "yaw" to 20.0,
        "pitch" to 20.0,
        "roll" to 20.0
    )

    // Image Input Processing Constants
    const val MODEL_INPUT_WIDTH: Int = 640
    const val MODEL_INPUT_HEIGHT: Int = 640

    // Video Evaluation Frame Interval (in microseconds)
    const val EVALUATION_FRAME_STEP_US: Long = 100_000L
}