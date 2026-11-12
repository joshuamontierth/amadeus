package com.maestro.app.face

/**
 * A facial gesture the app can recognise. The user maps these onto page actions
 * in Settings, so the meaning of "left"/"right" can be swapped freely to match
 * whatever feels natural with their device's front camera (mirroring varies).
 */
enum class GestureType(val label: String) {
    NONE("None (disable this direction)"),
    HEAD_LEFT("Turn head left"),
    HEAD_RIGHT("Turn head right"),
    WINK_LEFT("Wink left eye"),
    WINK_RIGHT("Wink right eye"),
    BLINK_BOTH("Long blink (both eyes)"),
    DOUBLE_BLINK("Double blink"),
    SMILE("Smile");

    companion object {
        fun fromName(name: String?): GestureType =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Which way a recognised gesture should move the document. */
enum class PageAction { NEXT, PREVIOUS }

/**
 * A single frame's worth of facial measurements distilled from ML Kit.
 * Eye-open/smile values are 0f..1f, or -1f when the detector could not classify them.
 */
data class FaceSignals(
    val present: Boolean,
    val yaw: Float = 0f,           // headEulerAngleY in degrees (+/- = turned to a side)
    val pitch: Float = 0f,         // headEulerAngleX in degrees (+/- = tilted up/down)
    val roll: Float = 0f,          // headEulerAngleZ in degrees (+/- = head tipped to a shoulder)
    val leftEyeOpen: Float = -1f,  // probability the subject's left eye is open
    val rightEyeOpen: Float = -1f,
    val smiling: Float = -1f,      // probability the subject is smiling, or -1f if unknown
    val usable: Boolean = true     // false: a face is there but this frame can't be trusted (see [FaceSelection])
)

/**
 * How hard the recogniser works to ignore movements that aren't a page-turn gesture. Stricter
 * settings accept a gesture a little later, and only while the face is squarely in view.
 */
enum class MisfireProtection(
    val label: String,
    val settleMs: Long,           // a face that just appeared must sit neutral this long before any gesture counts
    val maxTiltDeg: Float,        // head pitched this far from its resting pose (looking down at the keys) = ignored
    val maxRollDeg: Float,        // head tipped sideways this far = ignored
    val maxPitchSwingDeg: Float,  // pitch moving this far within half a second (nodding along) = ignored
    val rearmMs: Long,            // neutral for this long after a turn before the next one is allowed
    val rivalFaceRatio: Float     // another face at least this fraction of the reader's size = frame ignored
) {
    RELAXED("Relaxed", 250L, 25f, 35f, 18f, 150L, 0.6f),
    BALANCED("Balanced", 400L, 18f, 25f, 12f, 250L, 0.4f),
    STRICT("Strict", 600L, 12f, 18f, 8f, 400L, 0.25f);

    companion object {
        fun fromName(name: String?): MisfireProtection =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

/** User-tunable detection parameters, persisted via SettingsStore. */
data class GestureConfig(
    val nextGesture: GestureType = GestureType.HEAD_RIGHT,
    val prevGesture: GestureType = GestureType.HEAD_LEFT,
    val yawThresholdDeg: Float = 22f,
    val eyeClosedThreshold: Float = 0.25f, // below this an eye counts as closed
    val eyeOpenThreshold: Float = 0.55f,   // above this an eye counts as open
    val smileThreshold: Float = 0.7f,      // above this the subject counts as smiling
    val dwellMs: Long = 350L,              // held poses (head turn / wink / smile) fire after this
    val cooldownMs: Long = 1200L,          // ignore new triggers this long after firing
    val longBlinkMs: Long = 700L,          // eyes closed this long = a deliberate long blink
    val protection: MisfireProtection = MisfireProtection.BALANCED,
    val enabledByDefault: Boolean = true
)

/** Coarse state of the recogniser, surfaced to the UI as a status dot. */
enum class EngineStatus { NO_FACE, READY, ARMING, COOLDOWN }

/** Result of feeding one frame to [GestureEngine]. */
data class GestureOutput(val status: EngineStatus, val fired: PageAction?)
