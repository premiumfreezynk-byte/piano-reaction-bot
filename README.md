# 🎹 Piano Reaction Bot — Android APK

High-speed Piano Tiles automation bot using MediaProjection + AccessibilityService.

## How It Works

| Component | Description |
|---|---|
| `OverlayService` | Floating Command Center (START / STOP / CALIBRATE) |
| `ScreenCaptureService` | MediaProjection → ImageReader at 60 FPS — **RAM only, no file writes** |
| `LumaDetector` | Reads RGBA ByteBuffer directly, computes luma per sensor point |
| `AccessibilityTapService` | `dispatchGesture()` — sub-millisecond synthetic tap injection |

## Download APK

1. Go to the **Actions** tab above
2. Click the latest **Build Debug APK** run
3. Download **PianoReactionBot-arm64-debug** from Artifacts

## Setup on Device

### Step 1 — Install the APK
```
adb install app-arm64-v8a-debug.apk
# or just tap the downloaded file with "Install unknown apps" allowed
```

### Step 2 — Grant Permissions (in app)
Launch **Reaction Bot** and tap **LAUNCH BOT**. Grant:
- **Overlay permission** (Draw over other apps)
- **Screen capture** (one-time dialog)

### Step 3 — Enable Accessibility Service
```
Settings → Accessibility → Installed Apps → Reaction Bot → Enable
```

### Step 4 — Calibrate
Open your Piano Tiles game, then tap the **Reaction Bot overlay → ⊕ CAL**.
Drag the 4 orange dots to align with each lane. Tap CAL again to save.

### Step 5 — Start
Tap **▶ START**. The bot reads luma at your sensor points at 60 FPS and taps automatically when a dark tile is detected.

## In-Memory Processing Confirmation

```
✓ ImageReader uses PixelFormat.RGBA_8888 in RAM-backed ByteBuffer
✓ No files written to storage — all pixel data stays in Image.planes[0].buffer
✓ Image.close() called after every frame — zero accumulation
✓ VirtualDisplay feeds directly to ImageReader surface (no disk I/O)
✓ High-priority HandlerThread for image processing (no main-thread blocking)
✓ Per-lane debounce (80ms) prevents duplicate taps on same tile
```

## Architecture

```
MainActivity
    └── OverlayService (foreground, TYPE_APPLICATION_OVERLAY)
            └── ScreenCaptureService (foreground, mediaProjection type)
                    └── ImageReader ←── VirtualDisplay ←── MediaProjection
                            └── LumaDetector (ByteBuffer, no alloc)
                                    └── AccessibilityTapService.tapAt()
                                            └── dispatchGesture()
```

## Requirements
- Android 8.0+ (API 26+)
- ARM64-v8a device
- 2GB+ RAM recommended
