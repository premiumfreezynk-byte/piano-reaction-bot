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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Foreground service (type: mediaProjection) that:
 * 1. Creates a VirtualDisplay backed by an ImageReader (no files, RAM only)
 * 2. Processes RGBA frames at up to 60 FPS
 * 3. Reads luma at each sensor point via LumaDetector
 * 4. Fires AccessibilityTapService.tapAt() on dark detection
 *
 * CRITICAL: All frame data lives in Image.planes[0].buffer (direct ByteBuffer).
 * Image.close() is always called in finally — zero frame accumulation.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var imageThread: HandlerThread
    private lateinit var imageHandler: Handler

    // Per-lane debounce: prevent rapid re-tap on same tile
    private val lastTapTime = LongArray(4) { 0L }
    private val TAP_DEBOUNCE_MS = 80L

    companion object {
        const val CHANNEL_ID = "capture_channel"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val FPS = 60
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        imageThread = HandlerThread("ImageProcessor", Thread.MAX_PRIORITY).apply { start() }
        imageHandler = Handler(imageThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Reaction Bot — Capturing")
            .setContentText("Frame processing: RAM only, no storage writes")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(2, notification)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

        if (resultCode != -1 && projectionData != null) {
            startCapture(resultCode, projectionData)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, projectionData: Intent) {
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)

        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, projectionData)

        // 2-buffer queue: acquireLatestImage() always gets the newest frame, drops stale ones
        imageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)

        imageReader!!.setOnImageAvailableListener({ reader ->
            if (!BotState.isRunning) return@setOnImageAvailableListener

            // acquireLatestImage drops queued frames — keeps us in sync at high game speeds
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer          // Direct ByteBuffer — no copy, no allocation
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val now = System.currentTimeMillis()

                for (i in BotState.sensorPoints.indices) {
                    val pt = BotState.sensorPoints[i]
                    val px = pt.x.toInt().coerceIn(0, w - 1)
                    val py = pt.y.toInt().coerceIn(0, h - 1)
                    val luma = LumaDetector.getLuma(buffer, px, py, rowStride, pixelStride)

                    if (LumaDetector.isDark(luma) && (now - lastTapTime[i]) > TAP_DEBOUNCE_MS) {
                        lastTapTime[i] = now
                        AccessibilityTapService.tapAt(pt.x, pt.y)
                    }
                }
            } finally {
                // ALWAYS close — prevents buffer starvation and memory leaks
                image.close()
            }
        }, imageHandler)  // Runs on dedicated high-priority thread, not main thread

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ReactionBotDisplay",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        BotState.isRunning = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        imageThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
