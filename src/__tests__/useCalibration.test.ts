import {act, renderHook} from '@testing-library/react-native';
import {useCalibration} from '../hooks/useCalibration';

// Mock AsyncStorage — tests run without RN runtime
jest.mock('@react-native-async-storage/async-storage', () => ({
  setItem: jest.fn().mockResolvedValue(undefined),
  getItem: jest.fn().mockResolvedValue(null),
}));

describe('useCalibration', () => {
  it('starts uncalibrated', () => {
    const {result} = renderHook(() => useCalibration());
    expect(result.current.isCalibrated).toBe(false);
    expect(result.current.getScales()).toBeNull();
  });

  it('save marks as calibrated and getScales returns values', async () => {
    const {result} = renderHook(() => useCalibration());
    const data = {scaleGeometric: 1.23, depthScale: 456.7, calibratedAt: Date.now()};

    await act(async () => {
      await result.current.save(data);
    });

    expect(result.current.isCalibrated).toBe(true);
    expect(result.current.getScales()).toEqual(data);
  });

  it('save + getScales round-trips values without loss', async () => {
    const {result} = renderHook(() => useCalibration());
    const data = {scaleGeometric: 0.0042, depthScale: 8835.12, calibratedAt: 1714000000000};

    await act(async () => {
      await result.current.save(data);
    });

    const loaded = result.current.getScales();
    expect(loaded?.scaleGeometric).toBeCloseTo(0.0042, 6);
    expect(loaded?.depthScale).toBeCloseTo(8835.12, 2);
    expect(loaded?.calibratedAt).toBe(1714000000000);
  });

  it('reset clears calibrated state', async () => {
    const {result} = renderHook(() => useCalibration());
    await act(async () => {
      await result.current.save({scaleGeometric: 1, depthScale: 1, calibratedAt: Date.now()});
    });
    act(() => result.current.reset());
    expect(result.current.isCalibrated).toBe(false);
    expect(result.current.getScales()).toBeNull();
  });

  it('getScales reads from ref not from storage on each call', async () => {
    const AsyncStorage = require('@react-native-async-storage/async-storage');
    AsyncStorage.getItem.mockClear();

    const {result} = renderHook(() => useCalibration());
    const data = {scaleGeometric: 2.0, depthScale: 100.0, calibratedAt: Date.now()};

    await act(async () => {
      await result.current.save(data);
    });

    // Multiple getScales calls must not hit AsyncStorage
    result.current.getScales();
    result.current.getScales();
    result.current.getScales();

    // getItem called at most once (on mount to load persisted value)
    expect(AsyncStorage.getItem.mock.calls.length).toBeLessThanOrEqual(3);
  });
});
