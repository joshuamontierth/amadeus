package com.maestro.app.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceSelectionTest {

    private val balanced = MisfireProtection.BALANCED

    /** A square face with its top-left corner at ([x], [y]). */
    private fun face(x: Int, y: Int, size: Int, yaw: Float = 0f) = DetectedFace(
        left = x, top = y, right = x + size, bottom = y + size,
        yaw = yaw, pitch = 0f, roll = 0f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f, smiling = null
    )

    private fun signals(vararg faces: DetectedFace) = FaceSelection.signalsFor(faces.toList(), 480, 640, balanced)

    @Test
    fun noFaces_isNoFace() {
        assertFalse(signals().present)
    }

    @Test
    fun aLoneFace_isUsable_andCarriesItsMeasurements() {
        val s = signals(face(100, 200, 200, yaw = 18f))
        assertTrue(s.present && s.usable)
        assertEquals(18f, s.yaw, 0f)
        assertEquals(0.9f, s.leftEyeOpen, 0f)
        assertEquals(-1f, s.smiling, 0f) // not classified
    }

    @Test
    fun theLargestFaceIsTheReader_andASmallerOneBehindIsIgnored() {
        val s = signals(face(20, 20, 60, yaw = -25f), face(120, 200, 220, yaw = 4f))
        assertTrue(s.usable)
        assertEquals(4f, s.yaw, 0f)
    }

    @Test
    fun anotherFaceNearlyAsLarge_makesTheFrameUnusable() {
        val s = signals(face(20, 100, 190, yaw = -25f), face(250, 100, 200, yaw = 4f))
        assertTrue(s.present)
        assertFalse(s.usable)
    }

    @Test
    fun theRivalThresholdFollowsTheProtectionLevel() {
        fun usable(faces: List<DetectedFace>, p: MisfireProtection) = FaceSelection.signalsFor(faces, 480, 640, p).usable
        val half = listOf(face(20, 100, 150), face(250, 100, 200)) // the second face is 56% of the first's area
        assertTrue(usable(half, MisfireProtection.RELAXED))
        assertFalse(usable(half, MisfireProtection.BALANCED))
        assertFalse(usable(half, MisfireProtection.STRICT))
        val farther = listOf(face(20, 100, 120), face(250, 100, 200)) // 36%
        assertTrue(usable(farther, MisfireProtection.RELAXED))
        assertTrue(usable(farther, MisfireProtection.BALANCED))
        assertFalse(usable(farther, MisfireProtection.STRICT))
    }

    @Test
    fun aFaceCutOffByTheFrameEdge_isUnusable() {
        assertFalse(signals(face(-120, 200, 220)).usable)
        assertFalse(signals(face(300, 200, 220)).usable)
        assertFalse(signals(face(100, 500, 220)).usable)
    }

    @Test
    fun aFaceJustTouchingTheEdge_isStillUsable() {
        assertTrue(signals(face(-5, 200, 220)).usable)
    }
}
