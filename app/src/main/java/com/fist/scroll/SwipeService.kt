package com.fist.scroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

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
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

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
