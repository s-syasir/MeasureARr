# MeasureARr

Open-source Android AR measuring app with zero Google dependencies.

**Stack:** React Native 0.74 · Kotlin · OpenCV 4.9 · ONNX Runtime · Depth Anything V2 Metric Indoor  
**Target:** Android 8.0+ (API 26) · arm64-v8a

No ARCore. No Google Play Services. No telemetry. No network after install.

---

## How it works

```
Camera frame (RGBA_8888 or YUV_420_888, 1920×1080)
    │
    ├─► PlaneDetector (ORB+RANSAC, every 3rd frame)
    │   └─► plane confidence — used for tilt feedback
    │
    └─► Each processed frame: cached at 518×518 in a rolling 3-frame buffer
    
    At tap time: run DepthEngine.infer() on all 3 buffered frames
            Depth Anything V2 Metric Indoor (ONNX, 518×518)
            9×9 edge-aware neighbourhood median at each tap point
            (pixels where |v − centre| > 25% of centre are excluded as edge pixels)
            Median depthAtP1 and depthAtP2 across 3 frames → metres
                │
                ▼
        MeasurementEngine.measureMetricDepth()
            p = [z·(u−cx)/fx,  z·(v−cy)/fy,  z]
            dist = ‖p₂ − p₁‖ × 1000 mm
                │
                └─ × depthScale (if calibrated)
                        │
                    Result shown
```

### Depth model

**Depth Anything V2 Metric Indoor** (ONNX) — bundled in the APK as `assets/depth_metric_indoor.onnx`. Runs entirely on-device. Input: 518×518 RGB (ImageNet normalised). Output: float32 depth map in metres.

Without calibration the model overestimates absolute depth by ~2–3× for close-range scenes (known limitation of monocular depth models trained on room-scale data). Calibrating once with a known-size object corrects this with a single scale factor.

---

## Setup

### Requirements

| Tool | Version |
|------|---------|
| Node | 18+ |
| JDK | 17+ |
| Android SDK | API 26+ |
| NDK | 27+ |
| Physical device | arm64-v8a (no emulator — ONNX Runtime + OpenCV) |

### Install

```bash
npm install
```

### Build and install

The repo includes two convenience scripts:

```bash
./run-debug.sh    # bundle JS (dev mode) + assembleDebug + adb install
./run-release.sh  # bundle JS (prod mode) + assembleRelease + adb install
```

Or manually:

```bash
# Bundle the JS into the APK (required — Metro dev server not used)
npx react-native bundle --platform android --dev true --entry-file index.js \
  --bundle-output android/app/src/main/assets/index.android.bundle \
  --assets-dest android/app/src/main/res/

# Build debug APK
cd android && ./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Note:** `npx react-native run-android` builds an AAB, not an APK. Use the commands above.
> **Note:** `run-release.sh` requires a signing keystore configured in `android/app/build.gradle`.

---

## Using the app

1. **Open the app.** The depth model loads automatically from assets — no download required.
2. **Point** the phone at the surface you want to measure. Move it slowly for a moment.
3. **Tap point A** — a dot appears.
4. **Tap point B** — a line is drawn and the distance is shown.
5. **Toggle cm / in** by tapping the result.
6. **Calibrate** (recommended for first use) to correct the depth model's systematic offset.
7. **Measure again** clears the overlay and returns to the ready state.

### Tilt warning

If the phone is held more than 70° from horizontal (nearly vertical), an amber banner appears. Measurements at extreme angles are unreliable.

---

## Calibration

The depth model has a systematic scale offset that varies by device and scene distance. Calibrating once against a known-size object corrects this.

**Flow:**

1. After taking a measurement, tap **Calibrate**.
2. Select a reference object whose size you know:
   - Credit card — 85.6 mm (ISO 7810 ID-1 short side)
   - A4 short edge — 210 mm
   - A4 long edge — 297 mm
   - Custom — enter mm manually
3. The app reruns depth inference on the same two tap points, computes the ratio of known-mm to measured-mm, and saves it as `depthScale`.
4. All subsequent measurements are multiplied by `depthScale`.
5. The scale factor persists across app restarts via AsyncStorage.

**Accuracy after calibration:** ±10–20% typical (monocular depth model limitation). Best results when measuring at a similar distance to where you calibrated.

---

## Architecture

```
MeasureARr/
├── android/app/src/main/kotlin/com/measurearr/
│   ├── MeasureFrameProcessorPlugin.kt   VisionCamera v4 JSI plugin (10 fps)
│   │                                    imageToRgbaMat() — YUV_420_888 + RGBA_8888
│   │                                    rolling 3-frame buffer at 518×518 for tap-time inference
│   │                                    builds camera matrix from LENS_INTRINSIC_CALIBRATION
│   ├── MeasurementBridgeModule.kt       JS-callable measure() + calibrate() via Promise
│   │                                    calibrate() is depth-only — no plane detection needed
│   │                                    runs inference on all 3 buffered frames, medians result
│   │                                    depthScale applied to every measurement
│   ├── DepthEngine.kt                   ONNX Runtime session, ImageNet normalisation
│   │                                    9×9 edge-aware median (filters depth-edge pixels)
│   │                                    OOM → returns DepthResult.OOM
│   ├── MeasurementEngine.kt             measureMetricDepth() only — geometric path removed
│   ├── PlaneDetector.kt                 ORB (200 kp) + BFMatcher + Lowe's 0.75 + RANSAC
│   │                                    runs for tilt feedback; output not used in measurement
│   └── TiltModule.kt                    SensorManager TYPE_GRAVITY → tiltWarning events
│
├── src/
│   ├── App.tsx                          UI in persistent transparent Modal
│   │                                    (required: VisionCamera surface occludes sibling Views)
│   │                                    tapArea Pressable stops at BOTTOM_H to expose buttons
│   ├── components/
│   │   ├── Camera.tsx                   VisionCamera wrapper; 1080p format; pixelFormat="rgb"
│   │   ├── MeasurementOverlay.tsx       SVG AR line + dots + loading indicator + distance label
│   │   ├── CalibrationModal.tsx         known-object picker + custom mm input
│   │   └── TiltWarning.tsx              amber banner, NativeEventEmitter subscription
│   └── hooks/
│       ├── useMeasurement.ts            state machine (UNCALIBRATED → RESULT)
│       ├── useFrameProcessor.ts         VisionCameraProxy plugin; sharedFrameMeta singleton
│       └── useCalibration.ts            depthScale in useRef + AsyncStorage persistence
│
└── android/app/src/main/assets/
    └── depth_metric_indoor.onnx         bundled depth model (~50 MB)
