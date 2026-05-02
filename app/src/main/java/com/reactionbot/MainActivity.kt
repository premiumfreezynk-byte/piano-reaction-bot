package com.reactionbot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_OVERLAY = 100
        private const val REQ_PROJECTION = 101
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        findViewById<Button>(R.id.btnLaunch).setOnClickListener { checkPermissions() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = isAccessibilityEnabled()
        statusText.text = buildString {
            appendLine("① Overlay permission:      ${if (overlayOk) "✓ Granted" else "✗ Not granted"}")
            appendLine("② Screen capture:          ${if (OverlayService.projectionResultCode != -1) "✓ Ready" else "✗ Not granted yet"}")
            appendLine("③ Accessibility service:   ${if (accessOk) "✓ Enabled" else "✗ Not enabled"}")
            if (!overlayOk) appendLine("\n→ Tap LAUNCH to grant overlay permission.")
            if (!accessOk) appendLine("\n→ Go to Settings › Accessibility › Installed Apps\n   › Reaction Bot › Enable")
        }
    }

    private fun checkPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val target = "$packageName/${AccessibilityTapService::class.java.name}"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(':').any { it.equals(target, ignoreCase = true) }
        } catch (_: Exception) { false }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) requestProjection()
                else Toast.makeText(this, "Overlay permission is required.", Toast.LENGTH_LONG).show()
            }
            REQ_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Store in OverlayService companion — shared across app lifetime
                    OverlayService.projectionResultCode = resultCode
                    OverlayService.projectionData = data
                    startForegroundService(Intent(this, OverlayService::class.java))
                    Toast.makeText(this, "Bot overlay launched! Tap START to begin.", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
