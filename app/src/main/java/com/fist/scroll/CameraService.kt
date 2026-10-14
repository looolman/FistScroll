package com.fist.scroll

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.nio.ByteBuffer

/**
 * Следит через фронтальную камеру за рукой и просит SwipeService сделать свайп.
 * Работает как foreground-служба, поэтому камера не отключается при переходе в TikTok.
 */
class CameraService : Service() {

    companion object {
        const val PREFS = "fist_prefs"
        const val KEY_MODE = "mode"
        const val KEY_SENS = "sens"
        const val KEY_LAUNCH = "launch_on"
        const val KEY_BRIGHT = "auto_bright"
        const val KEY_APP_ONE = "app_one"
        const val KEY_APP_TWO = "app_two"
        const val ACTION_STOP = "com.fist.scroll.STOP"
        private const val CHANNEL = "fist_scroll"

        @Volatile
        var running = false

        /** Текст статуса для экрана приложения (вызывается в главном потоке). */
        @Volatile
        var statusListener: ((String) -> Unit)? = null

        @Volatile
        var lastStatus: String = "Остановлено"
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var handLm: HandLandmarker? = null
    private var faceLm: FaceLandmarker? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sensorOrientation = 270

    private val engine = GestureEngine()
    private var lastFrame = 0L
    private var lastHandRun = 0L

    // автояркость
    private var requestBuilder: CaptureRequest.Builder? = null
    private var aeRange: Range<Int>? = null
    private var aeStep = 0.1667f
    private var fpsRange: Range<Int>? = null
    private var expIdx = 0
    private var lastExpAdjust = 0L
    private val lut = IntArray(256) { it }
    private var gammaSmooth = 1f
    private var lastMean = 128f
    private var lastStatusText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        startAsForeground()
        running = true
        publish("Запуск…")

        thread = HandlerThread("fist-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        handler!!.post {
            try {
                val mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODE, Mode.FIST)
                if (mode == Mode.WINK) createFace().also { faceLm = it } else createHand().also { handLm = it }
                openCamera()
            } catch (e: Throwable) {
                publish("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                main.post { stopSelf() }
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "FistScroll", NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("FistScroll работает")
            .setContentText("Камера следит за жестом")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }
    }

    /** Модель читаем из assets в память: так обходится ошибка «doesn't have a slash in it». */
    private fun modelBuffer(name: String): ByteBuffer {
        val bytes = assets.open(name).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.rewind()
        return buffer
    }

    private fun createHand(): HandLandmarker {
        val base = BaseOptions.builder().setModelAssetBuffer(modelBuffer("hand_landmarker.task")).build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.35f)
            .setMinHandPresenceConfidence(0.35f)
            .setMinTrackingConfidence(0.35f)
            .build()
        return HandLandmarker.createFromOptions(this, options)
    }

    private fun createFace(): FaceLandmarker {
        val base = BaseOptions.builder().setModelAssetBuffer(modelBuffer("face_landmarker.task")).build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setMinFaceDetectionConfidence(0.4f)
            .setMinFacePresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .build()
        return FaceLandmarker.createFromOptions(this, options)
    }

    private fun fail(e: Throwable) {
        publish("Ошибка: ${e.message ?: e.javaClass.simpleName}")
        main.post { stopSelf() }
    }

    private fun ensureHand(): HandLandmarker? {
        handLm?.let { return it }
        return try { createHand().also { handLm = it } } catch (e: Throwable) { fail(e); null }
    }

