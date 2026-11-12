package com.maestro.app.face

import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * CameraX analyzer that runs on-device ML Kit face detection on each frame,
 * distils the result into [FaceSignals] (see [FaceSelection]), and feeds them to a [GestureEngine].
 *
 * ML Kit's success listeners dispatch on the main thread, so [onResult] is always
 * invoked on the main thread — safe to update UI state / turn pages directly.
 */
class FaceAnalyzer(
    private val configProvider: () -> GestureConfig,
    private val onResult: (status: EngineStatus, fired: PageAction?, signals: FaceSignals) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.2f)
            .build()
    )

    private val engine = GestureEngine()

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val sideways = rotation == 90 || rotation == 270
        val frameWidth = if (sideways) imageProxy.height else imageProxy.width
        val frameHeight = if (sideways) imageProxy.width else imageProxy.height
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val config = configProvider()
                val signals = FaceSelection.signalsFor(faces.map(::detected), frameWidth, frameHeight, config.protection)
                val out = engine.onFrame(signals, config, SystemClock.elapsedRealtime())
                onResult(out.status, out.fired, signals)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun detected(f: Face) = DetectedFace(
        left = f.boundingBox.left,
        top = f.boundingBox.top,
        right = f.boundingBox.right,
        bottom = f.boundingBox.bottom,
        yaw = f.headEulerAngleY,
        pitch = f.headEulerAngleX,
        roll = f.headEulerAngleZ,
        leftEyeOpen = f.leftEyeOpenProbability,
        rightEyeOpen = f.rightEyeOpenProbability,
        smiling = f.smilingProbability
    )

    fun close() {
        detector.close()
    }
}
