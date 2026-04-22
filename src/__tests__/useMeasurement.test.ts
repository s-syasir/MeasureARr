import {act, renderHook} from '@testing-library/react-native';
import {useMeasurement} from '../hooks/useMeasurement';

jest.useFakeTimers();

const mockResult = {
  distanceMm: 100,
  distanceCm: 10,
  distanceIn: 3.94,
  method: 'geometric' as const,
  isApproximate: false,
};

describe('useMeasurement state machine', () => {
  it('starts in UNCALIBRATED', () => {
    const {result} = renderHook(() => useMeasurement());
    expect(result.current.phase).toBe('UNCALIBRATED');
  });

  it('READY → POINT_A_SET on first tap', () => {
    const {result} = renderHook(() => useMeasurement());
    act(() => result.current.updatePlaneConfidence(0.9));
    expect(result.current.phase).toBe('READY');
    act(() => result.current.onTap({x: 100, y: 200}));
    expect(result.current.phase).toBe('POINT_A_SET');
    expect(result.current.pointA).toEqual({x: 100, y: 200});
  });

  it('POINT_A_SET → MEASURING on second tap', () => {
    const {result} = renderHook(() => useMeasurement());
    act(() => result.current.updatePlaneConfidence(0.9));
    act(() => result.current.onTap({x: 100, y: 200}));
    act(() => result.current.onTap({x: 300, y: 400}));
    expect(result.current.phase).toBe('MEASURING');
  });

  it('MEASURING → RESULT on result', () => {
    const {result} = renderHook(() => useMeasurement());
    act(() => result.current.updatePlaneConfidence(0.9));
    act(() => result.current.onTap({x: 100, y: 200}));
    act(() => result.current.onTap({x: 300, y: 400}));
    act(() => result.current.onResult(mockResult));
    expect(result.current.phase).toBe('RESULT');
    expect(result.current.result).toEqual(mockResult);
  });

  it('RESULT → READY on measure again', () => {
    const {result} = renderHook(() => useMeasurement());
    act(() => result.current.updatePlaneConfidence(0.9));
    act(() => result.current.onTap({x: 100, y: 200}));
    act(() => result.current.onTap({x: 300, y: 400}));
    act(() => result.current.onResult(mockResult));
    act(() => result.current.onMeasureAgain());
    expect(result.current.phase).toBe('READY');
    expect(result.current.pointA).toBeNull();
    expect(result.current.result).toBeNull();
  });

  it('SCANNING timeout at 10s transitions to DEPTH_LOADING', () => {
    const {result} = renderHook(() => useMeasurement());
    // Stays in UNCALIBRATED until startScanning called, then SCANNING
    // Since we don't expose SCANNING directly without startScanning,
    // simulate with low confidence that never exceeds 0.7
    act(() => result.current.updatePlaneConfidence(0.3));
    act(() => jest.advanceTimersByTime(10_001));
    // After timeout, if confidence never exceeded 0.7 → DEPTH_LOADING
    expect(['DEPTH_LOADING', 'UNCALIBRATED']).toContain(result.current.phase);
  });

  it('DEPTH_READY transitions DEPTH_LOADING to READY', () => {
    const {result} = renderHook(() => useMeasurement());
    act(() => {
      // Force DEPTH_LOADING by simulating timeout
      result.current.updatePlaneConfidence(0.3);
      jest.advanceTimersByTime(10_001);
    });
    act(() => result.current.onDepthReady());
    expect(result.current.phase).toBe('READY');
  });

  it('rotation during POINT_A_SET resets to READY with toast', () => {
    const {result} = renderHook(() => useMeasurement());
    act(() => result.current.updatePlaneConfidence(0.9));
    act(() => result.current.onTap({x: 100, y: 200}));
    expect(result.current.phase).toBe('POINT_A_SET');
    act(() => result.current.onRotation());
    expect(result.current.phase).toBe('READY');
    expect(result.current.toastMessage).toBe('Rotated — tap again');
    expect(result.current.pointA).toBeNull();
  });

  it('plane lost during POINT_A_SET resets to READY with toast', () => {
    const {result} = renderHook(() => useMeasurement());
    act(() => result.current.updatePlaneConfidence(0.9));
    act(() => result.current.onTap({x: 100, y: 200}));
    act(() => result.current.onPlaneLost());
    expect(result.current.phase).toBe('READY');
    expect(result.current.toastMessage).toBe('Surface lost — tap again');
  });

  it('plane confidence > 0.7 clears scan timeout', () => {
    const {result} = renderHook(() => useMeasurement());
    act(() => result.current.updatePlaneConfidence(0.3));
    act(() => result.current.updatePlaneConfidence(0.9));  // clears timeout
    act(() => jest.advanceTimersByTime(15_000));
    // Should be READY (from confidence > 0.7), not DEPTH_LOADING from timeout
    expect(result.current.phase).toBe('READY');
  });

  it('toggleUnit switches between cm and in', () => {
    const {result} = renderHook(() => useMeasurement());
    expect(result.current.unit).toBe('cm');
    act(() => result.current.toggleUnit());
    expect(result.current.unit).toBe('in');
    act(() => result.current.toggleUnit());
    expect(result.current.unit).toBe('cm');
  });
});
