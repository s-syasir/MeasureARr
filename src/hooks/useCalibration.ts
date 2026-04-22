import {useCallback, useEffect, useRef, useState} from 'react';
import {NativeModules} from 'react-native';

// SharedPreferences keys
const KEY_SCALE_GEOMETRIC = 'cal_scale_geometric';
const KEY_DEPTH_SCALE = 'cal_depth_scale';
const KEY_CALIBRATED_AT = 'cal_calibrated_at';

export type KnownObject =
  | 'credit_card'   // 85.6mm
  | 'a4_short'      // 210mm
  | 'a4_long'       // 297mm
  | 'custom';

export const KNOWN_OBJECT_MM: Record<Exclude<KnownObject, 'custom'>, number> = {
  credit_card: 85.6,
  a4_short: 210,
  a4_long: 297,
};

export interface CalibrationData {
  scaleGeometric: number;
  depthScale: number;
  calibratedAt: number; // epoch ms
}

export function useCalibration() {
  // Cache scale factors in memory (useRef) — never read SharedPreferences in measurement hot path
  const scaleRef = useRef<CalibrationData | null>(null);
  const [isCalibrated, setIsCalibrated] = useState(false);
  const [lastCalibratedAt, setLastCalibratedAt] = useState<number | null>(null);

  // Load persisted calibration once on mount — hint only; each session starts uncalibrated
  useEffect(() => {
    const load = async () => {
      try {
        const stored = await loadFromStorage();
        if (stored) {
          scaleRef.current = stored;
          setLastCalibratedAt(stored.calibratedAt);
          // Note: isCalibrated stays false — per-session recalibration required.
          // Stored value is surfaced as "Last calibrated X ago" hint only.
        }
      } catch {
        // Ignore — first launch
      }
    };
    load();
  }, []);

  const save = useCallback(async (data: CalibrationData) => {
    scaleRef.current = data;
    setIsCalibrated(true);
    setLastCalibratedAt(data.calibratedAt);
    await saveToStorage(data);
  }, []);

  const getScales = useCallback(() => scaleRef.current, []);

  const reset = useCallback(() => {
    scaleRef.current = null;
    setIsCalibrated(false);
  }, []);

  return {
    isCalibrated,
    lastCalibratedAt,
    save,
    getScales,
    reset,
  };
}

// Thin AsyncStorage wrappers — isolated here so the measurement path never touches storage
async function saveToStorage(data: CalibrationData): Promise<void> {
  // React Native's AsyncStorage is the right tool; importing inline to avoid
  // requiring it at module level (keeps this hook testable without RN setup)
  const {AsyncStorage} = require('@react-native-async-storage/async-storage');
  await Promise.all([
    AsyncStorage.setItem(KEY_SCALE_GEOMETRIC, String(data.scaleGeometric)),
    AsyncStorage.setItem(KEY_DEPTH_SCALE, String(data.depthScale)),
    AsyncStorage.setItem(KEY_CALIBRATED_AT, String(data.calibratedAt)),
  ]);
}

async function loadFromStorage(): Promise<CalibrationData | null> {
  const {AsyncStorage} = require('@react-native-async-storage/async-storage');
  const [sg, ds, at] = await Promise.all([
    AsyncStorage.getItem(KEY_SCALE_GEOMETRIC),
    AsyncStorage.getItem(KEY_DEPTH_SCALE),
    AsyncStorage.getItem(KEY_CALIBRATED_AT),
  ]);
  if (!sg || !ds || !at) return null;
  const scaleGeometric = parseFloat(sg);
  const depthScale = parseFloat(ds);
  const calibratedAt = parseInt(at, 10);
  if (isNaN(scaleGeometric) || isNaN(depthScale) || isNaN(calibratedAt)) return null;
  return {scaleGeometric, depthScale, calibratedAt};
}
