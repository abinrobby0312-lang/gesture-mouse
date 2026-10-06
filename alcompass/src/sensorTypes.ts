import type { LatLng } from './geo';

/**
 * `idle`: waiting for the user to tap Start (web only; iOS Safari grants
 * motion access only from a tap). `pending`: the permission prompt is up.
 */
export type PermissionState = 'idle' | 'pending' | 'granted' | 'denied';

export type SensorAccess = {
  state: PermissionState;
  request: () => void;
};

export type Position = LatLng & {
  /** GPS course over ground in degrees, or null when not moving. */
  course: number | null;
};

export type Heading = {
  /** Smoothed heading in degrees clockwise from north, or null before the first reading. */
  degrees: number | null;
  /** True when the platform says the reading should not be trusted. */
  calibrating: boolean;
  /** True when no heading has arrived at all, e.g. no magnetometer. */
  unavailable: boolean;
};

// Below this speed the GPS course is noise.
export const MIN_COURSE_SPEED_MPS = 0.8;

// If no heading reading arrives in this window, treat the compass as missing.
export const HEADING_TIMEOUT_MS = 4000;

export function courseFrom(speed: number | null, heading: number | null): number | null {
  const moving = speed != null && speed >= MIN_COURSE_SPEED_MPS;
  return moving && heading != null && Number.isFinite(heading) && heading >= 0 ? heading : null;
}
