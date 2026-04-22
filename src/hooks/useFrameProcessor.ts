import {useCallback, useRef} from 'react';
import {useFrameProcessor} from 'react-native-vision-camera';
import {runAtTargetFps, runAsync} from 'react-native-vision-camera';

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

export function useMeasureFrameProcessor(opts: FrameProcessorOptions) {
  const frameMetaRef = useRef<{width: number; height: number} | null>(null);

  const frameProcessor = useFrameProcessor(frame => {
    'worklet';

    frameMetaRef.current = {width: frame.width, height: frame.height};

    runAtTargetFps(10, () => {
      'worklet';
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const plugin = (global as any).__MeasurePlugin;
      if (!plugin) return;

      const result = plugin.call(frame, {action: 'processFrame'}) as {
        confidence?: number;
        skipped?: boolean;
        error?: string;
      };

      if (result?.error) return;
      if (!result?.skipped && result?.confidence !== undefined) {
        runAsync(frame, () => {
          'worklet';
          opts.onPlaneUpdate(result.confidence!);
        });
      }
    });
  }, [opts.onPlaneUpdate]);

  // Convert screen tap → frame coordinates before sending to Kotlin
  const toFrameCoords = useCallback(
    ({screenX, screenY, previewWidth, previewHeight}: TapCoords) => {
      const meta = frameMetaRef.current;
      if (!meta) return null;
      return {
        u: (screenX / previewWidth) * meta.width,
        v: (screenY / previewHeight) * meta.height,
      };
    },
    [],
  );

  return {frameProcessor, toFrameCoords, frameMetaRef};
}
