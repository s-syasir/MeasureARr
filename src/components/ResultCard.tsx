import React from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {MeasurementResult} from '../hooks/useMeasurement';

interface Props {
  result: MeasurementResult;
  unit: 'cm' | 'in';
  onMeasureAgain: () => void;
  onToggleUnit: () => void;
}

export function ResultCard({result, unit, onMeasureAgain, onToggleUnit}: Props) {
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

      {result.isApproximate && (
        <Text style={styles.approxNote}>
          Approximate — tap ⚙ to calibrate for better accuracy
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
