export type LatLng = { latitude: number; longitude: number };

const EARTH_RADIUS_M = 6371008.8;

const toRad = (deg: number) => (deg * Math.PI) / 180;
const toDeg = (rad: number) => (rad * 180) / Math.PI;

/** Wraps any angle into [0, 360). */
export function normalizeDeg(deg: number): number {
  return ((deg % 360) + 360) % 360;
}

/** Initial great-circle bearing from `from` to `to`, in degrees clockwise from true north. */
export function bearingDeg(from: LatLng, to: LatLng): number {
  const phi1 = toRad(from.latitude);
  const phi2 = toRad(to.latitude);
  const dLambda = toRad(to.longitude - from.longitude);
  const y = Math.sin(dLambda) * Math.cos(phi2);
  const x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
  return normalizeDeg(toDeg(Math.atan2(y, x)));
}

/** Haversine distance in metres. */
export function distanceM(a: LatLng, b: LatLng): number {
  const dPhi = toRad(b.latitude - a.latitude);
  const dLambda = toRad(b.longitude - a.longitude);
  const h =
    Math.sin(dPhi / 2) ** 2 +
    Math.cos(toRad(a.latitude)) * Math.cos(toRad(b.latitude)) * Math.sin(dLambda / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Angle to rotate the needle so it points at `bearing` while the phone faces `heading`. */
export function needleAngleDeg(bearing: number, heading: number): number {
  return normalizeDeg(bearing - heading);
}

/** Signed shortest turn from `fromDeg` to `toDeg`, in (-180, 180]. */
export function shortestDeltaDeg(fromDeg: number, toDeg: number): number {
  const d = normalizeDeg(toDeg - fromDeg);
  return d > 180 ? d - 360 : d;
}

/** Metres under 1 km, one-decimal kilometres above. */
export function formatDistance(m: number): string {
  if (m < 1000) return `${Math.round(m)} m`;
  return `${(m / 1000).toFixed(1)} km`;
}

const COMPASS_POINTS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];

export function compassPoint(deg: number): string {
  return COMPASS_POINTS[Math.round(normalizeDeg(deg) / 45) % 8];
}
