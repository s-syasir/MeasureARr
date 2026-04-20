import {useCallback, useMemo} from 'react';
import {useFrameProcessor, VisionCameraProxy} from 'react-native-vision-camera';
import {runAtTargetFps} from 'react-native-vision-camera';
import {Worklets} from 'react-native-worklets-core';

export interface FrameProcessorOptions {
  onPlaneUpdate: (confidence: number) => void;
  onDepthReady: () => void;
  onDepthOOM: () => void;
}

export interface TapCoords {
  screenX: number;
  screenY: number;
  previewWidth: number;
  previewHeight: number;
}

// Set from ARCamera when device format is known; read by toFrameCoords on the JS thread.
export const sharedFrameMeta: {width: number; height: number} = {width: 0, height: 0};

const plugin = VisionCameraProxy.initFrameProcessorPlugin('measure');

export function useMeasureFrameProcessor(opts: FrameProcessorOptions, cameraId: string | undefined) {
  const onPlaneUpdateJS = useMemo(
    () => Worklets.createRunOnJS((confidence: number, frameW: number, frameH: number) => {
      sharedFrameMeta.width = frameW;
      sharedFrameMeta.height = frameH;
      opts.onPlaneUpdate(confidence);
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  const frameProcessor = useFrameProcessor(frame => {
    'worklet';
    runAtTargetFps(10, () => {
      'worklet';
      if (plugin == null) return;
      const result = plugin.call(frame, {
        action: 'processFrame',
        cameraId: cameraId ?? '',
      }) as Record<string, unknown> | null;
      if (result == null || result.error != null) return;
      const confidence = (result.confidence as number) ?? 0;
      onPlaneUpdateJS(confidence, frame.width, frame.height);
    });
  }, [cameraId, onPlaneUpdateJS]);

  const toFrameCoords = useCallback(
    ({screenX, screenY, previewWidth, previewHeight}: TapCoords) => {
      const {width, height} = sharedFrameMeta;
      if (width === 0 || height === 0) return null;
      return {
        u: (screenX / previewWidth) * width,
        v: (screenY / previewHeight) * height,
      };
    },
    [],
  );

  return {frameProcessor, toFrameCoords};
}
