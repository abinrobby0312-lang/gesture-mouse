import { useEffect, useMemo, useRef, useState } from 'react';
import { StatusBar } from 'expo-status-bar';
import { useFonts } from 'expo-font';
import {
  BigShouldersDisplay_800ExtraBold,
  BigShouldersDisplay_900Black,
} from '@expo-google-fonts/big-shoulders-display';
import { Archivo_500Medium, Archivo_700Bold } from '@expo-google-fonts/archivo';
import { Platform, Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import Animated, {
  Easing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { bearingDeg, compassPoint, distanceM, formatDistance, needleAngleDeg } from './src/geo';
import { Label } from './src/Label';
import { Needle } from './src/Needle';
import { RollingText } from './src/RollingText';
import { addFix, EMPTY_TRIP, verdict, type Verdict } from './src/trip';
import { ErrorBoundary } from './src/ErrorBoundary';
import { track } from './src/log';
import { openState } from './src/hours';
import { nearestFirst } from './src/shops';
import { font, ink } from './src/theme';
import { useHeading, usePosition, useSensorAccess } from './src/useSensors';
import { useShops } from './src/useShops';

// Browser surfaces the app does not draw: page background behind overscroll, and text selection.
if (Platform.OS === 'web' && typeof document !== 'undefined') {
  document.documentElement.style.backgroundColor = ink.ground;
  const css = document.createElement('style');
  css.textContent = [
    `::selection { background: ${ink.label}; color: ${ink.key}; }`,
    `[role=button]:focus-visible { outline: 3px solid ${ink.paper}; outline-offset: 6px; }`,
    `[data-testid=start]:focus-visible { outline-color: ${ink.key}; outline-offset: 3px; }`,
  ].join('\n');
  document.head.appendChild(css);
}

export default function App() {
  return (
    <ErrorBoundary>
      <Screen />
    </ErrorBoundary>
  );
}

function Screen() {
  const { width, height } = useWindowDimensions();
  const [fontsLoaded] = useFonts({
    BigShouldersDisplay_800ExtraBold,
    BigShouldersDisplay_900Black,
    Archivo_500Medium,
    Archivo_700Bold,
  });
  const access = useSensorAccess();
  const permission = access.state;
  const granted = permission === 'granted';
  const position = usePosition(granted);
  const heading = useHeading(granted);
  useEffect(() => {
    if (permission !== 'idle') track('permission', { state: permission });
  }, [permission]);

  const labelWidth = Math.min(width * 0.86, 400);
  // The emblem is the label's largest element, but the whole label must fit a short screen.
  const emblemSize = Math.min(labelWidth - 2 * 23 - 24, height * 0.34);

  if (!fontsLoaded) return <View style={styles.screen} />;

  return (
    <View style={styles.screen}>
      <StatusBar style="light" />
      <Text style={styles.credit}>Shop data © OpenStreetMap contributors</Text>
      {granted ? (
        <Compass labelWidth={labelWidth} emblemSize={emblemSize} position={position} heading={heading} />
      ) : (
        <Label
          width={labelWidth}
          title={permission === 'denied' ? 'Location is off' : 'Alcompass'}
          emblem={<Needle angle={permission === 'denied' ? null : 0} size={emblemSize} />}
        >
          {permission === 'denied' ? (
            <Text style={styles.small}>
              Alcompass needs location access to point at shops. Turn it on in your settings, then
              reload.
            </Text>
          ) : (
            <>
              <Text style={styles.tagline}>Your pre-game just got more adventurous</Text>
              <Text style={styles.small}>
                Points at the nearest alcohol shop. Needs your location and compass.
              </Text>
              {permission === 'pending' ? (
                <Text style={[styles.small, styles.pending]}>Asking for location access…</Text>
              ) : (
                <Pressable
                  testID="start"
                  onPress={() => {
                    track('start_tap');
                    access.request();
                  }}
                  accessibilityRole="button"
                  style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
                >
                  <Text style={styles.buttonText}>Start</Text>
                </Pressable>
              )}
            </>
          )}
        </Label>
      )}
    </View>
  );
}

type CompassProps = {
  labelWidth: number;
  emblemSize: number;
  position: ReturnType<typeof usePosition>;
  heading: ReturnType<typeof useHeading>;
};

function Compass({ labelWidth, emblemSize, position, heading }: CompassProps) {
  const { width } = useWindowDimensions();
  const reduced = useReducedMotion();
  const shops = useShops(position);
  const candidates = useMemo(
    () => (position && 'shops' in shops ? nearestFirst(shops.shops, position) : []),
    [position, shops],
  );
  // Follow the chosen shop by id, so re-sorting while walking does not swap the target.
  const [chosenId, setChosenId] = useState<string | null>(null);
  const index = Math.max(0, candidates.findIndex((c) => c.id === chosenId));
  const target = candidates[index] ?? null;

  // Swap the label like a matchbox tray: slide it out left, change it, slide the new one in.
  const slide = useSharedValue(0);
  const sliding = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (sliding.current) clearTimeout(sliding.current);
  }, []);
  const swapLabel = (change: () => void) => {
    if (sliding.current) return; // A tray is already moving; ignore the extra tap.
    if (reduced) {
      change();
      return;
    }
    slide.value = withTiming(-width, { duration: 160, easing: Easing.in(Easing.quad) });
    sliding.current = setTimeout(() => {
      change();
      slide.value = width;
      slide.value = withTiming(0, { duration: 340, easing: Easing.out(Easing.exp) });
      sliding.current = null;
    }, 160);
  };
  const next = () => {
    if (candidates.length < 2) return;
    const nextId = candidates[(index + 1) % candidates.length].id;
    track('next_shop', { index: (index + 1) % candidates.length, of: candidates.length });
    swapLabel(() => setChosenId(nextId));
  };
  const slideStyle = useAnimatedStyle(() => ({ transform: [{ translateX: slide.value }] }));

  const bearing = target && position ? bearingDeg(position, target) : null;
  const distance = target && position ? distanceM(position, target) : null;

  // The shop stays a mystery until you reach it. Separate arrive and leave distances stop the
  // reveal flickering at the edge of GPS accuracy.
  const [arrivedId, setArrivedId] = useState<string | null>(null);
  const arrived = target != null && arrivedId === target.id;
  // The trip to the shop, judged on arrival: how far, how fast, how many sips it earned.
  const trip = useRef(EMPTY_TRIP);
  const [tripVerdict, setTripVerdict] = useState<Verdict | null>(null);
  useEffect(() => {
    if (position && !arrived) trip.current = addFix(trip.current, position);
  }, [position, arrived]);

  // Bumped to look again once a tray already in motion has landed.
  const [recheck, setRecheck] = useState(0);
  useEffect(() => {
    if (!target || distance == null) return;
    const flip = !arrived && distance <= ARRIVE_M ? target.id : arrived && distance > LEAVE_M ? null : undefined;
    if (flip === undefined) return;
    if (sliding.current) {
      const id = setTimeout(() => setRecheck((n) => n + 1), 200);
      return () => clearTimeout(id);
    }
    // Arriving and leaving both swap the label like a tray, as a new shop does.
    swapLabel(() => {
      setArrivedId(flip);
      if (flip) {
        const v = verdict(trip.current);
        setTripVerdict(v);
        track('arrived', { mode: v.mode, distance_m: Math.round(v.distanceM / 50) * 50, kcal: v.kcal, minutes: Math.round(trip.current.movingMs / 60_000) });
      } else {
        track('left_shop');
        // Walked off again: the next arrival judges the trip from here.
        trip.current = EMPTY_TRIP;
        setTripVerdict(null);
      }
    });
    // swapLabel is recreated each render; only the distance and target matter here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target?.id, distance, arrived, recheck]);

  // Which way the phone faces: magnetometer first, GPS course while walking as the fallback.
  const facing = heading.unavailable ? position?.course ?? null : heading.degrees;
  const usingCourse = heading.unavailable && facing != null;

  // Log each sensor or result condition once per change, to see what visitors actually hit.
  const condition = heading.unavailable ? (usingCourse ? 'compass_course' : 'compass_missing') : heading.calibrating ? 'compass_calibrating' : null;
  useEffect(() => {
    if (condition) track(condition);
  }, [condition]);
  const empty = shops.status === 'ready' && candidates.length === 0;
  useEffect(() => {
    if (empty) track('no_shops');
  }, [empty]);

  let status: string | null = null;
  if (heading.unavailable && !usingCourse) status = 'No compass on this phone. Walk a few steps and the bottle will point.';
  else if (usingCourse) status = 'No compass. The bottle follows your walking direction.';
  else if (facing == null) status = 'Waiting for compass…';
  else if (heading.calibrating) status = 'Calibrating. Move the phone in a figure 8.';

  let title: string;
  let detail = '';
  if (!position) {
    title = 'Finding you…';
    detail = 'Waiting for a location fix';
  } else if (target) {
    title = arrived ? target.name : 'Follow the bottle';
  } else if (shops.status === 'error') {
    title = 'Couldn\'t load shops';
    detail = 'Will retry shortly';
  } else if (shops.status === 'ready') {
    title = 'Nothing nearby';
    detail = `No alcohol shops within ${RADIUS_KM} km`;
  } else {
    title = 'Looking for shops…';
  }

  const canPoint = facing != null && bearing != null;
  const emblem =
    canPoint || bearing == null ? (
      <Needle
        angle={canPoint ? needleAngleDeg(bearing, facing) : null}
        size={arrived ? Math.round(emblemSize * 0.6) : emblemSize}
        dimmed={heading.calibrating && !usingCourse}
      />
    ) : (
      // No way to orient a needle: give plain direction text inside the burst instead.
      <View style={{ width: emblemSize, height: emblemSize }}>
        <Needle angle={null} size={emblemSize} dimmed hub={false} />
        <View style={styles.pointText}>
          <Text style={[styles.point, { fontSize: emblemSize * 0.36 }]}>{compassPoint(bearing)}</Text>
        </View>
      </View>
    );

  // One line on any phone: Big Shoulders caps run about 0.13 of the label width per this phrase's em.
  const arrivedSize = Math.min(48, Math.floor(labelWidth * 0.125));
  const serial = candidates.length > 0 ? `No. ${index + 1} of ${candidates.length}` : 'No. —';

  return (
    <>
      <Pressable
        onPress={next}
        disabled={candidates.length < 2}
        accessibilityRole="button"
        accessibilityHint={candidates.length > 1 ? 'Points at the next nearest shop' : undefined}
      >
        {({ pressed }) => (
          <Animated.View style={[slideStyle, pressed && styles.pressed]}>
            <Label width={labelWidth} serial={serial} title={title} emblem={emblem}>
              {arrived && target ? (
                <>
                  <Text style={[styles.arrived, { fontSize: arrivedSize, lineHeight: arrivedSize + 4 }]}>You've arrived</Text>
                  {!!openLine(target.hours) && (
                    <Text style={[styles.chip, styles.chipOpen]}>{openLine(target.hours)}</Text>
                  )}
                  {tripVerdict && (
                    <View style={styles.verdict}>
                      <Text style={styles.receipt}>{tripVerdict.receipt}</Text>
                      <Text style={styles.quip}>{tripVerdict.quip}</Text>
                    </View>
                  )}
                </>
              ) : distance != null ? (
                <RollingText key={target?.id} text={formatDistance(distance)} style={styles.distance} />
              ) : (
                !!detail && <Text style={styles.small}>{detail}</Text>
              )}
            </Label>
          </Animated.View>
        )}
      </Pressable>

      {candidates.length > 1 && <Text style={styles.hint}>Tap the label to try another shop</Text>}
      {status && <Text style={styles.status}>{status}</Text>}
    </>
  );
}

/** What the shop's own listed hours say, for information only; null when it lists none. */
function openLine(hours: string | null): string | null {
  const s = openState(hours, Date.now());
  if (!s) return null;
  if (!s.open) return 'Listed as closed right now';
  return s.closesAt == null ? 'Open now' : `Open till ${formatClock(s.closesAt)}`;
}

function formatClock(t: number): string {
  return new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

// Matches RADIUS_M in server/places.ts.
const RADIUS_KM = 3;
// Within this many metres the shop is reached and revealed; it hides again beyond LEAVE_M.
const ARRIVE_M = 30;
const LEAVE_M = 60;

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: ink.ground,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 16,
  },
  pressed: { opacity: 0.94 },
  // Required by the OpenStreetMap licence (ODbL); kept out of the way at the foot of the screen.
  credit: {
    position: 'absolute',
    bottom: 14,
    fontFamily: font.text,
    fontSize: 11,
    color: ink.onGround,
    opacity: 0.8,
  },
  small: {
    fontFamily: font.text,
    fontSize: 15,
    lineHeight: 21,
    color: ink.key,
    textAlign: 'center',
    maxWidth: 300,
  },
  tagline: {
    fontFamily: font.display,
    fontSize: 20,
    lineHeight: 23,
    letterSpacing: 0.6,
    textTransform: 'uppercase',
    textAlign: 'center',
    color: ink.key,
    marginBottom: 8,
  },
  pending: { marginTop: 14, fontFamily: font.textBold },
  button: {
    marginTop: 16,
    alignSelf: 'stretch',
    backgroundColor: ink.key,
    paddingVertical: 14,
    alignItems: 'center',
  },
  buttonPressed: { backgroundColor: ink.emblem },
  buttonText: {
    fontFamily: font.display,
    fontSize: 24,
    letterSpacing: 2,
    textTransform: 'uppercase',
    color: ink.label,
  },
  distance: {
    fontFamily: font.displayHeavy,
    fontSize: 68,
    lineHeight: 70,
    color: ink.key,
    fontVariant: ['tabular-nums'],
  },
  arrived: {
    fontFamily: font.displayHeavy,
    textTransform: 'uppercase',
    textAlign: 'center',
    color: ink.key,
  },
  verdict: {
    marginTop: 14,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: ink.key,
    alignSelf: 'stretch',
    alignItems: 'center',
  },
  receipt: {
    fontFamily: font.textBold,
    fontSize: 13,
    letterSpacing: 1.2,
    textTransform: 'uppercase',
    color: ink.key,
    fontVariant: ['tabular-nums'],
  },
  quip: {
    marginTop: 6,
    fontFamily: font.text,
    fontSize: 15,
    lineHeight: 21,
    color: ink.key,
    textAlign: 'center',
    maxWidth: 300,
  },
  chip: {
    marginTop: 8,
    paddingHorizontal: 10,
    paddingVertical: 3,
    fontFamily: font.textBold,
    fontSize: 13,
    letterSpacing: 1.2,
    textTransform: 'uppercase',
    borderWidth: 1.5,
    borderColor: ink.key,
    overflow: 'hidden',
  },
  chipOpen: { backgroundColor: ink.emblem, color: ink.paper, borderColor: ink.emblem },
  hint: { marginTop: 22, fontFamily: font.text, fontSize: 15, color: ink.onGround },
  status: {
    marginTop: 10,
    fontFamily: font.textBold,
    fontSize: 15,
    lineHeight: 21,
    color: ink.label,
    textAlign: 'center',
    maxWidth: 320,
  },
  pointText: { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, alignItems: 'center', justifyContent: 'center' },
  point: { fontFamily: font.displayHeavy, color: ink.key },
});
