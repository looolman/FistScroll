package com.fist.scroll

import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object Mode {
    const val FIST = "fist"
    const val TWO = "two"
    const val WAVE = "wave"
    const val WINK = "wink"
}

/** Поза руки по одному кадру. */
enum class Pose(val title: String) {
    NONE("руки не видно"),
    FIST("кулак"),
    ONE("один палец"),
    TWO("два пальца"),
    OPEN("открытая ладонь"),
    OTHER("рука")
}

private class P(val x: Float, val y: Float, val z: Float)

/**
 * Решает, пора ли делать свайп. Вызывается на каждый кадр.
 * sensitivity: 1..10 (больше = легче срабатывает).
 */
class GestureEngine {

    private var mode = ""
    private var holdFrames = 0
    private var releaseFrames = 99
    private var armed = true
    private var lastTrigger = 0L

    private class Sample(val t: Long, val x: Float, val y: Float)
    private val trail = ArrayList<Sample>()
    private var lastSeen = 0L

    // состояние пальцев: индекс, средний, безымянный, мизинец
    private val fingers = arrayOf(
        intArrayOf(5, 6, 7, 8),
        intArrayOf(9, 10, 11, 12),
        intArrayOf(13, 14, 15, 16),
        intArrayOf(17, 18, 19, 20)
    )
    // запуск приложений жестом
    private var launchHeld: Pose? = null
    private var launchSince = 0L
    private var launchLastSeen = 0L
    private var launchArmed = true
    private var lastLaunch = 0L

    // подмигивание
    private val winkFrames = ArrayList<Long>()
    private var winkArmed = true
    private var lastWinkSeen = 0L

    private val smooth = FloatArray(4)
    private val ext = BooleanArray(4)
    private var fingerInit = false

    var lastPose = Pose.NONE
        private set
    var lastDetail = ""
        private set

    /** Если не null — нужно открыть приложение для этого жеста (ONE или TWO). */
    var launchPose: Pose? = null
        private set

    private fun syncMode(m: String) {
        if (mode != m) {
            mode = m
            holdFrames = 0
            releaseFrames = 99
            armed = true
            trail.clear()
            fingerInit = false
            launchHeld = null
            launchSince = 0L
            launchLastSeen = 0L
            launchArmed = true
            winkFrames.clear()
            winkArmed = true
        }
    }

    /** Один/два пальца должны продержаться ~0,35 с, после запуска жест нужно опустить. */
    private fun checkLaunch(pose: Pose, mode: String, now: Long): Pose? {
        val candidate = when {
            pose == Pose.ONE -> Pose.ONE
            pose == Pose.TWO && mode != Mode.TWO -> Pose.TWO
            else -> null
        }
        if (candidate == null) {
            launchHeld = null
            if (now - launchLastSeen > 300) launchArmed = true
            return null
        }
        launchLastSeen = now
        if (candidate != launchHeld) {
            launchHeld = candidate
            launchSince = now
        }
        if (launchArmed && now - launchSince >= 350 && now - lastLaunch > 3000) {
            launchArmed = false
            lastLaunch = now
            return candidate
        }
        return null
    }

    /** Общая логика: жест должен продержаться пару кадров, потом его надо «отпустить». */
    private fun hold(match: Boolean, need: Int, now: Long): Boolean {
        var fire = false
        if (match) {
            holdFrames++
            releaseFrames = 0
            if (holdFrames >= need && armed && now - lastTrigger > 1000) {
                fire = true
                armed = false
                lastTrigger = now
            }
        } else {
            holdFrames = 0
            releaseFrames++
            if (releaseFrames >= 3) armed = true
        }
        return fire
    }

    /** Режимы с рукой: кулак, два пальца, взмах. Возвращает true, если пора листать. */
    fun updateHand(
        lm: List<NormalizedLandmark>?,
        world: List<Landmark>?,
        w: Int,
        h: Int,
        now: Long,
        mode: String,
        sensitivity: Int,
        launchEnabled: Boolean
    ): Boolean {
        syncMode(mode)

        // для пальцев берём «мировые» 3D-координаты: они не зависят от размера руки и расстояния
        val pts: List<P>? = when {
            world != null && world.size >= 21 -> world.map { P(it.x(), it.y(), it.z()) }
            lm != null && lm.size >= 21 -> lm.map { P(it.x() * w, it.y() * h, it.z() * w) }
            else -> null
        }
        if (pts == null) {
            fingerInit = false
            lastPose = Pose.NONE
            lastDetail = ""
        } else {
            lastPose = classify(pts)
        }
        launchPose = if (launchEnabled) checkLaunch(lastPose, mode, now) else null

        if (mode == Mode.WAVE) {
            val fire = updateWave(lm, now, sensitivity) && now - lastTrigger > 1000
            if (fire) {
                lastTrigger = now
                trail.clear()
            }
            return fire
        }

        val target = if (mode == Mode.FIST) Pose.FIST else Pose.TWO
        val need = if (sensitivity >= 7) 2 else 3
        return hold(lastPose == target, need, now)
    }

