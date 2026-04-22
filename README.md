# MeasureARr

Open-source Android AR measuring app with zero Google dependencies.

**Accuracy:** ±1-3cm on flat surfaces (calibrated) · ±5-10cm depth fallback
**Stack:** React Native 0.74 · Kotlin · OpenCV 4.9 · Depth Anything V2 TFLite
**Target:** Android 8.0+ (API 26) · arm64-v8a · Pixel 8 primary

No ARCore. No Google Play Services. No telemetry.

---

## How it works

```
Camera frame
    │
    ├── OpenCV ORB + RANSAC ──► plane_confidence > 0.7?
    │                                    │
    │                            YES     │     NO
    │                            │       │
    │               Geometric 3D proj    │  Depth Anything V2 TFLite
    │                  (±1-3cm)          │     (±5-10cm, "~" label)
    │                                    │
               User taps two points → distance displayed
```

## Setup

### Requirements
- Node 18+
- JDK 17+
- Android SDK (API 26+), NDK 27+
- Physical Android device (arm64)

### Install

```bash
npm install
cd android && ./gradlew :app:assembleDebug
```

### First launch
On first launch the app downloads the Depth Anything V2 Small model (~50MB) from GitHub Releases.
The download is hash-verified. After that, the app works fully offline.

## Calibration

Calibration is **opt-in** — you get approximate results without it, accurate results after.

1. After your first measurement, tap **Calibrate now**
2. Select a reference object (credit card, A4 paper, or custom size)
3. Tap its two ends in the camera view
4. Done — subsequent measurements use the calibrated scale

Calibration is per-session. Each app launch starts in approximate mode;
recalibrate to get accurate mode. "Last calibrated X ago" is shown as a hint.

## Architecture

```
MeasureARr/
├── android/app/src/main/kotlin/com/measurearr/
│   ├── MeasureFrameProcessorPlugin.kt  # VisionCamera JSI plugin
│   ├── PlaneDetector.kt                # OpenCV ORB + RANSAC (Mat.release() critical)
│   ├── DepthEngine.kt                  # TFLite lazy-load + inference (pre-alloc ByteBuffer)
│   ├── MeasurementEngine.kt            # hybrid routing + projection math
│   ├── ModelDownloader.kt              # one-time download + SHA-256 check
│   └── ModelDownloaderModule.kt        # RN bridge for JS download control
├── src/
│   ├── components/
│   │   ├── Camera.tsx                  # VisionCamera wrapper + frame processor
│   │   ├── MeasurementOverlay.tsx      # AR line + dots + label (react-native-svg)
│   │   ├── CalibrationModal.tsx        # known-object calibration UI
│   │   └── ResultCard.tsx              # result + method label + unit toggle
│   ├── hooks/
│   │   ├── useMeasurement.ts           # state machine (UNCALIBRATED → RESULT)
│   │   ├── useFrameProcessor.ts        # bridge to Kotlin plugin + coord mapping
│   │   └── useCalibration.ts           # scale factor management (ref-cached)
│   └── App.tsx
└── .github/workflows/ci.yml
```

## Privacy

- **CAMERA** permission: required for AR
- **INTERNET** permission: declared permanently (Android doesn't allow runtime revocation), used only once for model download
- Zero analytics, zero crash reporting, zero telemetry
- All measurements processed on-device, never leave the phone

## Tests

```bash
# TypeScript
npm test

# Kotlin
cd android && ./gradlew :app:test
```

## Roadmap

| Version | Features |
|---------|----------|
| v1 | Android (this) |
| v2 | iOS (Swift module wrapping ARKit, RN layer already portable) · VIO metric scale (IMU sweep) |
| v3 | Room scanning mode |

## License

Apache 2.0
