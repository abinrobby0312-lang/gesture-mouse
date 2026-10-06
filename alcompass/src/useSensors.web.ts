// Browser sensors: Geolocation API plus device orientation events.
// Same hooks as useSensors.ts; Metro picks this file for the web build.
import { useCallback, useEffect, useRef, useState } from 'react';
import { HeadingFilter } from './headingFilter';
import {
  courseFrom,
  HEADING_TIMEOUT_MS,
  type Heading,
  type PermissionState,
  type Position,
  type SensorAccess,
} from './sensorTypes';
import { headingFromOrientation, type OrientationReading } from './webCompass';

type IOSOrientationEvent = typeof DeviceOrientationEvent & {
  requestPermission?: () => Promise<'granted' | 'denied'>;
};

export function useSensorAccess(): SensorAccess {
  const [state, setState] = useState<PermissionState>('idle');

  // Skip the Start tap when location is already allowed and no motion prompt is needed (not iOS).
  useEffect(() => {
    const needsMotionPrompt =
      typeof DeviceOrientationEvent !== 'undefined' &&
      typeof (DeviceOrientationEvent as IOSOrientationEvent).requestPermission === 'function';
    if (needsMotionPrompt || !navigator.permissions) return;
    navigator.permissions
      .query({ name: 'geolocation' })
      .then((p) => p.state === 'granted' && setState((s) => (s === 'idle' ? 'granted' : s)))
      .catch(() => {});
  }, []);

  const request = useCallback(() => {
    if (!window.isSecureContext || !navigator.geolocation) {
      setState('denied');
      return;
    }
    setState('pending');
    // iOS Safari: must be called directly inside the tap handler. A refusal only loses the compass.
    const ios = (typeof DeviceOrientationEvent !== 'undefined'
      ? DeviceOrientationEvent
      : undefined) as IOSOrientationEvent | undefined;
    ios?.requestPermission?.().catch(() => 'denied');

    navigator.geolocation.getCurrentPosition(
      () => setState('granted'),
      (err) => setState(err.code === err.PERMISSION_DENIED ? 'denied' : 'granted'),
      { enableHighAccuracy: false, timeout: 15000, maximumAge: 60000 },
    );
  }, []);

  return { state, request };
}

export function usePosition(enabled: boolean): Position | null {
  const [pos, setPos] = useState<Position | null>(null);
  useEffect(() => {
    if (!enabled || !navigator.geolocation) return;
    const id = navigator.geolocation.watchPosition(
      ({ coords, timestamp }) =>
        setPos({
          latitude: coords.latitude,
          longitude: coords.longitude,
          course: courseFrom(coords.speed, coords.heading),
          accuracy: coords.accuracy ?? null,
          at: timestamp,
        }),
      () => {},
      { enableHighAccuracy: true, maximumAge: 2000, timeout: 20000 },
    );
    return () => navigator.geolocation.clearWatch(id);
  }, [enabled]);
  return pos;
}

function screenAngle(): number {
  const a = screen.orientation?.angle;
  return typeof a === 'number' ? a : 0;
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
    let gotReading = false;
    const timeout = setTimeout(() => {
      if (!gotReading) setHeading((h) => ({ ...h, unavailable: true }));
    }, HEADING_TIMEOUT_MS);

    const onOrientation = (e: Event) => {
      const reading = headingFromOrientation(e as unknown as OrientationReading, screenAngle());
      if (!reading) return;
      gotReading = true;
      setHeading({
        degrees: filter.current.push(reading.degrees),
        calibrating: reading.calibrating,
        unavailable: false,
      });
    };

    // Chrome/Android: 'deviceorientationabsolute'. Safari: 'deviceorientation' with webkitCompassHeading.
    // Relative-only events are ignored by headingFromOrientation.
    window.addEventListener('deviceorientationabsolute', onOrientation);
    window.addEventListener('deviceorientation', onOrientation);
    return () => {
      clearTimeout(timeout);
      window.removeEventListener('deviceorientationabsolute', onOrientation);
      window.removeEventListener('deviceorientation', onOrientation);
      filter.current.reset();
    };
  }, [enabled]);

  return heading;
}
