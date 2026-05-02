package com.reactionbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Foreground service (mediaProjection type).
 *
 * ▸ Frame data lives ONLY in Image.planes[0].buffer (direct ByteBuffer, RAM).
 * ▸ No files written — Image.close() called in every finally block.
 * ▸ acquireLatestImage() drops stale frames automatically — stays synced at high speed.
 * ▸ Per-lane 80ms debounce stops double-taps on the same tile.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader?         = null
    private var display: VirtualDisplay?     = null
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val lastTap = LongArray(4) { 0L }

    companion object {
        const val CHANNEL_ID      = "cap_ch"
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_DATA        = "data"
        private const val DEBOUNCE  = 80L
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        thread  = HandlerThread("BotFrames", Thread.MAX_PRIORITY).also { it.start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, buildNote())

        val rc = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        // API-safe parcelable extra
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (rc != -1 && data != null) beginCapture(rc, data)
        return START_NOT_STICKY
    }

    private fun beginCapture(rc: Int, data: Intent) {
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(it)
        }
        val W = metrics.widthPixels
        val H = metrics.heightPixels

        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(rc, data)

        // 2-slot queue — acquireLatestImage always gets the newest frame
        reader = ImageReader.newInstance(W, H, android.graphics.PixelFormat.RGBA_8888, 2)

        reader!!.setOnImageAvailableListener({ r ->
            if (!BotState.isRunning) return@setOnImageAvailableListener
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane     = img.planes[0]
                val buf       = plane.buffer          // Direct ByteBuffer — zero copy
                val rowStride = plane.rowStride
                val pxStride  = plane.pixelStride
                val now       = System.currentTimeMillis()

                for (i in BotState.sensorPoints.indices) {
                    if (!BotState.isRunning) break
                    val pt = BotState.sensorPoints[i]
                    val x  = pt.x.toInt().coerceIn(0, W - 1)
                    val y  = pt.y.toInt().coerceIn(0, H - 1)
                    val luma = LumaDetector.getLuma(buf, x, y, rowStride, pxStride)
                    if (LumaDetector.isDark(luma) && now - lastTap[i] > DEBOUNCE) {
                        lastTap[i] = now
                        AccessibilityTapService.tapAt(pt.x, pt.y)
                    }
                }
            } finally {
                img.close()   // Always release — prevents buffer starvation
            }
        }, handler)           // High-priority background thread

        display = projection!!.createVirtualDisplay(
            "BotVD", W, H, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
    }

    override fun onDestroy() {
        BotState.isRunning = false
        display?.release()
        reader?.close()
        projection?.stop()
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNote() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Reaction Bot — Capturing")
        .setContentText("RAM-only frame processing active")
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .build()

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }
}
