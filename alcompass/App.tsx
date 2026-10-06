import { StatusBar } from 'expo-status-bar';
import { StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { bearingDeg, compassPoint, distanceM, formatDistance, needleAngleDeg } from './src/geo';
import { Needle } from './src/Needle';
import { DEV_SHOP } from './src/target';
import { useHeading, useLocationPermission, usePosition } from './src/useSensors';

export default function App() {
  const { width } = useWindowDimensions();
  const permission = useLocationPermission();
  const granted = permission === 'granted';
  const position = usePosition(granted);
  const heading = useHeading(granted);

  return (
    <View style={styles.screen}>
      <StatusBar style="light" />
      {permission === 'pending' && <Text style={styles.note}>Asking for location access…</Text>}
      {permission === 'denied' && (
        <Text style={styles.note}>
          Alcompass needs location access to point at shops. Turn it on in Settings.
        </Text>
      )}
      {granted && <Compass size={width * 0.6} position={position} heading={heading} />}
    </View>
  );
}

type CompassProps = {
  size: number;
  position: ReturnType<typeof usePosition>;
  heading: ReturnType<typeof useHeading>;
};

function Compass({ size, position, heading }: CompassProps) {
  const target = DEV_SHOP;
  const bearing = target && position ? bearingDeg(position, target) : 0; // 0 = north
  const distance = target && position ? distanceM(position, target) : null;

  // Which way the phone faces: magnetometer first, GPS course while walking as the fallback.
  const facing = heading.unavailable ? position?.course ?? null : heading.degrees;
  const usingCourse = heading.unavailable && facing != null;

  let status: string | null = null;
  if (heading.unavailable && !usingCourse) status = 'No compass on this phone. Start walking to orient the arrow.';
  else if (usingCourse) status = 'No compass. Arrow follows your walking direction.';
  else if (heading.calibrating) status = 'Calibrating. Move the phone in a figure 8.';

  return (
    <>
      {facing != null ? (
        <Needle
          angle={needleAngleDeg(bearing, facing)}
          size={size}
          dimmed={heading.calibrating && !usingCourse}
        />
      ) : (
        // No way to orient a needle: give plain direction text instead.
        <View style={[styles.textOnly, { height: size }]}>
          <Text style={styles.big}>
            {distance != null ? `${compassPoint(bearing)}` : '…'}
          </Text>
        </View>
      )}

      <View style={styles.targetLine}>
        <Text style={styles.name}>{target ? target.name : 'North'}</Text>
        <Text style={styles.distance}>
          {!target
            ? 'Set a test shop to point at it'
            : distance == null
              ? 'Finding your position…'
              : `${formatDistance(distance)} · retail outlet`}
        </Text>
      </View>

      {status && <Text style={styles.note}>{status}</Text>}
    </>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#0d0f13',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
  },
  targetLine: { marginTop: 32, alignItems: 'center' },
  name: { color: '#f5f6f8', fontSize: 22, fontWeight: '600' },
  distance: { color: '#9aa1ad', fontSize: 16, marginTop: 6 },
  note: { color: '#9aa1ad', fontSize: 14, textAlign: 'center', marginTop: 20 },
  textOnly: { justifyContent: 'center' },
  big: { color: '#f2b134', fontSize: 64, fontWeight: '700' },
});
