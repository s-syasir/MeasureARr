import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
  GestureResponderEvent,
  Modal,
  NativeModules,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  ToastAndroid,
  View,
} from 'react-native';
import {ARCamera} from './components/Camera';
import {CalibrationModal} from './components/CalibrationModal';
import {MeasurementOverlay} from './components/MeasurementOverlay';
import {TiltWarning} from './components/TiltWarning';
import {useMeasurement, Point2D} from './hooks/useMeasurement';
import {useCalibration} from './hooks/useCalibration';
import {sharedFrameMeta, TapCoords} from './hooks/useFrameProcessor';

const {MeasurementBridge} = NativeModules;

export default function App() {
  const measurement = useMeasurement();
  const calibration = useCalibration();

  const [cameraSize, setCameraSize] = useState({width: 0, height: 0});
  const [pointB, setPointB] = useState<Point2D | null>(null);
  const [showCalibModal, setShowCalibModal] = useState(false);

  const previewSizeRef = useRef({width: 0, height: 0});

  const toFrameCoords = useCallback(({screenX, screenY, previewWidth, previewHeight}: TapCoords) => {
    const {width, height} = sharedFrameMeta;
    if (width === 0 || height === 0) return null;
    return {
      u: (screenX / previewWidth) * width,
      v: (screenY / previewHeight) * height,
    };
  }, []);
  const pointAFrameRef = useRef<{u: number; v: number} | null>(null);
  const pointBFrameRef = useRef<{u: number; v: number} | null>(null);

  // Depth model is bundled in the APK — start scanning immediately.
  useEffect(() => {
    measurement.startScanning();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Show toasts from state machine
  useEffect(() => {
    if (measurement.toastMessage) {
      ToastAndroid.show(measurement.toastMessage, ToastAndroid.SHORT);
      measurement.clearToast();
    }
  }, [measurement.toastMessage, measurement]);

  const handleCameraReady = useCallback((w: number, h: number) => {
    setCameraSize({width: w, height: h});
    previewSizeRef.current = {width: w, height: h};
  }, []);

  const handleTap = useCallback(
    (e: GestureResponderEvent) => {
      const {locationX: sx, locationY: sy} = e.nativeEvent;
      const {width: pw, height: ph} = previewSizeRef.current;
      const frameCoords = toFrameCoords({screenX: sx, screenY: sy, previewWidth: pw, previewHeight: ph});
      if (!frameCoords) return;

      const screenPoint: Point2D = {x: sx, y: sy};

      if (measurement.phase === 'READY') {
        pointAFrameRef.current = frameCoords;
        measurement.onTap(screenPoint);
      } else if (measurement.phase === 'POINT_A_SET') {
        pointBFrameRef.current = frameCoords;
        setPointB(screenPoint);
        measurement.onTap(screenPoint);
        performMeasurement(frameCoords.u, frameCoords.v);
      }
    },
    [measurement, toFrameCoords],
  );

  const performMeasurement = useCallback(
    async (u2: number, v2: number) => {
      const ptA = pointAFrameRef.current;
      if (!ptA) {
        measurement.onMeasureAgain();
        return;
      }
      const scales = calibration.getScales();
      // Pass calibration scales only when present — bridge uses geometric path as
      // an upgrade over metric depth when the plane is well-detected.
      const measureParams: Record<string, number> = {u1: ptA.u, v1: ptA.v, u2, v2};
      if (scales) {
        measureParams.scaleGeometric = scales.scaleGeometric;
        measureParams.depthScale = scales.depthScale;
      }
      try {
        const result = await MeasurementBridge.measure(measureParams);
        const distanceMm: number = result.distanceMm;
        measurement.onResult({
          distanceMm,
          distanceCm: distanceMm / 10,
          distanceIn: distanceMm / 25.4,
          method: result.method,
          isApproximate: result.isApproximate,
        });
      } catch (err: any) {
        ToastAndroid.show(`Measurement failed: ${err.message ?? err}`, ToastAndroid.SHORT);
        measurement.onMeasureAgain();
      }
    },
    [calibration, measurement],
  );

  const handleCalibrate = useCallback(
    async (knownMm: number) => {
      setShowCalibModal(false);
      const ptA = pointAFrameRef.current;
      if (!ptA) {
        ToastAndroid.show('Tap two points first, then calibrate', ToastAndroid.SHORT);
        return;
      }
      const ptB = pointBFrameRef.current;
      if (!ptB) {
        ToastAndroid.show('Measure an object first, then calibrate', ToastAndroid.SHORT);
        return;
      }
      try {
        const result = await MeasurementBridge.calibrate({
          u1: ptA.u,
          v1: ptA.v,
          u2: ptB.u,
          v2: ptB.v,
          knownMm,
        });
        await calibration.save({
          scaleGeometric: result.scaleGeometric,
          depthScale: result.depthScale,
          calibratedAt: Date.now(),
        });
        ToastAndroid.show('Calibrated!', ToastAndroid.SHORT);
      } catch (err: any) {
        ToastAndroid.show(`Calibration failed: ${err.message ?? err}`, ToastAndroid.SHORT);
      }
    },
    [calibration],
  );

  const statusText = (): string => {
    switch (measurement.phase) {
      case 'UNCALIBRATED':
      case 'SCANNING':
      case 'DEPTH_LOADING': return 'Move phone slowly over a flat surface';
      case 'READY':         return 'Tap first point';
      case 'POINT_A_SET':   return 'Tap second point';
      case 'MEASURING':     return 'Measuring…';
      case 'RESULT':        return '';
    }
  };

  const result = measurement.result;
  const isResult = measurement.phase === 'RESULT' && result != null;

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />

      {/* Camera — full screen. All UI must be children of ARCamera to render above the surface. */}
      <ARCamera
        onPlaneUpdate={measurement.updatePlaneConfidence}
        onDepthReady={measurement.onDepthReady}
        onDepthOOM={measurement.onDepthOOM}
        onCameraReady={handleCameraReady}>

        <MeasurementOverlay
          pointA={measurement.pointA}
          pointB={pointB}
          result={measurement.result}
          isLoading={measurement.phase === 'MEASURING'}
          unit={measurement.unit}
          width={cameraSize.width}
          height={cameraSize.height}
        />

        <TiltWarning />

      </ARCamera>

      {/*
        Modal renders above the camera surface on Android.
        The Pressable tap area is here (not in ARCamera) so it can capture touches
        without being obscured by the camera surface z-ordering.
      */}
      <Modal transparent visible animationType="none" statusBarTranslucent>
        {/* Transparent tap area — covers everything above the bottom panel */}
        <Pressable style={styles.tapArea} onPress={handleTap} />

        {/* Hint pill */}
        {measurement.phase !== 'RESULT' && (
          <View style={styles.hintContainer} pointerEvents="none">
            <Text style={styles.hintText}>{statusText()}</Text>
          </View>
        )}

        {/* Bottom panel — absolute, anchored to bottom of Modal window */}
        <View style={styles.bottomPanel}>
          {isResult ? (
            <>
              <Pressable onPress={measurement.toggleUnit} style={styles.resultDistance}>
                <Text style={styles.resultValue}>
                  {result.isApproximate ? '~' : ''}
                  {measurement.unit === 'cm'
                    ? `${result.distanceCm.toFixed(1)} cm`
                    : `${result.distanceIn.toFixed(2)} in`}
                </Text>
                {calibration.lastCalibratedAt === null ? (
                  <Text style={styles.resultCalibrationHint}>
                    Tap Calibrate below for accurate results
                  </Text>
                ) : (
                  <Text style={styles.resultMethod}>
                    {calibration.isCalibrated ? 'calibrated' : 'uncalibrated'}
                  </Text>
                )}
              </Pressable>
              <View style={styles.bottomActions}>
                <Pressable style={styles.btnSecondary} onPress={() => setShowCalibModal(true)}>
                  <Text style={styles.btnSecondaryText}>Calibrate</Text>
                </Pressable>
                <Pressable style={styles.btnPrimary} onPress={() => { setPointB(null); measurement.onMeasureAgain(); }}>
                  <Text style={styles.btnPrimaryText}>Measure again</Text>
                </Pressable>
              </View>
            </>
          ) : (
            <View style={styles.bottomActions}>
              <Pressable style={styles.btnSecondary} onPress={() => setShowCalibModal(true)}>
                <Text style={styles.btnSecondaryText}>Calibrate</Text>
              </Pressable>
              <Pressable style={styles.btnSecondary} onPress={measurement.toggleUnit}>
                <Text style={styles.btnSecondaryText}>{measurement.unit.toUpperCase()}</Text>
              </Pressable>
            </View>
          )}
        </View>
      </Modal>

      <CalibrationModal
        visible={showCalibModal}
        onCalibrate={handleCalibrate}
        onDismiss={() => setShowCalibModal(false)}
      />
    </View>
  );
}

const NAV_BAR_H = 32; // gesture navigation zone (dp) — Pixel 8 mandatorySystemGestures=84px@420dpi
const BOTTOM_H = 130 + NAV_BAR_H; // panel sits fully above the gesture area

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#000'},
  tapArea: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: BOTTOM_H,
  },
  hintContainer: {
    position: 'absolute',
    top: 56,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  hintText: {
    color: '#FFF',
    fontSize: 15,
    fontWeight: '500',
    backgroundColor: 'rgba(0,0,0,0.55)',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    overflow: 'hidden',
  },
  bottomPanel: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: BOTTOM_H,
    backgroundColor: '#111',
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: NAV_BAR_H + 12, // keep buttons above gesture zone
    justifyContent: 'space-between',
  },
  resultDistance: {alignItems: 'center', marginBottom: 8},
  resultValue: {color: '#FFF', fontSize: 48, fontWeight: '700', letterSpacing: -1},
  resultMethod: {color: '#888', fontSize: 13, marginTop: 2},
  resultCalibrationHint: {color: '#FF9F0A', fontSize: 12, marginTop: 3},
  bottomActions: {flexDirection: 'row', gap: 10},
  btnPrimary: {
    flex: 2,
    backgroundColor: '#00E5FF',
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
  },
  btnPrimaryText: {color: '#000', fontWeight: '700', fontSize: 15},
  btnSecondary: {
    flex: 1,
    backgroundColor: '#222',
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
  },
  btnSecondaryText: {color: '#FFF', fontSize: 15},
});
