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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Persistent floating "Command Center" overlay.
 * Draws over other apps using TYPE_APPLICATION_OVERLAY.
 * Provides START / STOP / CALIBRATE buttons and 4 draggable sensor points.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var controlPanel: View? = null
    private val sensorViews = ArrayList<View>(4)

    companion object {
        const val CHANNEL_ID = "overlay_channel"
        var projectionResultCode: Int = -1
        var projectionData: Intent? = null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, buildNotification())
        showControlPanel()
    }

    // ── Control panel ──────────────────────────────────────────────────────────

    private fun showControlPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(14, 10, 14, 10)
            setBackgroundColor(Color.argb(230, 15, 15, 15))
        }

        panel.addView(makeBtn("▶ START", Color.rgb(0, 160, 60)) {
            if (projectionResultCode == -1 || projectionData == null) {
                toast("Restart app to grant screen permission first.")
                return@makeBtn
            }
            BotState.isRunning = true
            startCapture()
            toast("Bot running!")
        })
        panel.addView(makeBtn("■ STOP", Color.rgb(190, 20, 20)) {
            BotState.isRunning = false
            stopService(Intent(this, ScreenCaptureService::class.java))
            toast("Bot stopped")
        })
        panel.addView(makeBtn("⊕ CAL", Color.rgb(10, 80, 180)) {
            toggleCalibrate()
        })

        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 10; y = 80 }

        windowManager.addView(panel, lp)
        controlPanel = panel
        makePanelDraggable(panel, lp)
    }

    private fun makeBtn(label: String, bg: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setBackgroundColor(bg)
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(18, 6, 18, 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6 }
            setOnClickListener { onClick() }
        }

    // ── Calibration sensor points ──────────────────────────────────────────────

    private fun toggleCalibrate() {
        if (sensorViews.isEmpty()) showSensorPoints() else hideSensorPoints()
    }

    private fun showSensorPoints() {
        for (i in 0..3) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(200, 255, 80, 0))
                    setStroke(4, Color.WHITE)
                }
            }
            val size = 64
            val pt = BotState.sensorPoints[i]
            val lp = overlayParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
                x = pt.x.toInt() - size / 2
                y = pt.y.toInt() - size / 2
            }
            windowManager.addView(dot, lp)
            sensorViews.add(dot)
            makeSensorDraggable(dot, lp, i)
        }
        toast("Drag orange dots to align with lanes, then tap CAL again")
    }

    private fun hideSensorPoints() {
        sensorViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }
        sensorViews.clear()
        toast("Sensor positions saved")
    }

    // ── Drag helpers ───────────────────────────────────────────────────────────

    private fun makePanelDraggable(view: View, lp: WindowManager.LayoutParams) {
        var dx = 0; var dy = 0; var tx = 0f; var ty = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = lp.x; dy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = dx + (e.rawX - tx).toInt()
                    lp.y = dy + (e.rawY - ty).toInt()
                    windowManager.updateViewLayout(view, lp)
                }
            }
            false
        }
    }

    private fun makeSensorDraggable(view: View, lp: WindowManager.LayoutParams, idx: Int) {
        var dx = 0; var dy = 0; var tx = 0f; var ty = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = lp.x; dy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = dx + (e.rawX - tx).toInt()
                    lp.y = dy + (e.rawY - ty).toInt()
                    windowManager.updateViewLayout(view, lp)
                    // Update shared state so ScreenCaptureService reads new coords
                    BotState.sensorPoints[idx].set(
                        (lp.x + 32).toFloat(),
                        (lp.y + 32).toFloat()
                    )
                }
            }
            true
        }
    }

    // ── Screen capture ─────────────────────────────────────────────────────────

    private fun startCapture() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, projectionResultCode)
            putExtra(ScreenCaptureService.EXTRA_PROJECTION_DATA, projectionData)
        }
        startForegroundService(intent)
    }

    // ── Overlay params helper ──────────────────────────────────────────────────

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    // ── Notification ───────────────────────────────────────────────────────────

    private fun buildNotification() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Reaction Bot Active")
        .setContentText("Floating overlay running")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .build()

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        try { controlPanel?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        hideSensorPoints()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
