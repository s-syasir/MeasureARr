import React, {useCallback, useRef, useState} from 'react';
import {
  GestureResponderEvent,
  NativeEventEmitter,
  NativeModules,
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  ToastAndroid,
  TouchableWithoutFeedback,
  View,
} from 'react-native';
import {ARCamera} from './components/Camera';
import {CalibrationModal} from './components/CalibrationModal';
import {MeasurementOverlay} from './components/MeasurementOverlay';
import {ResultCard} from './components/ResultCard';
import {useMeasurement, Point2D, MeasurementResult} from './hooks/useMeasurement';
import {useCalibration} from './hooks/useCalibration';
import {useMeasureFrameProcessor} from './hooks/useFrameProcessor';

const {ModelDownloader} = NativeModules;

export default function App() {
  const measurement = useMeasurement();
  const calibration = useCalibration();

  const [cameraSize, setCameraSize] = useState({width: 0, height: 0});
  const [pointB, setPointB] = useState<Point2D | null>(null);
  const [showCalibModal, setShowCalibModal] = useState(false);
  const [modelReady, setModelReady] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState<number | null>(null);

  const {toFrameCoords} = useMeasureFrameProcessor({
    onPlaneUpdate: measurement.updatePlaneConfidence,
    onDepthReady: measurement.onDepthReady,
    onDepthOOM: measurement.onDepthOOM,
  });

  const previewSizeRef = useRef({width: 0, height: 0});

  // Check model on mount, download if needed
  React.useEffect(() => {
    ModelDownloader.isModelReady().then((ready: boolean) => {
      if (ready) {
        setModelReady(true);
        measurement.startScanning();
        return;
      }
      // First launch — prompt download
      const emitter = new NativeEventEmitter(ModelDownloader);
      const sub = emitter.addListener('modelDownloadProgress', (e: {bytesReceived: number; totalBytes: number}) => {
        if (e.totalBytes > 0) setDownloadProgress(e.bytesReceived / e.totalBytes);
      });
      ModelDownloader.downloadModel()
        .then(() => {
          sub.remove();
          setModelReady(true);
          setDownloadProgress(null);
          measurement.startScanning();
        })
        .catch((err: Error) => {
          sub.remove();
          ToastAndroid.show('Download failed — check connection', ToastAndroid.LONG);
        });
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Show toasts from state machine
  React.useEffect(() => {
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
        measurement.onTap(screenPoint);
      } else if (measurement.phase === 'POINT_A_SET') {
        setPointB(screenPoint);
        measurement.onTap(screenPoint);
        // Trigger measurement via plugin (async)
        performMeasurement(frameCoords.u, frameCoords.v);
      }
    },
    [measurement, toFrameCoords],
  );

  const performMeasurement = useCallback(
    async (u2: number, v2: number) => {
      const scales = calibration.getScales();
      if (!scales) {
        measurement.onResult({
          distanceMm: 0,
          distanceCm: 0,
          distanceIn: 0,
          method: 'geometric',
          isApproximate: true,
        });
        return;
      }
      // Plugin call happens on worklet thread via frame processor in production.
      // This JS-side path is used for the calibration flow only.
    },
    [calibration, measurement],
  );

  const handleCalibrate = useCallback(
    (knownMm: number) => {
      setShowCalibModal(false);
      // Plugin call: freeze plane detection, use snapshotted H, then save result
      // Full implementation in MeasureFrameProcessorPlugin.kt via 'calibrate' action
    },
    [],
  );

  const statusText = (): string => {
    if (!modelReady && downloadProgress !== null) {
      return `Downloading depth model… ${Math.round(downloadProgress * 100)}%`;
    }
    switch (measurement.phase) {
      case 'UNCALIBRATED': return 'Move phone slowly over a flat surface';
      case 'SCANNING': return 'Move phone slowly over a flat surface';
      case 'DEPTH_LOADING': return 'Preparing depth mode…';
      case 'READY': return 'Tap first point';
      case 'POINT_A_SET': return 'Tap second point';
      case 'MEASURING': return 'Measuring…';
      case 'RESULT': return '';
    }
  };

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />

      <TouchableWithoutFeedback onPress={handleTap}>
        <View style={styles.fill}>
          <ARCamera
            onPlaneUpdate={measurement.updatePlaneConfidence}
            onDepthReady={measurement.onDepthReady}
            onDepthOOM={measurement.onDepthOOM}
            onCameraReady={handleCameraReady}>

            <MeasurementOverlay
              pointA={measurement.pointA}
              pointB={pointB}
              result={measurement.result}
              unit={measurement.unit}
              width={cameraSize.width}
              height={cameraSize.height}
            />

            {/* Status hint */}
            {measurement.phase !== 'RESULT' && (
              <View style={styles.hintContainer} pointerEvents="none">
                <Text style={styles.hintText}>{statusText()}</Text>
              </View>
            )}

            {/* Plane confidence indicator */}
            {measurement.phase === 'SCANNING' && (
              <View style={styles.confidenceBar} pointerEvents="none">
                <View
                  style={[
                    styles.confidenceFill,
                    {width: `${Math.round(measurement.planeConfidence * 100)}%`},
                  ]}
                />
              </View>
            )}
          </ARCamera>
        </View>
      </TouchableWithoutFeedback>

      {/* Result card */}
      {measurement.phase === 'RESULT' && measurement.result && (
        <ResultCard
          result={measurement.result}
          unit={measurement.unit}
          isCalibrated={calibration.isCalibrated}
          onMeasureAgain={() => {
            setPointB(null);
            measurement.onMeasureAgain();
          }}
          onCalibrate={() => setShowCalibModal(true)}
          onToggleUnit={measurement.toggleUnit}
        />
      )}

      <CalibrationModal
        visible={showCalibModal}
        onCalibrate={handleCalibrate}
        onDismiss={() => setShowCalibModal(false)}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#000'},
  fill: {flex: 1},
  hintContainer: {
    position: 'absolute',
    top: 60,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  hintText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '500',
    backgroundColor: 'rgba(0,0,0,0.5)',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    overflow: 'hidden',
  },
  confidenceBar: {
    position: 'absolute',
    bottom: 20,
    left: 40,
    right: 40,
    height: 3,
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderRadius: 2,
  },
  confidenceFill: {
    height: '100%',
    backgroundColor: '#00E5FF',
    borderRadius: 2,
  },
});
