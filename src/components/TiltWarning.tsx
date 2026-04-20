import React, {useEffect, useState} from 'react';
import {NativeEventEmitter, NativeModules, StyleSheet, Text, View} from 'react-native';

const {TiltModule} = NativeModules;

export function TiltWarning() {
  const [tilted, setTilted] = useState(false);

  useEffect(() => {
    if (!TiltModule) return;
    const emitter = new NativeEventEmitter(TiltModule);
    const sub = emitter.addListener('tiltWarning', (isTilted: boolean) => {
      setTilted(isTilted);
    });
    TiltModule.addListener('tiltWarning');
    return () => {
      sub.remove();
      TiltModule.removeListeners(1);
    };
  }, []);

  if (!tilted) return null;

  return (
    <View style={styles.banner} pointerEvents="none">
      <Text style={styles.text}>Hold phone more horizontal for accurate measurements</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    position: 'absolute',
    top: 110,
    left: 20,
    right: 20,
    backgroundColor: 'rgba(255, 160, 0, 0.85)',
    borderRadius: 12,
    paddingVertical: 8,
    paddingHorizontal: 14,
    alignItems: 'center',
  },
  text: {
    color: '#000',
    fontSize: 13,
    fontWeight: '600',
    textAlign: 'center',
  },
});
