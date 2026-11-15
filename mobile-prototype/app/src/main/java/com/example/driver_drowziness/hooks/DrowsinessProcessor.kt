package com.example.driver_drowziness.hooks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import androidx.core.graphics.scale
import com.example.driver_drowziness.declarations.DefaultParameters

/**
 * Data class representing real-time feedback values for facial landmarks, EAR, head pose, and drowsiness status.
 */
data class DetectionFeedback(
    val ear: Float,
    val isDrowsyConfirmed: Boolean,
    val leftEyePoints: List<Pair<Float, Float>>,
    val rightEyePoints: List<Pair<Float, Float>>,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val centerPoint: Pair<Float, Float>? = null,
    val rawLandmarks: List<NormalizedLandmark>? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

/**
 * Core processor handling MediaPipe face landmark detection and ONNX YOLO model inference for drowsiness checks.
 */
class DrowsinessProcessor(private val context: Context) {

    // STATES
    private var faceLandmarker: FaceLandmarker? = null
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private var isChannelFirstLayout: Boolean = true

    private var earThresh = 0.30f
    private var confThreshold = 0.85f

    private var counter = 0

    private var headOrientationIsRisky = false

    // BASELINE CALIBRATION ORIENTATIONS
    private var baseYaw = 0f
    private var basePitch = 0f
    private var baseRoll = 0f
    private var isCalibrated = false

    private val headOrientationThresholds = DefaultParameters.HEAD_ORIENTATION_THRESHOLDS

    private val earThresholdIncrease = 0.04f

    private var lastFaceDetectedTime = System.currentTimeMillis()

    private val leftEyeIndices = intArrayOf(362, 385, 387, 263, 373, 380)
    private val rightEyeIndices = intArrayOf(33, 160, 158, 133, 153, 144)

    init {
        setupMediaPipe()
        setupOnnxRuntime()
    }

    /**
     * Loads and configures the MediaPipe Face Landmarker tracking model from assets.
     */
    private fun setupMediaPipe() {
        try {
            val baseOptions = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinFaceDetectionConfidence(0.5f)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.d("MediaPipeSetup", "MediaPipe successfully initialized.")
        } catch (e: Exception) {
            Log.e("MediaPipeSetup", "MediaPipe initialization failed: ${e.message}")
        }
    }

    /**
     * Dynamically updates detection sensitivity thresholds based on UI sliders.
     */
    fun updateThresholds(newEar: Float, newConf: Float) {
        this.earThresh = newEar
        this.confThreshold = newConf
        Log.d("UpdateThresholds", "Thresholds updated -> EAR: $earThresh | YOLO Conf: $confThreshold")
    }

    /**
     * Loads the custom YOLO ONNX neural network model from assets.
     */
    private fun setupOnnxRuntime() {
        try {
            val modelBytes = context.assets.open("scaled_model_int8_6.onnx").readBytes()
            ortSession = ortEnv.createSession(modelBytes)
            Log.d("UpdateThresholds", "INT8 YOLO ONNX Runtime successfully initialized.")
        } catch (e: Exception) {
            Log.e("UpdateThresholds", "YOLO ONNX Runtime initialization failed: ${e.message}")
        }
    }

    /**
     * Calibrates baseline head orientation using facial landmarks.
     */
    fun calibrateHeadOrientation(landmarks: List<NormalizedLandmark>) {
        val nose = landmarks[1]
        val chin = landmarks[152]
        val leftEye = landmarks[33]
        val rightEye = landmarks[263]

        baseRoll = Math.toDegrees(
            atan2((rightEye.x() - leftEye.x()).toDouble(), (rightEye.y() - leftEye.y()).toDouble())
        ).toFloat()

        basePitch = Math.toDegrees(
            atan2((chin.z() - nose.z()).toDouble(), (chin.y() - nose.y()).toDouble())
        ).toFloat()

        baseYaw = Math.toDegrees(
            atan2((((leftEye.x() + rightEye.x()) / 2f)).toDouble() - nose.x(), (((leftEye.z() + rightEye.z()) / 2f)).toDouble() - nose.z())
        ).toFloat()

        isCalibrated = true
        Log.d("BaseCalibration", "Head orientation calibrated: Yaw=$baseYaw, Pitch=$basePitch, Roll=$baseRoll")
    }

    /**
     * Calibrates default EAR and confidence threshold limits.
     */
    fun calibrateEARandCONF(landmarks: List<NormalizedLandmark>) {
        val leftEar = calculateEAR(landmarks, leftEyeIndices)
        val rightEar = calculateEAR(landmarks, rightEyeIndices)
        val avgEar = (leftEar + rightEar) / 2.0f

        this.updateThresholds((avgEar - earThresholdIncrease), this.confThreshold)

        Log.d("DefaultValuesCalibration", "Default Values Calibrated: Ear Threshold = ${this.earThresh}, ONNX Confidence = ${this.confThreshold}")
    }

    data class DefaultValues(
        val earThreshold: Float,
        val confThreshold: Float
    )

    fun getDefaultValues(): DefaultValues {
        return DefaultValues(
            earThreshold = earThresh,
            confThreshold = confThreshold
        )
    }

    /**
     * Calculates the Eye Aspect Ratio (EAR) using Euclidean distances across landmarks.
     */
    private fun calculateEAR(landmarks: List<NormalizedLandmark>, indices: IntArray): Float {
        val pts = indices.map { landmarks[it] }
        val v1 = hypot(pts[1].x() - pts[5].x(), pts[1].y() - pts[5].y())
        val v2 = hypot(pts[2].x() - pts[4].x(), pts[2].y() - pts[4].y())
        val hDist = hypot(pts[0].x()     - pts[3].x(), pts[0].y() - pts[3].y())
        return if (hDist > 0.001f) (v1 + v2) / (2.0f * hDist) else 0.0f
    }

    private var drowsyStartTime: Long? = null


    /**
     * Runs inference validation on a bitmap using the ONNX model.
     */
    private fun verifyWithYOLO(bitmap: Bitmap): Boolean {
        val session = ortSession ?: run {
            Log.e("VerifyWithYOLO", "[ERROR] ortSession is NULL! Exiting function.")
            return false
        }

        var inputTensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null

        try {
            val resizedBitmap = bitmap.scale(DefaultParameters.MODEL_INPUT_WIDTH, DefaultParameters.MODEL_INPUT_HEIGHT)
            val inputName = session.inputNames.iterator().next()

            val floatBuffer = convertBitmapToByteBufferFloat32(resizedBitmap)
            inputTensor = OnnxTensor.createTensor(
                ortEnv, floatBuffer, longArrayOf(1, 3, DefaultParameters.MODEL_INPUT_WIDTH.toLong(), DefaultParameters.MODEL_INPUT_HEIGHT.toLong())
            )

            outputs = session.run(mapOf(inputName to inputTensor))

            val outputTensor = outputs[0] as OnnxTensor
            val outputType = outputTensor.info.type

            if (isChannelFirstLayout == null && outputTensor.info is ai.onnxruntime.TensorInfo) {
                val shape = (outputTensor.info as ai.onnxruntime.TensorInfo).shape
                // Shape is usually [1, 6, 8400] for channel-first or [1, 8400, 6] for channel-second
                if (shape.size >= 2) {
                    isChannelFirstLayout = (shape[1] == 6L)
                    Log.d("ParseYOLO", "Detected tensor layout -> Is Channel First: $isChannelFirstLayout")
                }
            }

            val floatArray: FloatArray = when (outputType) {
                OnnxJavaType.FLOAT -> {
                    val buffer = outputTensor.floatBuffer
                    buffer.rewind()
                    FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
                else -> return false
            }

            val isCurrentFrameDrowsy = parseYoloOutputArray(floatArray)
            val currentTime = System.currentTimeMillis()

            val isSustainedDrowsy = synchronized(this) {
                if (isCurrentFrameDrowsy) {
                    if (drowsyStartTime == null)
                        drowsyStartTime = currentTime

                    val continuousDrowsyDuration = currentTime - (drowsyStartTime ?: currentTime)
                    continuousDrowsyDuration >= DefaultParameters.CASCADE_SUSTAINED_DROWSY_DURATION_MS
                } else {
                    drowsyStartTime = null
                    false
                }
            }

            return isSustainedDrowsy

        } catch (e: Exception) {
            Log.e("VerifyWithYOLO", "[EXCEPTION] Error inside verifyWithYOLO: ${e.message}", e)
            return false
        } finally {
            inputTensor?.close()
            outputs?.close()
        }
    }

    /**
     * Converts a Bitmap into a normalized Float32 buffer for YOLO input. (Google Gemini was used to generate this function)
     */
    private fun convertBitmapToByteBufferFloat32(bitmap: Bitmap): FloatBuffer {
        val width = DefaultParameters.MODEL_INPUT_WIDTH
        val height = DefaultParameters.MODEL_INPUT_HEIGHT
        val imageSize = width * height

        val floatBuffer = FloatBuffer.allocate(1 * 3 * width * height)
        val intValues = IntArray(imageSize)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        // RED
        for (i in 0 until imageSize)
            floatBuffer.put(i, (((intValues[i] shr 16) and 0x000000FF) / 255.0f))
        // GREEN
        for (i in 0 until imageSize)
            floatBuffer.put(imageSize + i, (((intValues[i] shr 8) and 0x000000FF) / 255.0f))
        // BLUE
        for (i in 0 until imageSize)
            floatBuffer.put((imageSize * 2) + i, ((intValues[i] and 0x000000FF) / 255.0f))


        floatBuffer.rewind()
        return floatBuffer
    }

    /**
     * Parses the flat YOLO output array.
     */
    private fun parseYoloOutputArray(outputArray: FloatArray): Boolean {
        val numAnchors = 8400
        val numChannels = 6

        if (outputArray.size < numChannels * numAnchors) {
            Log.e("ParseYOLO", "Unexpected output shape size: ${outputArray.size}")
            return false
        }

        var maxSleepyScore = 0.0f
        val sleepyChannel = 5

        val getIndex: (Int) -> Int = if (isChannelFirstLayout) {
            { anchor -> (sleepyChannel * numAnchors) + anchor }
        } else {
            { anchor -> (anchor * numChannels) + sleepyChannel }
        }

        for (anchor in 0 until numAnchors) {
            val idx = getIndex(anchor)
            if (idx < outputArray.size) {
                val score = outputArray[idx]
                if (score > maxSleepyScore) {
                    maxSleepyScore = score
                }
            }
        }

        return maxSleepyScore >= confThreshold
    }

    private var lastYoloRunTime = 0L

    /**
     * Processes an individual bitmap frame and returns analysis feedback.
     */
    fun processBitmap(bitmap: Bitmap, rotationDegrees: Int, onFeedback: (DetectionFeedback) -> Unit) {
        try {
            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            val frameWidth = rotatedBitmap.width
            val frameHeight = rotatedBitmap.height

            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(rotatedBitmap).build()
            val result = faceLandmarker?.detect(mpImage)

            if (result != null && result.faceLandmarks().isNotEmpty()) {
                lastFaceDetectedTime = System.currentTimeMillis()
                val landmarks = result.faceLandmarks()[0]

                val leftEar = calculateEAR(landmarks, leftEyeIndices)
                val rightEar = calculateEAR(landmarks, rightEyeIndices)
                val avgEar = (leftEar + rightEar) / 2.0f


                val leftEyePoints = leftEyeIndices.map { Pair(landmarks[it].x(), landmarks[it].y()) }
                val rightEyePoints = rightEyeIndices.map { Pair(landmarks[it].x(), landmarks[it].y()) }

                val nose = landmarks[1]
                val chin = landmarks[152]
                val leftEye = landmarks[33]
                val rightEye = landmarks[263]

                val rawRoll = Math.toDegrees(
                    atan2((rightEye.x() - leftEye.x()).toDouble(), (rightEye.y() - leftEye.y()).toDouble())
                ).toFloat()

                val rawPitch = Math.toDegrees(
                    atan2((chin.z() - nose.z()).toDouble(), (chin.y() - nose.y()).toDouble())
                ).toFloat()

                val rawYaw = Math.toDegrees(
                    atan2((((leftEye.x() + rightEye.x()) / 2f)).toDouble() - nose.x(), (((leftEye.z() + rightEye.z()) / 2f)).toDouble() - nose.z())
                ).toFloat()

                val centerPoint = Pair(nose.x(), nose.y())

                val yawDiffSigned = if (isCalibrated) ((rawYaw) - (baseYaw)) else 0.0
                val pitchDiffSigned = if (isCalibrated) ((rawPitch) - (basePitch)) else 0.0
                val rollDiffSigned = if (isCalibrated) ((rawRoll) - (baseRoll)) else 0.0

                val yawDiff = abs(yawDiffSigned.toDouble())
                val pitchDiff = abs(pitchDiffSigned.toDouble())
                val rollDiff = abs(rollDiffSigned.toDouble())

                headOrientationIsRisky = isCalibrated && (
                        yawDiff > headOrientationThresholds["yaw"]!! ||
                                pitchDiff > headOrientationThresholds["pitch"]!! ||
                                rollDiff > headOrientationThresholds["roll"]!!
                        )

                var isDrowsyConfirmed = false

                if (avgEar < earThresh || headOrientationIsRisky) {
                    counter++
                    if (counter >= DefaultParameters.MEDIAPIPE_CONS_FRAMES) {
                        val currentTime = System.currentTimeMillis()

                        if (currentTime - lastYoloRunTime >= DefaultParameters.YOLO_COOLDOWN_MS) {
                            lastYoloRunTime = currentTime
                            val yoloSaysSleeping = verifyWithYOLO(rotatedBitmap)
                            if (yoloSaysSleeping) {
                                isDrowsyConfirmed = true
                            }
                        } else {
                            isDrowsyConfirmed = drowsyStartTime != null
                        }
                    }
                } else {
                    counter = 0
                    drowsyStartTime = null
                }

                onFeedback(
                    DetectionFeedback(
                        ear = avgEar,
                        isDrowsyConfirmed = isDrowsyConfirmed,
                        leftEyePoints = leftEyePoints,
                        rightEyePoints = rightEyePoints,
                        yaw = yawDiffSigned.toFloat(),
                        pitch = pitchDiffSigned.toFloat(),
                        roll = rollDiffSigned.toFloat(),
                        centerPoint = centerPoint,
                        rawLandmarks = landmarks,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight
                    )
                )
            } else {
                counter = 0
                val timeMissing = System.currentTimeMillis() - lastFaceDetectedTime
                val isMissingConfirmed = timeMissing >= DefaultParameters.MAX_MISSING_TIME_MS
                onFeedback(
                    DetectionFeedback(
                        ear = 0.0f,
                        isDrowsyConfirmed = isMissingConfirmed,
                        leftEyePoints = emptyList(),
                        rightEyePoints = emptyList(),
                        frameWidth = frameWidth,
                        frameHeight = frameHeight
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("ProcessBitmap", "Inference error: ${e.message}")
        }
    }

    /**
     * Processes raw camera ImageProxy frames sequentially.
     */
    fun processImageProxy(imageProxy: ImageProxy, onFeedback: (DetectionFeedback) -> Unit) {
        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()
            processBitmap(bitmap, rotationDegrees, onFeedback)
        } catch (e: Exception) {
            Log.e("ProcessImageProxy", "Inference error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    fun resetState() {
        counter = 0
        drowsyStartTime = null
        lastFaceDetectedTime = System.currentTimeMillis()
    }

    /**
     * Computes suggested EAR threshold for a given bitmap using MediaPipe.
     */
    fun calibrateEARThresholdFromBitmap(bitmap: Bitmap): Float? {
        try {
            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
            val result = faceLandmarker?.detect(mpImage)
            if (result != null && result.faceLandmarks().isNotEmpty()) {
                val landmarks = result.faceLandmarks()[0]
                val leftEar = calculateEAR(landmarks, leftEyeIndices)
                val rightEar = calculateEAR(landmarks, rightEyeIndices)
                val avgEar = (leftEar + rightEar) / 2.0f
                return avgEar - earThresholdIncrease
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Calibrates head orientation and default thresholds directly from a bitmap.
     */
    fun calibrateFromBitmap(bitmap: Bitmap): Boolean {
        try {
            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
            val result = faceLandmarker?.detect(mpImage)
            if (result != null && result.faceLandmarks().isNotEmpty()) {
                val landmarks = result.faceLandmarks()[0]
                calibrateHeadOrientation(landmarks)
                calibrateEARandCONF(landmarks)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}