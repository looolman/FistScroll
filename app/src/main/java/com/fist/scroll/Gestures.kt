package com.fist.scroll

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot

object Mode {
    const val FIST = "fist"
    const val TWO = "two"
    const val WAVE = "wave"
}

/** Поза руки по одному кадру. */
enum class Pose(val title: String) {
    NONE("руки не видно"),
    FIST("кулак"),
    TWO("два пальца"),
    OPEN("открытая ладонь"),
    OTHER("рука")
}

/**
 * Решает, пора ли делать свайп. Вызывается на каждый кадр.
 * sensitivity: 1..10 (больше = легче срабатывает).
 */
class GestureEngine {

    private var holdFrames = 0
    private var releaseFrames = 99
    private var armed = true
    private var lastTrigger = 0L

    private class Sample(val t: Long, val x: Float, val y: Float)
    private val trail = ArrayList<Sample>()
    private var lastSeen = 0L

    var lastPose = Pose.NONE
        private set

    /** Возвращает true, если нужно сделать свайп. */
    fun update(
        lm: List<NormalizedLandmark>?,
        w: Int,
        h: Int,
        now: Long,
        mode: String,
        sensitivity: Int
    ): Boolean {
        val pose = if (lm == null) Pose.NONE else classify(lm, w, h)
        lastPose = pose

        val cooldownOk = now - lastTrigger > 1000
        var fire = false

        if (mode == Mode.WAVE) {
            fire = updateWave(lm, now, sensitivity) && cooldownOk
        } else {
            val target = if (mode == Mode.FIST) Pose.FIST else Pose.TWO
            val need = if (sensitivity >= 7) 2 else 3
            if (pose == target) {
                holdFrames++
                releaseFrames = 0
                if (holdFrames >= need && armed && cooldownOk) {
                    fire = true
                    armed = false
                }
            } else {
                holdFrames = 0
                releaseFrames++
                // Чтобы повторить свайп, жест нужно «отпустить» на пару кадров.
                if (releaseFrames >= 3) armed = true
            }
        }

        if (fire) {
            lastTrigger = now
            trail.clear()
        }
        return fire
    }

    private fun updateWave(lm: List<NormalizedLandmark>?, now: Long, sensitivity: Int): Boolean {
        if (lm == null) {
            if (now - lastSeen > 300) trail.clear()
            return false
        }
        lastSeen = now
        // центр ладони: запястье и основания пальцев
        val ids = intArrayOf(0, 5, 9, 13, 17)
        var cx = 0f
        var cy = 0f
        for (i in ids) {
            cx += lm[i].x()
            cy += lm[i].y()
        }
        cx /= ids.size
        cy /= ids.size
        trail.add(Sample(now, cx, cy))
        while (trail.isNotEmpty() && now - trail[0].t > 450) trail.removeAt(0)
        if (trail.size < 4) return false

        val first = trail[0]
        val dy = cy - first.y
        val dx = cx - first.x
        // порог: чем выше чувствительность, тем короче нужен взмах
        val threshold = 0.40f - 0.025f * sensitivity
        return dy < -threshold && abs(dx) < abs(dy) * 1.3f
    }

    private fun classify(lm: List<NormalizedLandmark>, w: Int, h: Int): Pose {
        fun d(a: Int, b: Int): Float =
            hypot((lm[a].x() - lm[b].x()) * w, (lm[a].y() - lm[b].y()) * h)

        // палец вытянут, если кончик заметно дальше от запястья, чем средний сустав
        fun extended(pip: Int, tip: Int) = d(0, tip) > d(0, pip) * 1.2f

        val index = extended(6, 8)
        val middle = extended(10, 12)
        val ring = extended(14, 16)
        val pinky = extended(18, 20)
        val count = listOf(index, middle, ring, pinky).count { it }

        return when {
            count == 0 -> Pose.FIST
            index && middle && !ring && !pinky -> Pose.TWO
            count == 4 -> Pose.OPEN
            else -> Pose.OTHER
        }
    }
}
