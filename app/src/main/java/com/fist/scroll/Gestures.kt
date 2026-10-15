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
    const val BROW = "brow"
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
    private class Seen(val t: Long, val pose: Pose)
    private val launchSeen = ArrayList<Seen>()
    private var launchLastSeen = 0L
    private var launchArmed = true
    private var lastLaunch = 0L

    // подмигивание
    private val winkFrames = ArrayList<Long>()
    private var winkArmed = true
    private var lastWinkSeen = 0L

    // поднятие бровей
    private val browFrames = ArrayList<Long>()
    private var browArmed = true
    private var lastBrowSeen = 0L
    private var browBase = 0f
    private var browInit = false

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
            launchSeen.clear()
            launchLastSeen = 0L
            launchArmed = true
            winkFrames.clear()
            winkArmed = true
            browFrames.clear()
            browArmed = true
            browInit = false
        }
    }

    /**
     * Запуск приложения: один/два пальца должны уверенно держаться в кадре (~0,3–1 с, зависит
     * от чувствительности). Кадры копятся в окне 1,5 с, поэтому короткий сбой распознавания
     * не сбрасывает отсчёт. После запуска жест нужно опустить, пауза между запусками 1,5 с.
     */
    private fun checkLaunch(pose: Pose, mode: String, now: Long, sensitivity: Int): Pose? {
        val candidate = when {
            pose == Pose.ONE -> Pose.ONE
            pose == Pose.TWO && mode != Mode.TWO -> Pose.TWO
            else -> null
        }
        if (candidate != null) {
            launchSeen.add(Seen(now, candidate))
            launchLastSeen = now
        }
        while (launchSeen.isNotEmpty() && now - launchSeen[0].t > 1500) launchSeen.removeAt(0)
        if (candidate == null && now - launchLastSeen > 600) launchArmed = true
        if (!launchArmed || now - lastLaunch < 1500) return null

        val holdMs = 1100 - 80 * sensitivity // чувствительность 5 -> 0,7 с, 10 -> 0,3 с
        for (p in arrayOf(Pose.ONE, Pose.TWO)) {
            val xs = launchSeen.filter { it.pose == p }
            val others = launchSeen.size - xs.size
            if (xs.size >= 3 && xs.size > others &&
                now - xs[0].t >= holdMs && now - xs[xs.size - 1].t < 300
            ) {
                launchArmed = false
                lastLaunch = now
                launchSeen.clear()
                return p
            }
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
        launchPose = if (launchEnabled) checkLaunch(lastPose, mode, now, sensitivity) else null

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

    /**
     * Только запуск приложений по пальцам: используется в режиме подмигивания,
     * где свайп делается глазом, а руку мы смотрим лишь для запуска. Режим не сбрасывает.
     */
    fun updateLaunchOnly(
        lm: List<NormalizedLandmark>?,
        world: List<Landmark>?,
        w: Int,
        h: Int,
        now: Long,
        sensitivity: Int
    ) {
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
        launchPose = checkLaunch(lastPose, Mode.WINK, now, sensitivity)
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


    var lastBrow = 0f
        private set
    var lastBrowBase = 0f
        private set

    /**
     * Поднятие бровей. У каждого человека брови в покое стоят немного по-своему, поэтому
     * сравниваем текущий уровень с «спокойным» (медленно подстраивающимся) и срабатываем,
     * когда брови заметно выше. Кадры копятся в окне 0,4 с, единичный потерянный кадр не мешает.
     */
    fun updateBrow(hasFace: Boolean, brow: Float, now: Long, sensitivity: Int): Boolean {
        syncMode(Mode.BROW)
        lastFace = hasFace
        lastBrow = brow
        if (!hasFace) {
            browFrames.clear()
            return false
        }
        if (!browInit) {
            browBase = brow
            browInit = true
        }
        val rise = 0.50f - 0.04f * sensitivity   // сенс. 5 -> +0,30, сенс. 10 -> +0,10
        val raised = brow - browBase > rise && brow > 0.18f

        // «спокойный» уровень обновляем только когда брови не подняты
        if (!raised) browBase += (brow - browBase) * 0.04f
        lastBrowBase = browBase

        if (raised) {
            browFrames.add(now)
            lastBrowSeen = now
        }
        while (browFrames.isNotEmpty() && now - browFrames[0] > 400) browFrames.removeAt(0)
        if (!raised && now - lastBrowSeen > 400) browArmed = true

        val need = if (sensitivity >= 4) 2 else 3
        if (browArmed && browFrames.size >= need && now - lastTrigger > 900) {
            browArmed = false
            lastTrigger = now
            browFrames.clear()
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

    private fun dist(a: P, b: P): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
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
            // длина «запястье → кончик» относительно «запястье → основание пальца»:
            // у прямого пальца ~1,8, у согнутого ~1,1–1,3
            val reach = dist(p[0], p[f[3]]) / max(dist(p[0], p[f[0]]), 1e-6f)
            if (!fingerInit) {
                smooth[i] = m
                ext[i] = m > 140f
            } else {
                smooth[i] = 0.5f * smooth[i] + 0.5f * m
                // гистерезис: между 125 и 155 палец остаётся в прежнем состоянии
                if (smooth[i] > 155f) ext[i] = true
                else if (smooth[i] < 125f) ext[i] = false
                else if (reach > 1.6f) ext[i] = true   // спорная зона угла: решает расстояние
                else if (reach < 1.4f) ext[i] = false
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