    private fun ensureFace(): FaceLandmarker? {
        faceLm?.let { return it }
        return try { createFace().also { faceLm = it } } catch (e: Throwable) { fail(e); null }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("нет разрешения на камеру")
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: throw IllegalStateException("фронтальная камера не найдена")

        val chars = manager.getCameraCharacteristics(id)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270

        aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        aeStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.1667f
        // даём автоэкспозиции право на более длинную выдержку в темноте (но не медленнее ~15 к/с)
        fpsRange = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper in 24..30 && it.lower <= 15 }
            ?.minByOrNull { it.lower }

        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.YUV_420_888)
        // берём размер, ближайший к 640x480, чтобы было быстро
        val size = sizes.minByOrNull { Math.abs(it.width * it.height - 640 * 480) }!!

        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also {
            it.setOnImageAvailableListener({ r -> onFrame(r) }, handler)
        }

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                startSession(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                publish("Камеру занял другой процесс")
                device.close()
                main.post { stopSelf() }
            }

            override fun onError(device: CameraDevice, error: Int) {
                publish("Ошибка камеры: $error")
                device.close()
                main.post { stopSelf() }
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private fun startSession(device: CameraDevice) {
        try {
            val surface = reader!!.surface
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            requestBuilder = builder
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    s.setRepeatingRequest(builder.build(), null, handler)
                    publish("Жду жест…")
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    publish("Не удалось запустить камеру")
                    main.post { stopSelf() }
                }
            }, handler)
        } catch (e: Throwable) {
            publish("Ошибка камеры: ${e.message}")
            main.post { stopSelf() }
        }
    }

    private fun onFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        var bitmap: Bitmap? = null
        try {
            val now = SystemClock.uptimeMillis()
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val mode = prefs.getString(KEY_MODE, Mode.FIST) ?: Mode.FIST
            val sens = prefs.getInt(KEY_SENS, 5)
            // подмигивание ловим чаще (~30 кадров/с), жесты рукой ~25 кадров/с
            if (now - lastFrame < (if (mode == Mode.WINK) 30 else 40)) return
            lastFrame = now

            val boost = prefs.getBoolean(KEY_BRIGHT, true)
            val bmp = toBitmap(image, boost)
            adaptExposure(lastMean, now, boost)
            bitmap = bmp
            val mp = BitmapImageBuilder(bmp).build()

            var text: String
            var launchText: String? = null
            val fire: Boolean
            if (mode == Mode.WINK) {
                val fl = ensureFace() ?: return
                val res = fl.detectForVideo(mp, now)
                var left = 0f
                var right = 0f
                var has = false
                val opt = res.faceBlendshapes()
                if (opt.isPresent) {
                    val cats = opt.get().firstOrNull()
                    if (cats != null) {
                        has = true
                        for (c in cats) {
                            when (c.categoryName()) {
                                "eyeBlinkLeft" -> left = c.score()
                                "eyeBlinkRight" -> right = c.score()
                            }
                        }
                    }
                }
                fire = engine.updateWink(has, left, right, now, sens)
                text = if (!has) "Лицо не видно"
                else "Глаза закрыты: ${(left * 100).toInt()}% и ${(right * 100).toInt()}%"

                // пальцы для запуска приложений: смотрим руку реже (~15 раз/с), свайп при этом глазом
                if (prefs.getBoolean(KEY_LAUNCH, false)) {
                    if (now - lastHandRun >= 60) {
                        lastHandRun = now
                        val hl = ensureHand() ?: return
                        val hres = hl.detectForVideo(mp, now)
                        engine.updateLaunchOnly(
                            hres.landmarks().firstOrNull(), hres.worldLandmarks().firstOrNull(),
                            bmp.width, bmp.height, now, sens
                        )
                        val lp = engine.launchPose
                        if (lp != null) doLaunch(lp, prefs)?.let { launchText = it }
                    }
                    text += " | рука: ${engine.lastPose.title}"
                }
            } else {
                val hl = ensureHand() ?: return
                val res = hl.detectForVideo(mp, now)
                val hand = res.landmarks().firstOrNull()
                val world = res.worldLandmarks().firstOrNull()
                val launchOn = prefs.getBoolean(KEY_LAUNCH, false)
                fire = engine.updateHand(hand, world, bmp.width, bmp.height, now, mode, sens, launchOn)
                text = "Вижу: ${engine.lastPose.title}${engine.lastDetail}"

                val lp = engine.launchPose
                if (lp != null) doLaunch(lp, prefs)?.let { launchText = it }
            }

            if (fire) {
                val svc = SwipeService.instance
                text = if (svc == null) {
                    "Жест есть, но служба спец. возможностей выключена!"
                } else if (svc.swipeUp()) {
                    "Свайп!"
                } else {
                    "Свайп не удался"
                }
            }
            launchText?.let { text = it }
            if (gammaSmooth < 0.95f || expIdx > 0) text += " ☀"
            publish(text)
        } catch (e: Throwable) {
            publish("Ошибка: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            bitmap?.recycle()
            image.close()
        }
    }

    /** Если в кадре темно, просим камеру сделать светлее (+EV); если светло, возвращаем назад. */
    private fun adaptExposure(mean: Float, now: Long, enabled: Boolean) {
        val range = aeRange ?: return
        val builder = requestBuilder ?: return
        val sess = session ?: return
        if (now - lastExpAdjust < 700) return
        val oneEv = Math.max(1, Math.round(1f / aeStep))
        val cap = Math.min(range.upper, oneEv * 3)
        var idx = expIdx
        if (!enabled) idx = 0
        else if (mean < 85f && idx < cap) idx = Math.min(cap, idx + oneEv)
        else if (mean > 150f && idx > 0) idx = Math.max(0, idx - oneEv)
        if (idx == expIdx) return
        lastExpAdjust = now
        expIdx = idx
        try {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx)
            sess.setRepeatingRequest(builder.build(), null, handler)
        } catch (_: Throwable) {
        }
    }

    private fun doLaunch(lp: Pose, prefs: SharedPreferences): String? {
        val pkg = prefs.getString(if (lp == Pose.ONE) KEY_APP_ONE else KEY_APP_TWO, "") ?: ""
        if (pkg.isEmpty()) return null
        val svc = SwipeService.instance
        return if (svc == null) {
            "Служба спец. возможностей выключена!"
        } else if (svc.openApp(pkg)) {
            "Открываю приложение…"
        } else {
            "Не открылось: разреши «всплывающие окна в фоне»"
        }
    }

    /**
     * YUV_420_888 -> Bitmap, повёрнутый «головой вверх», как держишь телефон.
     * Берём каждый второй пиксель: модели MediaPipe всё равно уменьшают кадр до ~200 px,
     * зато обработка в разы быстрее и реакция живее.
     */
    private fun toBitmap(image: Image, boost: Boolean): Bitmap {
        val w2 = image.width / 2
        val h2 = image.height / 2
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val out = IntArray(w2 * h2)

        // средняя яркость кадра (по редкой сетке) и таблица осветления теней
        var sum = 0L
        var n = 0
        var gy = 0
        while (gy < image.height) {
            val row = gy * yP.rowStride
            var gx = 0
            while (gx < image.width) {
                sum += yBuf.get(row + gx).toInt() and 0xFF
                n++
                gx += 8
            }
            gy += 8
        }
        val mean = if (n > 0) sum.toFloat() / n else 128f
        lastMean = mean
        var gamma = 1f
        if (boost && mean < 90f) {
            // подбираем степень так, чтобы средняя яркость поднялась примерно до 105 из 255
            val g = (Math.log(105.0 / 255.0) / Math.log(Math.max(mean, 8f) / 255.0)).toFloat()
            gamma = g.coerceIn(0.4f, 1f)
        }
        gammaSmooth = 0.7f * gammaSmooth + 0.3f * gamma
        for (i in 0..255) {
            lut[i] = if (gammaSmooth >= 0.98f) i
            else (255.0 * Math.pow(i / 255.0, gammaSmooth.toDouble())).toInt().coerceIn(0, 255)
        }

        for (j in 0 until h2) {
            val sj = j * 2
            val yRow = sj * yP.rowStride
            val uRow = (sj shr 1) * uP.rowStride
            val vRow = (sj shr 1) * vP.rowStride
            for (i in 0 until w2) {
                val si = i * 2
                val y = lut[yBuf.get(yRow + si).toInt() and 0xFF]
                val u = (uBuf.get(uRow + (si shr 1) * uP.pixelStride).toInt() and 0xFF) - 128
                val v = (vBuf.get(vRow + (si shr 1) * vP.pixelStride).toInt() and 0xFF) - 128
                var r = y + (1.402f * v).toInt()
                var g = y - (0.344f * u + 0.714f * v).toInt()
                var b = y + (1.772f * u).toInt()
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[j * w2 + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val raw = Bitmap.createBitmap(out, w2, h2, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { postRotate(sensorOrientation.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, w2, h2, m, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    private fun publish(text: String) {
        lastStatus = text
        if (text == lastStatusText) return
        lastStatusText = text
        main.post { statusListener?.invoke(text) }
    }

    override fun onDestroy() {
        running = false
        try { session?.close() } catch (_: Throwable) {}
        try { camera?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { handLm?.close() } catch (_: Throwable) {}
        try { faceLm?.close() } catch (_: Throwable) {}
        thread?.quitSafely()
        lastStatus = "Остановлено"
        main.post { statusListener?.invoke("Остановлено") }
        super.onDestroy()
    }
}
