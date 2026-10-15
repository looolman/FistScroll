package com.fist.scroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/** Служба специальных возможностей: умеет делать свайп по экрану вместо пальца. */
class SwipeService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SwipeService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        hideHint()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        hideHint()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}


    // ---------- маленькая подсказка «что видит камера» поверх экрана ----------
    // Окно типа TYPE_ACCESSIBILITY_OVERLAY: отдельное разрешение не нужно, касания проходят
    // сквозь него (TikTok работает как обычно). Вызывать только из главного потока.
    private var hintView: TextView? = null
    private var hintPos = -1
    private var hintText = ""

    private fun hintParams(pos: Int): WindowManager.LayoutParams {
        val d = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val top = pos == 0 || pos == 1
        val left = pos == 0 || pos == 2
        lp.gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or
            (if (left) Gravity.START else Gravity.END)
        lp.x = (8 * d).toInt()
        lp.y = ((if (top) 56 else 100) * d).toInt()
        return lp
    }

    /** pos: 0 сверху слева, 1 сверху справа, 2 снизу слева, 3 снизу справа. */
    fun showHint(text: String, pos: Int) {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            var v = hintView
            if (v == null) {
                val d = resources.displayMetrics.density
                v = TextView(this).apply {
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 20 * d
                        setColor(Color.argb(150, 0, 0, 0))
                    }
                    this.text = text
                }
                hintText = text
                hintPos = pos
                hintView = v
                wm.addView(v, hintParams(pos))
                return
            }
            if (pos != hintPos) {
                hintPos = pos
                wm.updateViewLayout(v, hintParams(pos))
            }
            if (text != hintText) {
                hintText = text
                v.text = text
            }
        } catch (_: Throwable) {
        }
    }

    fun hideHint() {
        val v = hintView ?: return
        hintView = null
        hintText = ""
        hintPos = -1
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Throwable) {
        }
    }

    /** Открывает приложение по имени пакета. */
    fun openApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** Свайп снизу вверх по центру экрана = следующее видео. */
    fun swipeUp(): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.78f)
            lineTo(x, dm.heightPixels * 0.25f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 160)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
