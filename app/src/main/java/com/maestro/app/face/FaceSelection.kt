package com.maestro.app.face

/** One face as the detector reported it, without any Android types. */
data class DetectedFace(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val smiling: Float?
) {
    val area: Long get() = (right - left).toLong().coerceAtLeast(0) * (bottom - top).toLong().coerceAtLeast(0)
}

/**
 * Picks the person reading the music out of whatever the camera sees, and says whether this
 * frame can be trusted.
 *
 * The reader is the largest face (the nearest to the device). The frame is marked unusable when
 *  - another face is nearly as large (someone leaning in, or two players sharing a stand): which
 *    one is turning their head can't be told, and the choice could flip from frame to frame;
 *  - the reader's face is cut off by the edge of the frame: the head-pose and eye estimates of a
 *    partial face are unreliable.
 */
object FaceSelection {
    /** How far outside the frame (as a fraction of its size) a face's box may reach before it counts as cut off. */
    private const val EDGE_TOLERANCE = 0.02f

    fun signalsFor(
        faces: List<DetectedFace>,
        frameWidth: Int,
        frameHeight: Int,
        protection: MisfireProtection
    ): FaceSignals {
        val reader = faces.maxByOrNull { it.area } ?: return FaceSignals(present = false)
        val rivalled = faces.any { it !== reader && it.area >= reader.area * protection.rivalFaceRatio }
        val mx = frameWidth * EDGE_TOLERANCE
        val my = frameHeight * EDGE_TOLERANCE
        val cutOff = reader.left < -mx || reader.top < -my || reader.right > frameWidth + mx || reader.bottom > frameHeight + my
        return FaceSignals(
            present = true,
            yaw = reader.yaw,
            pitch = reader.pitch,
            roll = reader.roll,
            leftEyeOpen = reader.leftEyeOpen ?: -1f,
            rightEyeOpen = reader.rightEyeOpen ?: -1f,
            smiling = reader.smiling ?: -1f,
            usable = !rivalled && !cutOff
        )
    }
}
