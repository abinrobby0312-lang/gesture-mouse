import { useMemo, useState } from 'react';
import { StatusBar } from 'expo-status-bar';
import { Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { bearingDeg, compassPoint, distanceM, formatDistance, needleAngleDeg } from './src/geo';
import { Needle } from './src/Needle';
import { nearestFirst } from './src/shops';
import { useHeading, usePosition, useSensorAccess } from './src/useSensors';
import { useShops } from './src/useShops';

export default function App() {
  const { width } = useWindowDimensions();
  const access = useSensorAccess();
  const permission = access.state;
  const granted = permission === 'granted';
  const position = usePosition(granted);
  const heading = useHeading(granted);

  return (
    <View style={styles.screen}>
      <StatusBar style="light" />
      {permission === 'idle' && (
        <>
          <Text style={styles.name}>Alcompass</Text>
          <Text style={styles.note}>
            Points at the nearest alcohol shop. Needs your location and compass.
          </Text>
          <Pressable style={styles.button} onPress={access.request} accessibilityRole="button">
            <Text style={styles.buttonText}>Start</Text>
          </Pressable>
        </>
      )}
      {permission === 'pending' && <Text style={styles.note}>Asking for location access…</Text>}
      {permission === 'denied' && (
        <Text style={styles.note}>
          Alcompass needs location access to point at shops. Turn it on in your settings, then
          reload.
        </Text>
      )}
      {granted && <Compass size={Math.min(width * 0.6, 360)} position={position} heading={heading} />}
    </View>
  );
}

type CompassProps = {
  size: number;
  position: ReturnType<typeof usePosition>;
  heading: ReturnType<typeof useHeading>;
};

function Compass({ size, position, heading }: CompassProps) {
  const shops = useShops(position);
  const candidates = useMemo(
    () => (position && 'shops' in shops ? nearestFirst(shops.shops, position) : []),
    [position, shops],
  );
  // Follow the chosen shop by id, so re-sorting while walking does not swap the target.
  const [chosenId, setChosenId] = useState<string | null>(null);
  const index = Math.max(0, candidates.findIndex((c) => c.id === chosenId));
  const target = candidates[index] ?? null;
  const next = () => candidates.length > 1 && setChosenId(candidates[(index + 1) % candidates.length].id);

  const bearing = target && position ? bearingDeg(position, target) : null;
  const distance = target && position ? distanceM(position, target) : null;

  // Which way the phone faces: magnetometer first, GPS course while walking as the fallback.
  const facing = heading.unavailable ? position?.course ?? null : heading.degrees;
  const usingCourse = heading.unavailable && facing != null;

  let status: string | null = null;
  if (heading.unavailable && !usingCourse) status = 'No compass on this phone. Start walking to orient the arrow.';
  else if (usingCourse) status = 'No compass. Arrow follows your walking direction.';
  else if (facing == null) status = 'Waiting for compass…';
  else if (heading.calibrating) status = 'Calibrating. Move the phone in a figure 8.';

  let title: string;
  let detail: string;
  if (!position) {
    title = 'Finding you…';
    detail = 'Waiting for a location fix';
  } else if (target && distance != null) {
    title = target.name;
    const open = target.openNow == null ? '' : target.openNow ? ' · Open now' : ' · Closed';
    detail = `${formatDistance(distance)}${open}`;
  } else if (shops.status === 'error') {
    title = 'Couldn\'t load shops';
    detail = 'Will retry shortly';
  } else if (shops.status === 'ready') {
    title = 'Nothing nearby';
    detail = `No alcohol shops within ${RADIUS_KM} km`;
  } else {
    title = 'Looking for shops…';
    detail = '';
  }

  return (
    <>
      {facing != null && bearing != null ? (
        <Needle
          angle={needleAngleDeg(bearing, facing)}
          size={size}
          dimmed={heading.calibrating && !usingCourse}
        />
      ) : (
        // No way to orient a needle: give plain direction text instead.
        <View style={[styles.textOnly, { height: size }]}>
          <Text style={styles.big}>
            {heading.unavailable && bearing != null ? compassPoint(bearing) : '…'}
          </Text>
        </View>
      )}

      <Pressable
        style={styles.targetLine}
        onPress={next}
        disabled={candidates.length < 2}
        accessibilityRole="button"
        accessibilityHint={candidates.length > 1 ? 'Points at the next nearest shop' : undefined}
      >
        <Text style={styles.name} numberOfLines={1}>
          {title}
        </Text>
        {!!detail && <Text style={styles.distance}>{detail}</Text>}
        {candidates.length > 1 && (
          <Text style={styles.hint}>
            {index + 1} of {candidates.length} · tap for next
          </Text>
        )}
      </Pressable>

      {status && <Text style={styles.note}>{status}</Text>}
    </>
  );
}

// Matches RADIUS_M in server/places.ts.
const RADIUS_KM = 3;

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#0d0f13',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
  },
  targetLine: { marginTop: 32, alignItems: 'center', paddingVertical: 8, maxWidth: '100%' },
  hint: { color: '#5d6470', fontSize: 13, marginTop: 8 },
  name: { color: '#f5f6f8', fontSize: 22, fontWeight: '600' },
  distance: { color: '#9aa1ad', fontSize: 16, marginTop: 6 },
  note: { color: '#9aa1ad', fontSize: 14, textAlign: 'center', marginTop: 20 },
  textOnly: { justifyContent: 'center' },
  button: {
    marginTop: 28,
    backgroundColor: '#f2b134',
    borderRadius: 24,
    paddingHorizontal: 40,
    paddingVertical: 12,
  },
  buttonText: { color: '#0d0f13', fontSize: 17, fontWeight: '700' },
  big: { color: '#f2b134', fontSize: 64, fontWeight: '700' },
});
