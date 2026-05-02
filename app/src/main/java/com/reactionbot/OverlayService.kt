package com.reactionbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Floating Command Center overlay.
 * ▶ START  — checks accessibility + capture ready before starting.
 * ■ STOP   — stops capture, bot disarmed.
 * ⊕ CAL   — toggles 4 draggable sensor dots.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private val dots = ArrayList<View>(4)

    companion object {
        const val CHANNEL_ID = "overlay_ch"
        @Volatile var projectionResultCode: Int   = -1
        @Volatile var projectionData: Intent?     = null
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(1, buildNote())
        buildPanel()
    }

    override fun onDestroy() {
        removePanel()
        clearDots()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Panel ──────────────────────────────────────────────────────────────────

    private fun buildPanel() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(Color.argb(235, 10, 10, 10))
        }

        val btnStart = chip("▶ START", Color.rgb(0, 170, 60)) { onStart() }
        val btnStop  = chip("■ STOP",  Color.rgb(200, 20, 20))  { onStop() }
        val btnCal   = chip("⊕ CAL",  Color.rgb(20, 80, 200))  { onCal() }

        row.addView(btnStart)
        row.addView(btnStop)
        row.addView(btnCal)

        val lp = wlp(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 12; y = 80 }

        wm.addView(row, lp)
        panel = row
        draggable(row, lp)
    }

    private fun chip(label: String, bg: Int, click: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(bg)
            setPadding(20, 10, 20, 10)
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6 }
        }

    // ── Button actions ─────────────────────────────────────────────────────────

    private fun onStart() {
        // Guard 1: screen capture permission
        if (projectionResultCode == -1 || projectionData == null) {
            toast("❌ No screen capture permission.\nOpen the app and tap LAUNCH BOT first.")
            return
        }
        // Guard 2: accessibility service must be active
        if (BotState.accessibilityService == null) {
            toast("❌ Accessibility service not active.\nSettings › Accessibility › Reaction Bot › Enable")
            return
        }
        // All good — start
        BotState.isRunning = true
        startForegroundService(
            Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, projectionResultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, projectionData)
            }
        )
        toast("✅ Bot started! Tapping dark tiles…")
    }

    private fun onStop() {
        BotState.isRunning = false
        stopService(Intent(this, ScreenCaptureService::class.java))
        toast("⏹ Bot stopped.")
    }

    private fun onCal() {
        if (dots.isEmpty()) {
            addDots()
            toast("Drag orange dots onto each lane, then tap ⊕ CAL again to save.")
        } else {
            clearDots()
            toast("✅ Sensor positions saved.")
        }
    }

    // ── Calibration dots ───────────────────────────────────────────────────────

    private fun addDots() {
        for (i in 0..3) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(210, 255, 80, 0))
                    setStroke(4, Color.WHITE)
                }
            }
            val sz = 66
            val pt = BotState.sensorPoints[i]
            val lp = wlp(sz, sz).apply {
                gravity = Gravity.TOP or Gravity.START
                x = pt.x.toInt() - sz / 2
                y = pt.y.toInt() - sz / 2
            }
            wm.addView(dot, lp)
            dots.add(dot)
            draggableDot(dot, lp, i)
        }
    }

    private fun clearDots() {
        dots.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        dots.clear()
    }

    // ── Drag helpers ───────────────────────────────────────────────────────────

    private fun draggable(v: View, lp: WindowManager.LayoutParams) {
        var ox = 0; var oy = 0; var tx = 0f; var ty = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ox = lp.x; oy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - tx).toInt()
                    lp.y = oy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(v, lp)
                }
            }
            false
        }
    }

    private fun draggableDot(v: View, lp: WindowManager.LayoutParams, idx: Int) {
        var ox = 0; var oy = 0; var tx = 0f; var ty = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ox = lp.x; oy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - tx).toInt()
                    lp.y = oy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(v, lp)
                    BotState.sensorPoints[idx].set(
                        (lp.x + 33).toFloat(),
                        (lp.y + 33).toFloat()
                    )
                }
            }
            true
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun wlp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    private fun removePanel() {
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
    }

    private fun toast(msg: String) =
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()

    private fun buildNote() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Reaction Bot — Overlay Active")
        .setContentText("Tap ▶ START on the floating panel")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .build()

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }
}
