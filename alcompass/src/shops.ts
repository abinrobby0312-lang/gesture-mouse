import { distanceM, type LatLng } from './geo';

export type Shop = LatLng & {
  id: string;
  name: string;
  address: string | null;
  /** The OpenStreetMap opening_hours value, or null when the shop has none. */
  hours: string | null;
};

/** Refetch only after moving this far from where the last list was fetched. */
export const REFETCH_DISTANCE_M = 500;
/** How long a cached list for one area stays good. */
export const CACHE_TTL_MS = 24 * 60 * 60 * 1000;
/** Tap cycles through this many of the nearest shops. */
export const MAX_CANDIDATES = 5;

// About 500 m of latitude.
const CELL_DEG = 0.0045;

/** Key for the roughly 500 m x 500 m area containing `p`. */
export function cellKey(p: LatLng): string {
  const lngStep = CELL_DEG / Math.max(0.1, Math.cos((p.latitude * Math.PI) / 180));
  return `${Math.floor(p.latitude / CELL_DEG)}:${Math.floor(p.longitude / lngStep)}`;
}

export function shouldRefetch(lastFetchAt: LatLng | null, now: LatLng): boolean {
  return lastFetchAt == null || distanceM(lastFetchAt, now) > REFETCH_DISTANCE_M;
}

/** Nearest first from where the user is now, not from where the list was fetched. */
export function nearestFirst(shops: Shop[], from: LatLng): Shop[] {
  return shops
    .map((s) => ({ s, d: distanceM(from, s) }))
    .sort((a, b) => a.d - b.d)
    .map(({ s }) => s)
    .slice(0, MAX_CANDIDATES);
}

/** Validates the proxy's JSON so a bad response cannot crash the screen. */
export function parseShops(body: unknown): Shop[] {
  const list = (body as { shops?: unknown })?.shops;
  if (!Array.isArray(list)) throw new Error('bad response');
  return list.flatMap((x) => {
    const s = x as Record<string, unknown>;
    if (typeof s.id !== 'string' || typeof s.latitude !== 'number' || typeof s.longitude !== 'number') {
      return [];
    }
    return [
      {
        id: s.id,
        name: typeof s.name === 'string' ? s.name : 'Unnamed shop',
        latitude: s.latitude,
        longitude: s.longitude,
        address: typeof s.address === 'string' ? s.address : null,
        hours: typeof s.hours === 'string' && s.hours.trim() ? s.hours : null,
      },
    ];
  });
}
