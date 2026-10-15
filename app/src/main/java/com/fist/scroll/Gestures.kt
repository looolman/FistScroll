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
    THREE("три пальца"),
    OPEN("открытая ладонь"),
    THUMB("палец вверх"),
    RING("кольцо О"),
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

    // палец вверх = предыдущее видео
    private val thumbSeen = ArrayList<Long>()
    private var thumbLastSeen = 0L
    private var thumbArmed = true
    private var lastThumb = 0L

    /** true на том кадре, когда пора вернуться к предыдущему видео. */
    var thumbFired = false
        private set

    // кольцо «О» (большой и указательный сомкнуты) = выключить экран
    private val ringAll = ArrayList<Long>()
    private val ringHits = ArrayList<Long>()
    private var ringLastSeen = 0L
    private var ringArmed = true
    private var lastRing = 0L
    private var ringPinch = 0f

    /** true на том кадре, когда пора выключать экран. */
    var ringFired = false
        private set

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
            thumbSeen.clear()
            thumbArmed = true
            ringAll.clear()
            ringHits.clear()
            ringArmed = true
        }
    }

    /**
     * Кольцо «О»: поза должна держаться дольше остальных жестов (~0,7–1 с, зависит от
     * чувствительности) и занимать большинство кадров, потому что действие «выключить экран»
     * не должно срабатывать случайно. После срабатывания пауза 3 с, и жест надо убрать.
     */
    private fun checkRing(pose: Pose, now: Long, sensitivity: Int): Boolean {
        if (pose != Pose.NONE) ringAll.add(now)
        if (pose == Pose.RING) {
            ringHits.add(now)
            ringLastSeen = now
        }
        while (ringAll.isNotEmpty() && now - ringAll[0] > 1500) ringAll.removeAt(0)
        while (ringHits.isNotEmpty() && now - ringHits[0] > 1500) ringHits.removeAt(0)
        if (pose != Pose.RING && now - ringLastSeen > 800) ringArmed = true
        val holdMs = 1100 - 60 * sensitivity   // чувствительность 5 -> 0,8 с, 10 -> 0,5 с
        if (ringArmed && ringHits.size >= 4 && ringHits.size >= 0.6f * ringAll.size &&
            now - ringHits[0] >= holdMs && now - ringLastSeen < 300 && now - lastRing > 3000
        ) {
            ringArmed = false
            lastRing = now
            ringAll.clear()
            ringHits.clear()
            return true
        }
        return false
    }

    /**
     * Палец вверх: поза должна уверенно держаться (~0,25 с и минимум 2–3 кадра в окне 1 с),
     * потом её нужно убрать. Пауза между срабатываниями 1,2 с.
     */
    private fun checkThumb(pose: Pose, now: Long, sensitivity: Int): Boolean {
        if (pose == Pose.THUMB) {
            thumbSeen.add(now)
            thumbLastSeen = now
        }
        while (thumbSeen.isNotEmpty() && now - thumbSeen[0] > 1000) thumbSeen.removeAt(0)
        if (pose != Pose.THUMB && now - thumbLastSeen > 600) thumbArmed = true
        val need = if (sensitivity >= 7) 2 else 3
        if (thumbArmed && thumbSeen.size >= need && now - thumbSeen[0] >= 250 &&
            now - thumbLastSeen < 300 && now - lastThumb > 1200
        ) {
            thumbArmed = false
            lastThumb = now
            thumbSeen.clear()
            return true
        }
        return false
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
            pose == Pose.THREE -> Pose.THREE
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
        for (p in arrayOf(Pose.ONE, Pose.TWO, Pose.THREE)) {
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
        launchEnabled: Boolean,
        thumbEnabled: Boolean,
        ringEnabled: Boolean
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
            lastPose = classify(pts, lm, w, h)
        }
        launchPose = if (launchEnabled) checkLaunch(lastPose, mode, now, sensitivity) else null
        thumbFired = if (thumbEnabled) checkThumb(lastPose, now, sensitivity) else false
        ringFired = if (ringEnabled) checkRing(lastPose, now, sensitivity) else false

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
        sensitivity: Int,
        launchEnabled: Boolean,
        thumbEnabled: Boolean,
        ringEnabled: Boolean
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
            lastPose = classify(pts, lm, w, h)
        }
        launchPose = if (launchEnabled) checkLaunch(lastPose, Mode.WINK, now, sensitivity) else null
        thumbFired = if (thumbEnabled) checkThumb(lastPose, now, sensitivity) else false
        ringFired = if (ringEnabled) checkRing(lastPose, now, sensitivity) else false
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

    /**
     * Большой палец вверх. Три независимых признака (все должны сойтись):
     *  1) палец выпрямлен: кончик далеко от запястья или оба сустава почти прямые;
     *  2) он отведён от указательного (в кулаке большой палец прижат);
     *  3) по самому кадру он смотрит вверх и кончик заметно выше костяшек остальных пальцев.
     * Третий признак считается по картинке (ось y вниз), он не зависит от «мировых» координат.
     */
    private fun isThumbUp(p: List<P>, lm: List<NormalizedLandmark>?, w: Int, h: Int): Boolean {
        val scale = max(dist(p[0], p[9]), 1e-6f)
        val reach = dist(p[0], p[4]) / scale
        val straight = angle(p[2], p[3], p[4]) > 140f && angle(p[1], p[2], p[3]) > 130f
        thumbReach = reach
        if (!(reach > 1.3f || straight)) return false
        if (dist(p[4], p[5]) / scale < 0.75f) return false

        if (lm != null && lm.size >= 21) {
            val ux = (lm[4].x() - lm[2].x()) * w
            val uy = (lm[4].y() - lm[2].y()) * h
            val len = sqrt(ux * ux + uy * uy)
            if (len < 1e-6f || -uy / len < 0.5f) return false
            val sx = (lm[9].x() - lm[0].x()) * w
            val sy = (lm[9].y() - lm[0].y()) * h
            val palm = max(sqrt(sx * sx + sy * sy), 1e-6f)
            val knuckleTop = min(min(lm[5].y(), lm[9].y()), min(lm[13].y(), lm[17].y())) * h
            return lm[4].y() * h < knuckleTop - 0.25f * palm
        }
        // картинки нет: берём направление по 3D-координатам
        val ux = p[4].x - p[2].x
        val uy = p[4].y - p[2].y
        val uz = p[4].z - p[2].z
        val len = sqrt(ux * ux + uy * uy + uz * uz)
        return len > 1e-6f && -uy / len > 0.5f
    }

    /**
     * Кольцо «О» / знак «ОК»: кончики большого и указательного пальцев соприкасаются.
     * Если остальные три пальца выпрямлены (классический «ОК», как на твоём фото), это точно не кулак,
     * и достаточно лёгкого касания. Если остальные пальцы согнуты, указательный должен быть заметно
     * отведён от ладони, иначе это просто кулак.
     */
    private fun isRing(p: List<P>, othersExtended: Int): Boolean {
        val scale = max(dist(p[0], p[9]), 1e-6f)
        ringPinch = dist(p[4], p[8]) / scale
        val loop = dist(p[8], p[5]) / scale
        if (ringPinch > 0.38f) return false
        return if (othersExtended >= 2) loop > 0.40f else (ringPinch < 0.30f && loop > 0.60f)
    }

    /** Для статуса: насколько вытянут большой палец (около 1 = прижат, 1,5+ = отставлен). */
    private var thumbReach = 0f

    private fun classify(p: List<P>, lm: List<NormalizedLandmark>?, w: Int, h: Int): Pose {
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
        val others = (1..3).count { ext[it] }
        if (isRing(p, others)) {
            lastDetail = " [большой и указательный сомкнуты]"
            return Pose.RING
        }
        // «почти кулак»: все пальцы согнуты или один из них согнут не до конца
        val nearFist = count == 0 || (count == 1 && smooth[ext.indexOfFirst { it }] < 150f)
        if (nearFist && isThumbUp(p, lm, w, h)) {
            lastDetail = " [большой палец вверх]"
            return Pose.THUMB
        }
        if (count == 0) {
            lastDetail = " [все согнуты; большой ${"%.1f".format(thumbReach)}; щепотка ${"%.2f".format(ringPinch)}]"
        }
        if (ringPinch < 0.7f && count != 0) {
            lastDetail += " [щепотка ${"%.2f".format(ringPinch)}]"
        }
        return when {
            count == 0 -> Pose.FIST
            ext[0] && !ext[1] && !ext[2] && !ext[3] -> Pose.ONE
            ext[0] && ext[1] && !ext[2] && !ext[3] -> Pose.TWO
            ext[0] && ext[1] && ext[2] && !ext[3] -> Pose.THREE
            count == 4 -> Pose.OPEN
            else -> Pose.OTHER
        }
    }
}
