import React from 'react';
import {StyleSheet, View} from 'react-native';
import Svg, {Circle, Line, Text as SvgText} from 'react-native-svg';
import {Point2D, MeasurementResult} from '../hooks/useMeasurement';

interface Props {
  pointA: Point2D | null;
  pointB: Point2D | null;
  result: MeasurementResult | null;
  unit: 'cm' | 'in';
  width: number;
  height: number;
}

export function MeasurementOverlay({pointA, pointB, result, unit, width, height}: Props) {
  const formatDistance = (r: MeasurementResult) => {
    const prefix = r.isApproximate ? '~' : '';
    if (unit === 'cm') {
      return `${prefix}${(r.distanceCm).toFixed(1)} cm`;
    }
    return `${prefix}${(r.distanceIn).toFixed(2)} in`;
  };

  const midX = pointA && pointB ? (pointA.x + pointB.x) / 2 : 0;
  const midY = pointA && pointB ? (pointA.y + pointB.y) / 2 - 20 : 0;

  return (
    <View style={[StyleSheet.absoluteFillObject, {width, height}]} pointerEvents="none">
      <Svg width={width} height={height}>
        {/* Measurement line */}
        {pointA && pointB && (
          <Line
            x1={pointA.x} y1={pointA.y}
            x2={pointB.x} y2={pointB.y}
            stroke="#00E5FF"
            strokeWidth={2}
            strokeDasharray="6,4"
          />
        )}

        {/* Point A dot */}
        {pointA && (
          <>
            <Circle cx={pointA.x} cy={pointA.y} r={10} fill="#00E5FF" opacity={0.3} />
            <Circle cx={pointA.x} cy={pointA.y} r={5} fill="#00E5FF" />
          </>
        )}

        {/* Point B dot */}
        {pointB && (
          <>
            <Circle cx={pointB.x} cy={pointB.y} r={10} fill="#00E5FF" opacity={0.3} />
            <Circle cx={pointB.x} cy={pointB.y} r={5} fill="#00E5FF" />
          </>
        )}

        {/* Distance label */}
        {result && pointA && pointB && (
          <>
            <Circle cx={midX} cy={midY + 14} r={28} fill="rgba(0,0,0,0.6)" />
            <SvgText
              x={midX}
              y={midY + 19}
              textAnchor="middle"
              fill="#FFFFFF"
              fontSize={13}
              fontWeight="600">
              {formatDistance(result)}
            </SvgText>
          </>
        )}
      </Svg>
    </View>
  );
}
