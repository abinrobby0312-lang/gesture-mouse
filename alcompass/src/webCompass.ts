import { normalizeDeg } from './geo';

/** The parts of a DeviceOrientationEvent the compass uses, including Safari's webkit extras. */
export type OrientationReading = {
  alpha: number | null;
  absolute: boolean;
  webkitCompassHeading?: number;
  webkitCompassAccuracy?: number;
};

export type CompassReading = { degrees: number; calibrating: boolean };

// Safari reports accuracy as +/- degrees; above this the needle is not trustworthy.
const MAX_IOS_UNCERTAINTY_DEG = 30;

/**
 * Turns a browser orientation event into a compass heading, degrees clockwise
 * from magnetic north. Returns null for events that carry no usable heading
 * (e.g. Chrome's relative `deviceorientation`, whose alpha starts at an arbitrary zero).
 * `screenAngle` is screen.orientation.angle, so the heading follows the top of the screen.
 */
export function headingFromOrientation(e: OrientationReading, screenAngle: number): CompassReading | null {
  if (typeof e.webkitCompassHeading === 'number' && e.webkitCompassHeading >= 0) {
    // Safari already corrects for screen orientation.
    const acc = e.webkitCompassAccuracy;
    return {
      degrees: normalizeDeg(e.webkitCompassHeading),
      calibrating: acc == null || acc < 0 || acc > MAX_IOS_UNCERTAINTY_DEG,
    };
  }
  if (e.absolute && e.alpha != null) {
    // alpha runs counter-clockwise from north.
    return { degrees: normalizeDeg(360 - e.alpha + screenAngle), calibrating: false };
  }
  return null;
}