    var lastFace = false
        private set
    var lastLeft = 0f
        private set
    var lastRight = 0f
        private set

    /**
     * Подмигивание: один глаз закрыт заметно сильнее другого.
     * Сравниваем глаза между собой (а не с жёстким порогом), потому что при подмигивании
     * второй глаз обычно тоже немного щурится. Кадры с подмигиванием копятся в окне 0,35 с,
     * так что единичный пропущенный кадр не мешает.
     */
    fun updateWink(hasFace: Boolean, left: Float, right: Float, now: Long, sensitivity: Int): Boolean {
        syncMode(Mode.WINK)
        lastFace = hasFace
        lastLeft = left
        lastRight = right

        val diffMin = 0.45f - 0.03f * sensitivity     // насколько один глаз должен быть закрыт сильнее
        val closedMin = 0.45f - 0.025f * sensitivity  // минимальная закрытость «подмигивающего» глаза
        val hi = max(left, right)
        val lo = min(left, right)
        val wink = hasFace && hi > closedMin && (hi - lo) > diffMin && lo < 0.5f

        if (wink) {
            winkFrames.add(now)
            lastWinkSeen = now
        }
        while (winkFrames.isNotEmpty() && now - winkFrames[0] > 350) winkFrames.removeAt(0)
        if (!wink && now - lastWinkSeen > 400) winkArmed = true

        val need = if (sensitivity >= 4) 2 else 3
        if (winkArmed && winkFrames.size >= need && now - lastTrigger > 900) {
            winkArmed = false
            lastTrigger = now
            winkFrames.clear()
            return true
        }
        return false
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
        val threshold = 0.40f - 0.025f * sensitivity
        return dy < -threshold && abs(dx) < abs(dy) * 1.3f
    }

    /** Угол в точке b между отрезками b-a и b-c, в градусах (180 = прямой палец). */
    private fun angle(a: P, b: P, c: P): Float {
        val ux = a.x - b.x
        val uy = a.y - b.y
        val uz = a.z - b.z
        val vx = c.x - b.x
        val vy = c.y - b.y
        val vz = c.z - b.z
        val nu = sqrt(ux * ux + uy * uy + uz * uz)
        val nv = sqrt(vx * vx + vy * vy + vz * vz)
        if (nu < 1e-6f || nv < 1e-6f) return 180f
        val cos = ((ux * vx + uy * vy + uz * vz) / (nu * nv)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    private fun classify(p: List<P>): Pose {
        for (i in 0 until 4) {
            val f = fingers[i]
            val aPip = angle(p[f[0]], p[f[1]], p[f[2]])
            val aDip = angle(p[f[1]], p[f[2]], p[f[3]])
            val m = 0.65f * aPip + 0.35f * aDip
            if (!fingerInit) {
                smooth[i] = m
                ext[i] = m > 140f
            } else {
                smooth[i] = 0.5f * smooth[i] + 0.5f * m
                // гистерезис: между 125 и 155 палец остаётся в прежнем состоянии
                if (smooth[i] > 155f) ext[i] = true
                else if (smooth[i] < 125f) ext[i] = false
            }
        }
        fingerInit = true

        val names = arrayOf("указ.", "средн.", "безым.", "мизин.")
        lastDetail = ext.indices.filter { ext[it] }.joinToString(prefix = " [", postfix = "]") { names[it] }
            .let { if (it == " []") " [все согнуты]" else it }

        val count = ext.count { it }
        return when {
            count == 0 -> Pose.FIST
            ext[0] && !ext[1] && !ext[2] && !ext[3] -> Pose.ONE
            ext[0] && ext[1] && !ext[2] && !ext[3] -> Pose.TWO
            count == 4 -> Pose.OPEN
            else -> Pose.OTHER
        }
    }
}
