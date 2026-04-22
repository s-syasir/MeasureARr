import React, {useState} from 'react';
import {
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {KNOWN_OBJECT_MM, KnownObject} from '../hooks/useCalibration';

interface Props {
  visible: boolean;
  onCalibrate: (knownMm: number) => void;
  onDismiss: () => void;
}

const OBJECTS: {key: KnownObject; label: string; detail: string}[] = [
  {key: 'credit_card', label: 'Credit card', detail: '85.6 mm'},
  {key: 'a4_short', label: 'A4 short side', detail: '210 mm'},
  {key: 'a4_long', label: 'A4 long side', detail: '297 mm'},
  {key: 'custom', label: 'Custom length', detail: 'Enter mm'},
];

export function CalibrationModal({visible, onCalibrate, onDismiss}: Props) {
  const [selected, setSelected] = useState<KnownObject>('credit_card');
  const [customMm, setCustomMm] = useState('');
  const [error, setError] = useState<string | null>(null);

  const handleStart = () => {
    setError(null);
    if (selected === 'custom') {
      const mm = parseFloat(customMm);
      if (isNaN(mm) || mm <= 0) {
        setError('Enter a valid length in mm');
        return;
      }
      onCalibrate(mm);
    } else {
      onCalibrate(KNOWN_OBJECT_MM[selected]);
    }
  };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onDismiss}>
      <KeyboardAvoidingView
        style={styles.overlay}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <View style={styles.sheet}>
          <Text style={styles.title}>Calibrate</Text>
          <Text style={styles.subtitle}>
            Place a known object flat on the surface, then tap its two ends.
          </Text>

          <ScrollView>
            {OBJECTS.map(obj => (
              <Pressable
                key={obj.key}
                style={[styles.option, selected === obj.key && styles.optionSelected]}
                onPress={() => setSelected(obj.key)}>
                <Text style={styles.optionLabel}>{obj.label}</Text>
                <Text style={styles.optionDetail}>{obj.detail}</Text>
              </Pressable>
            ))}
          </ScrollView>

          {selected === 'custom' && (
            <TextInput
              style={styles.input}
              placeholder="Length in mm"
              placeholderTextColor="#666"
              keyboardType="decimal-pad"
              value={customMm}
              onChangeText={setCustomMm}
            />
          )}

          {error && <Text style={styles.error}>{error}</Text>}

          <View style={styles.actions}>
            <Pressable style={styles.cancelBtn} onPress={onDismiss}>
              <Text style={styles.cancelText}>Cancel</Text>
            </Pressable>
            <Pressable style={styles.startBtn} onPress={handleStart}>
              <Text style={styles.startText}>Tap two ends →</Text>
            </Pressable>
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    justifyContent: 'flex-end',
    backgroundColor: 'rgba(0,0,0,0.5)',
  },
  sheet: {
    backgroundColor: '#1C1C1E',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    padding: 24,
    paddingBottom: 40,
  },
  title: {
    color: '#FFFFFF',
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 6,
  },
  subtitle: {
    color: '#AAAAAA',
    fontSize: 14,
    marginBottom: 16,
  },
  option: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 14,
    borderRadius: 10,
    marginBottom: 8,
    backgroundColor: '#2C2C2E',
  },
  optionSelected: {
    backgroundColor: '#00E5FF22',
    borderWidth: 1,
    borderColor: '#00E5FF',
  },
  optionLabel: {
    color: '#FFFFFF',
    fontSize: 15,
  },
  optionDetail: {
    color: '#AAAAAA',
    fontSize: 13,
  },
  input: {
    backgroundColor: '#2C2C2E',
    color: '#FFFFFF',
    borderRadius: 10,
    padding: 14,
    fontSize: 15,
    marginTop: 4,
    marginBottom: 8,
  },
  error: {
    color: '#FF453A',
    fontSize: 13,
    marginBottom: 8,
  },
  actions: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 12,
  },
  cancelBtn: {
    flex: 1,
    padding: 14,
    borderRadius: 10,
    backgroundColor: '#2C2C2E',
    alignItems: 'center',
  },
  cancelText: {
    color: '#AAAAAA',
    fontSize: 15,
  },
  startBtn: {
    flex: 2,
    padding: 14,
    borderRadius: 10,
    backgroundColor: '#00E5FF',
    alignItems: 'center',
  },
  startText: {
    color: '#000000',
    fontWeight: '700',
    fontSize: 15,
  },
});
