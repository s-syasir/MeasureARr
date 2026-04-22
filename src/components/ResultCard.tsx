import React from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {MeasurementResult} from '../hooks/useMeasurement';

interface Props {
  result: MeasurementResult;
  unit: 'cm' | 'in';
  isCalibrated: boolean;
  onMeasureAgain: () => void;
  onCalibrate: () => void;
  onToggleUnit: () => void;
}

export function ResultCard({result, unit, isCalibrated, onMeasureAgain, onCalibrate, onToggleUnit}: Props) {
  const distance =
    unit === 'cm'
      ? `${result.distanceCm.toFixed(1)} cm`
      : `${result.distanceIn.toFixed(2)} in`;

  const prefix = result.isApproximate ? '~' : '';
  const methodLabel = result.method === 'depth' ? 'depth estimate' : 'plane geometry';

  return (
    <View style={styles.card}>
      <Pressable onPress={onToggleUnit}>
        <Text style={styles.distance}>
          {prefix}
          {distance}
        </Text>
      </Pressable>

      <Text style={styles.method}>{methodLabel}</Text>

      {!isCalibrated && (
        <View style={styles.calibrateBanner}>
          <Text style={styles.calibrateText}>
            Showing approximate result. Calibrate for ±2cm accuracy.
          </Text>
          <Pressable onPress={onCalibrate} style={styles.calibrateBtn}>
            <Text style={styles.calibrateBtnText}>Calibrate now</Text>
          </Pressable>
        </View>
      )}

      {result.isApproximate && isCalibrated && (
        <Text style={styles.approxNote}>
          Non-flat surface — ±5-10cm accuracy
        </Text>
      )}

      <Pressable onPress={onMeasureAgain} style={styles.againBtn}>
        <Text style={styles.againBtnText}>Measure again</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    position: 'absolute',
    bottom: 40,
    left: 20,
    right: 20,
    backgroundColor: 'rgba(0,0,0,0.82)',
    borderRadius: 16,
    padding: 20,
    alignItems: 'center',
  },
  distance: {
    color: '#FFFFFF',
    fontSize: 48,
    fontWeight: '700',
    letterSpacing: -1,
  },
  method: {
    color: '#AAAAAA',
    fontSize: 13,
    marginTop: 2,
  },
  calibrateBanner: {
    backgroundColor: 'rgba(255,200,0,0.15)',
    borderRadius: 10,
    padding: 12,
    marginTop: 14,
    width: '100%',
    alignItems: 'center',
  },
  calibrateText: {
    color: '#FFD600',
    fontSize: 13,
    textAlign: 'center',
  },
  calibrateBtn: {
    marginTop: 8,
    backgroundColor: '#FFD600',
    borderRadius: 8,
    paddingHorizontal: 16,
    paddingVertical: 6,
  },
  calibrateBtnText: {
    color: '#000000',
    fontWeight: '600',
    fontSize: 13,
  },
  approxNote: {
    color: '#AAAAAA',
    fontSize: 12,
    marginTop: 6,
  },
  againBtn: {
    marginTop: 16,
    backgroundColor: '#00E5FF',
    borderRadius: 10,
    paddingHorizontal: 28,
    paddingVertical: 10,
  },
  againBtnText: {
    color: '#000000',
    fontWeight: '700',
    fontSize: 15,
  },
});