```

### Key design decisions

| Decision | Rationale |
|----------|-----------|
| No ARCore / Play Services | Works on de-Googled Android; eliminates Google dependency |
| arm64-v8a only (v1) | Avoids ONNX Runtime + OpenCV ABI matrix for initial release |
| Model bundled in APK | No first-launch download flow; simpler; fully offline |
| ONNX Runtime (not TFLite) | Better support for Depth Anything V2 ONNX export format |
| Depth-only measurement path | Geometric homography path (`distanceRaw`) is scale-ambiguous per-frame — cannot be reliably calibrated across frames |
| Edge-aware 9×9 median | Pixels where `|v − centre| > 25% of centre` are on depth edges; excluding them prevents background depth contaminating the sample |
| 3-frame buffer + median | Reduces frame-to-frame depth model variance without blocking the camera thread |
| Calibration is depth-only | No plane detection required; just depth inference at the two tap points → `knownMm / measuredMm` |
| 1080p camera format | Higher resolution → higher intrinsic fx/fy → better 3D unprojection accuracy |
| Modal for all UI | VisionCamera renders in a hardware-accelerated surface that occludes all sibling Views; Modal renders in a separate window layer above it |
| Scale factors in useRef | AsyncStorage reads are async — never on the measurement hot path |
| Calibration per-session (isCalibrated) | Prevents silently wrong measurements if scene changes between app launches |
| Every-3rd-frame plane detection | Keeps plane detector running for tilt feedback without impacting frame rate |

---

## Native modules

### `MeasurementBridge`

| Method | Params | Returns |
|--------|--------|---------|
| `measure(params)` | `{u1,v1,u2,v2[,depthScale]}` | `Promise<{distanceMm,method,isApproximate}>` |
| `calibrate(params)` | `{u1,v1,u2,v2,knownMm}` | `Promise<{scaleGeometric,depthScale}>` |

Coordinates are in frame pixels (not screen dp). `toFrameCoords()` in `useFrameProcessor.ts` converts tap screen coordinates to frame coordinates using `sharedFrameMeta`.

### `TiltModule`

NativeEventEmitter. Subscribe: `addListener('tiltWarning')`. Fires `boolean` (true = >70° from horizontal).

---

## Known limitations

- **Absolute accuracy without calibration:** ~2–3× systematic overestimate (depth model trained on room-scale data, not close-range). Calibrate with a credit card to correct this.
- **Accuracy after calibration:** ±10–20% typical. Degrades at distances very different from the calibration distance (depth model scale is not perfectly linear).
- **Object edges:** Even with 9×9 median sampling, taps that land exactly on an object edge can sample a mix of foreground and background depths. Tap clearly on the object surface, not on its edge.
- **Textureless surfaces:** Plane detector (ORB) struggles on featureless white walls. This doesn't affect measurement (which uses depth), but the tilt warning may be less reliable.

---

## Future versions

| Version | Scope |
|---------|-------|
| v1 | Android · arm64-v8a · depth-based measurement |
| v2 | iOS (Swift module; RN layer already cross-platform) |
| v3 | Room scanning mode |

---

## Dependencies

| Library | Version | License | Role |
|---------|---------|---------|------|
| React Native | 0.74.5 | MIT | JS/native bridge |
| react-native-vision-camera | 4.5.1 | MIT | Camera + frame processor |
| react-native-worklets-core | — | MIT | VisionCamera worklet runtime |
| react-native-svg | 15.3.0 | MIT | AR overlay |
| @react-native-async-storage/async-storage | ^1.23.1 | MIT | Calibration persistence |
| OpenCV for Android | 4.9.0 | Apache 2.0 | ORB, RANSAC, image conversion |
| ONNX Runtime Android | — | MIT | Depth model inference |
| kotlinx-coroutines | 1.7.3 | Apache 2.0 | Async model loading |
| Depth Anything V2 Metric Indoor | — | Apache 2.0 | Monocular metric depth |

Model: *Depth Anything V2* (Yang et al., 2024)

---

## Privacy

| Permission | Why |
|------------|-----|
| `CAMERA` | AR measurement |

- All processing is on-device
- No network calls after install
- No analytics, crash reporting, or telemetry
- Measurements never leave the phone

---

## License

Apache 2.0
