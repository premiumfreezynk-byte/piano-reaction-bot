package com.reactionbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MainActivity — handles all permission grants in order:
 *  1. SYSTEM_ALERT_WINDOW  (overlay)
 *  2. MediaProjection       (screen capture pop-up → "Start now")
 *  3. Accessibility hint    (user must enable manually in Settings)
 *
 * Uses ActivityResultLauncher (modern API) — no deprecated onActivityResult.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnLaunch: Button
    private lateinit var mpManager: MediaProjectionManager

    // ── Launchers ──────────────────────────────────────────────────────────────

    private val overlayLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture()
            } else {
                showToast("Overlay permission denied. Tap LAUNCH again.")
            }
        }

    private val projectionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                // Save for OverlayService to use when START is tapped
                OverlayService.projectionResultCode = result.resultCode
                OverlayService.projectionData     = result.data

                // Start the floating overlay
                ContextCompat.startForegroundService(
                    this, Intent(this, OverlayService::class.java)
                )
                showToast("Overlay started! Tap ▶ START when ready.")
                finish()
            } else {
                showToast("Screen capture denied — tap LAUNCH and choose \"Start now\".")
            }
        }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mpManager  = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.statusText)
        btnLaunch  = findViewById(R.id.btnLaunch)
        btnLaunch.setOnClickListener { startPermissionFlow() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // ── Permission flow ────────────────────────────────────────────────────────

    private fun startPermissionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            showToast("Step 1/3 — Allow overlay permission")
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        showToast("Step 2/3 — Tap \"Start now\" in the next dialog")
        projectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    // ── Status display ─────────────────────────────────────────────────────────

    private fun updateStatus() {
        val overlayOk  = Settings.canDrawOverlays(this)
        val captureOk  = OverlayService.projectionResultCode != -1
        val accessOk   = isAccessibilityEnabled()

        statusText.text = buildString {
            appendLine("Step 1 — Overlay permission")
            appendLine(if (overlayOk)  "  ✅ Granted" else "  ❌ Not granted yet")
            appendLine()
            appendLine("Step 2 — Screen capture")
            appendLine(if (captureOk)  "  ✅ Ready"   else "  ❌ Not granted yet")
            appendLine()
            appendLine("Step 3 — Accessibility service")
            appendLine(if (accessOk)   "  ✅ Enabled" else "  ❌ Not enabled")
            if (!accessOk) {
                appendLine()
                appendLine("  → Settings › Accessibility › Installed Apps")
                appendLine("    › Reaction Bot › toggle ON")
            }
            appendLine()
            if (overlayOk && captureOk && accessOk) {
                appendLine("🟢 ALL READY — tap ▶ START on the overlay!")
            } else {
                appendLine("👆 Tap LAUNCH BOT to complete setup.")
            }
        }

        btnLaunch.text = when {
            !overlayOk  -> "LAUNCH BOT  (grant overlay)"
            !captureOk  -> "LAUNCH BOT  (grant screen capture)"
            else        -> "RELAUNCH OVERLAY"
        }
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

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
