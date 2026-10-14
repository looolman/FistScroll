package com.fist.scroll

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
            .setMinHandDetectionConfidence(0.4f)
            .setMinHandPresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
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
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }.build()
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    s.setRepeatingRequest(request, null, handler)
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
            if (now - lastFrame < 60) return // не чаще ~15 кадров/с, экономим батарею
            lastFrame = now

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val mode = prefs.getString(KEY_MODE, Mode.FIST) ?: Mode.FIST
            val sens = prefs.getInt(KEY_SENS, 5)

            val bmp = toBitmap(image)
            bitmap = bmp
            val mp = BitmapImageBuilder(bmp).build()

            var text: String
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
            } else {
                val hl = ensureHand() ?: return
                val res = hl.detectForVideo(mp, now)
                val hand = res.landmarks().firstOrNull()
                val world = res.worldLandmarks().firstOrNull()
                fire = engine.updateHand(hand, world, bmp.width, bmp.height, now, mode, sens)
                text = "Вижу: ${engine.lastPose.title}${engine.lastDetail}"
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
            publish(text)
        } catch (e: Throwable) {
            publish("Ошибка: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            bitmap?.recycle()
            image.close()
        }
    }

    /** YUV_420_888 -> Bitmap, повёрнутый «головой вверх», как держишь телефон. */
    private fun toBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val out = IntArray(w * h)

        for (j in 0 until h) {
            val yRow = j * yP.rowStride
            val uRow = (j shr 1) * uP.rowStride
            val vRow = (j shr 1) * vP.rowStride
            for (i in 0 until w) {
                val y = yBuf.get(yRow + i).toInt() and 0xFF
                val u = (uBuf.get(uRow + (i shr 1) * uP.pixelStride).toInt() and 0xFF) - 128
                val v = (vBuf.get(vRow + (i shr 1) * vP.pixelStride).toInt() and 0xFF) - 128
                var r = y + (1.402f * v).toInt()
                var g = y - (0.344f * u + 0.714f * v).toInt()
                var b = y + (1.772f * u).toInt()
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val raw = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { postRotate(sensorOrientation.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, w, h, m, true)
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
