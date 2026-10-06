// Native sensors (Android/iOS). The browser version is useSensors.web.ts; both export the same hooks.
import { useEffect, useRef, useState } from 'react';
import * as Location from 'expo-location';
import { HeadingFilter } from './headingFilter';
import {
  courseFrom,
  HEADING_TIMEOUT_MS,
  type Heading,
  type PermissionState,
  type Position,
  type SensorAccess,
} from './sensorTypes';

export function useSensorAccess(): SensorAccess {
  const [state, setState] = useState<PermissionState>('pending');
  useEffect(() => {
    let cancelled = false;
    Location.requestForegroundPermissionsAsync()
      .then(({ status }) => !cancelled && setState(status === 'granted' ? 'granted' : 'denied'))
      .catch(() => !cancelled && setState('denied'));
    return () => {
      cancelled = true;
    };
  }, []);
  // Native asks on launch; nothing for a button to do.
  return { state, request: () => {} };
}

export function usePosition(enabled: boolean): Position | null {
  const [pos, setPos] = useState<Position | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let sub: Location.LocationSubscription | undefined;
    let cancelled = false;
    Location.watchPositionAsync(
      { accuracy: Location.Accuracy.Balanced, distanceInterval: 5, timeInterval: 2000 },
      ({ coords }) =>
        setPos({
          latitude: coords.latitude,
          longitude: coords.longitude,
          course: courseFrom(coords.speed, coords.heading),
        }),
    ).then((s) => (cancelled ? s.remove() : (sub = s)));
    return () => {
      cancelled = true;
      sub?.remove();
    };
  }, [enabled]);
  return pos;
}

export function useHeading(enabled: boolean): Heading {
  const filter = useRef(new HeadingFilter(0.2));
  const [heading, setHeading] = useState<Heading>({
    degrees: null,
    calibrating: false,
    unavailable: false,
  });

  useEffect(() => {
    if (!enabled) return;
    let sub: Location.LocationSubscription | undefined;
    let cancelled = false;
    let gotReading = false;
    const timeout = setTimeout(() => {
      if (!gotReading && !cancelled) setHeading((h) => ({ ...h, unavailable: true }));
    }, HEADING_TIMEOUT_MS);

    Location.watchHeadingAsync(({ trueHeading, magHeading, accuracy }) => {
      gotReading = true;
      // trueHeading is -1 until location is known; magnetic is close enough until then.
      const raw = trueHeading >= 0 ? trueHeading : magHeading;
      setHeading({
        degrees: filter.current.push(raw),
        // Android reports 0-3; below 2 the needle is not trustworthy.
        calibrating: accuracy < 2,
        unavailable: false,
      });
    })
      .then((s) => (cancelled ? s.remove() : (sub = s)))
      .catch(() => !cancelled && setHeading((h) => ({ ...h, unavailable: true })));

    return () => {
      cancelled = true;
      clearTimeout(timeout);
      sub?.remove();
      filter.current.reset();
    };
  }, [enabled]);

  return heading;
}
