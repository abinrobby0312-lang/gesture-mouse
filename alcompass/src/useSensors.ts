import { useEffect, useRef, useState } from 'react';
import * as Location from 'expo-location';
import { HeadingFilter } from './headingFilter';
import type { LatLng } from './geo';

export type PermissionState = 'pending' | 'granted' | 'denied';

export function useLocationPermission(): PermissionState {
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
  return state;
}

export type Position = LatLng & {
  /** GPS course over ground in degrees, or null when not moving. */
  course: number | null;
};

// Below this speed the GPS course is noise.
const MIN_COURSE_SPEED_MPS = 0.8;

export function usePosition(enabled: boolean): Position | null {
  const [pos, setPos] = useState<Position | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let sub: Location.LocationSubscription | undefined;
    let cancelled = false;
    Location.watchPositionAsync(
      { accuracy: Location.Accuracy.Balanced, distanceInterval: 5, timeInterval: 2000 },
      ({ coords }) => {
        const moving = coords.speed != null && coords.speed >= MIN_COURSE_SPEED_MPS;
        setPos({
          latitude: coords.latitude,
          longitude: coords.longitude,
          course: moving && coords.heading != null && coords.heading >= 0 ? coords.heading : null,
        });
      },
    ).then((s) => (cancelled ? s.remove() : (sub = s)));
    return () => {
      cancelled = true;
      sub?.remove();
    };
  }, [enabled]);
  return pos;
}

export type Heading = {
  /** Smoothed true heading in degrees, or null before the first reading. */
  degrees: number | null;
  /** True when the platform reports accuracy below 2 (Android) and the needle should not be trusted. */
  calibrating: boolean;
  /** True when no heading has arrived at all, e.g. no magnetometer. */
  unavailable: boolean;
};

// If no heading reading arrives in this window, treat the compass as missing.
const HEADING_TIMEOUT_MS = 4000;

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
