import {useCallback, useEffect, useReducer, useRef} from 'react';
import {AppState, AppStateStatus} from 'react-native';

export type MeasurementState =
  | 'UNCALIBRATED'
  | 'SCANNING'
  | 'DEPTH_LOADING'
  | 'READY'
  | 'POINT_A_SET'
  | 'MEASURING'
  | 'RESULT';

export interface Point2D {
  x: number;
  y: number;
}

export interface MeasurementResult {
  distanceMm: number;
  distanceCm: number;
  distanceIn: number;
  method: 'geometric' | 'depth';
  isApproximate: boolean;
}

interface State {
  phase: MeasurementState;
  pointA: Point2D | null;
  result: MeasurementResult | null;
  planeConfidence: number;
  toastMessage: string | null;
  unit: 'cm' | 'in';
  scanningTimeoutId: ReturnType<typeof setTimeout> | null;
}

type Action =
  | {type: 'PLANE_UPDATE'; confidence: number}
  | {type: 'SCANNING_TIMEOUT'}
  | {type: 'DEPTH_READY'}
  | {type: 'DEPTH_OOM'}
  | {type: 'TAP'; point: Point2D}
  | {type: 'RESULT'; result: MeasurementResult}
  | {type: 'MEASURE_AGAIN'}
  | {type: 'ROTATION'}
  | {type: 'PLANE_LOST'}
  | {type: 'CLEAR_TOAST'}
  | {type: 'TOGGLE_UNIT'}
  | {type: 'APP_BACKGROUND'}
  | {type: 'RESET'};

const initialState: State = {
  phase: 'UNCALIBRATED',
  pointA: null,
  result: null,
  planeConfidence: 0,
  toastMessage: null,
  unit: 'cm',
  scanningTimeoutId: null,
};

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'PLANE_UPDATE': {
      const conf = action.confidence;
      const newPhase =
        state.phase === 'SCANNING' && conf > 0.7 ? 'READY' : state.phase;
      return {...state, planeConfidence: conf, phase: newPhase};
    }

    case 'SCANNING_TIMEOUT':
      if (state.phase !== 'SCANNING') return state;
      // If depth engine not ready yet → DEPTH_LOADING, else READY (depth-only mode)
      return {...state, phase: 'DEPTH_LOADING', scanningTimeoutId: null};

    case 'DEPTH_READY':
      return {
        ...state,
        phase: state.phase === 'DEPTH_LOADING' ? 'READY' : state.phase,
      };

    case 'DEPTH_OOM':
      return {
        ...state,
        phase: 'READY',
        toastMessage: 'Low memory — approximate mode off',
      };

    case 'TAP': {
      if (state.phase === 'READY') {
        return {...state, phase: 'POINT_A_SET', pointA: action.point};
      }
      if (state.phase === 'POINT_A_SET') {
        return {...state, phase: 'MEASURING'};
      }
      return state;
    }

    case 'RESULT':
      return {...state, phase: 'RESULT', result: action.result};

    case 'MEASURE_AGAIN':
      return {
        ...state,
        phase: 'READY',
        pointA: null,
        result: null,
      };

    case 'ROTATION':
      if (state.phase === 'POINT_A_SET' || state.phase === 'MEASURING') {
        return {
          ...state,
          phase: 'READY',
          pointA: null,
          toastMessage: 'Rotated — tap again',
        };
      }
      return state;

    case 'PLANE_LOST':
      if (state.phase === 'POINT_A_SET') {
        return {
          ...state,
          phase: 'READY',
          pointA: null,
          toastMessage: 'Surface lost — tap again',
        };
      }
      return state;

    case 'CLEAR_TOAST':
      return {...state, toastMessage: null};

    case 'TOGGLE_UNIT':
      return {...state, unit: state.unit === 'cm' ? 'in' : 'cm'};

    case 'APP_BACKGROUND':
      return {...initialState};

    case 'RESET':
      return {...initialState};

    default:
      return state;
  }
}

export function useMeasurement() {
  const [state, dispatch] = useReducer(reducer, initialState);
  const scanTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Begin scanning — start 10s timeout for depth fallback
  const startScanning = useCallback(() => {
    dispatch({type: 'RESET'});
    scanTimerRef.current = setTimeout(() => {
      dispatch({type: 'SCANNING_TIMEOUT'});
    }, 10_000);
  }, []);

  const updatePlaneConfidence = useCallback((confidence: number) => {
    dispatch({type: 'PLANE_UPDATE', confidence});
    if (confidence > 0.7 && scanTimerRef.current) {
      clearTimeout(scanTimerRef.current);
      scanTimerRef.current = null;
    }
  }, []);

  const onDepthReady = useCallback(() => dispatch({type: 'DEPTH_READY'}), []);
  const onDepthOOM = useCallback(() => dispatch({type: 'DEPTH_OOM'}), []);
  const onTap = useCallback((point: Point2D) => dispatch({type: 'TAP', point}), []);
  const onResult = useCallback((result: MeasurementResult) => dispatch({type: 'RESULT', result}), []);
  const onMeasureAgain = useCallback(() => dispatch({type: 'MEASURE_AGAIN'}), []);
  const onRotation = useCallback(() => dispatch({type: 'ROTATION'}), []);
  const onPlaneLost = useCallback(() => dispatch({type: 'PLANE_LOST'}), []);
  const clearToast = useCallback(() => dispatch({type: 'CLEAR_TOAST'}), []);
  const toggleUnit = useCallback(() => dispatch({type: 'TOGGLE_UNIT'}), []);

  // Reset on app background
  useEffect(() => {
    const sub = AppState.addEventListener('change', (next: AppStateStatus) => {
      if (next === 'background' || next === 'inactive') {
        dispatch({type: 'APP_BACKGROUND'});
      }
    });
    return () => {
      sub.remove();
      if (scanTimerRef.current) clearTimeout(scanTimerRef.current);
    };
  }, []);

  return {
    phase: state.phase,
    pointA: state.pointA,
    result: state.result,
    planeConfidence: state.planeConfidence,
    toastMessage: state.toastMessage,
    unit: state.unit,
    startScanning,
    updatePlaneConfidence,
    onDepthReady,
    onDepthOOM,
    onTap,
    onResult,
    onMeasureAgain,
    onRotation,
    onPlaneLost,
    clearToast,
    toggleUnit,
  };
}
