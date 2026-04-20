import React, {useCallback, useEffect, useRef} from 'react';
import {StyleSheet, View} from 'react-native';
import {Camera, useCameraDevice, useCameraFormat, useCameraPermission} from 'react-native-vision-camera';
import {useMeasureFrameProcessor} from '../hooks/useFrameProcessor';

interface Props {
  onPlaneUpdate: (confidence: number) => void;
  onDepthReady: () => void;
  onDepthOOM: () => void;
  onCameraReady: (width: number, height: number) => void;
  children?: React.ReactNode;
}

export function ARCamera({onPlaneUpdate, onDepthReady, onDepthOOM, onCameraReady, children}: Props) {
  const {hasPermission, requestPermission} = useCameraPermission();
  const device = useCameraDevice('back');
  const format = useCameraFormat(device, [{videoResolution: {width: 1920, height: 1080}}]);
  const cameraRef = useRef<Camera>(null);

  useEffect(() => {
    if (!hasPermission) requestPermission();
  }, [hasPermission, requestPermission]);

  const {frameProcessor} = useMeasureFrameProcessor({
    onPlaneUpdate,
    onDepthReady,
    onDepthOOM,
  }, device?.id);

  const onLayout = useCallback(
    (e: {nativeEvent: {layout: {width: number; height: number}}}) => {
      const {width, height} = e.nativeEvent.layout;
      onCameraReady(width, height);
    },
    [onCameraReady],
  );

  if (!hasPermission || !device) return <View style={styles.fill} />;

  return (
    <View style={styles.fill} onLayout={onLayout}>
      <Camera
        ref={cameraRef}
        style={StyleSheet.absoluteFill}
        device={device}
        isActive
        format={format}
        frameProcessor={frameProcessor}
        pixelFormat="rgb"
        enableZoomGesture={false}
      />
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: {flex: 1, backgroundColor: '#000'},
});
