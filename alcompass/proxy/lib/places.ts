// Shop lookup against Google Places API (New). No imports, so tests can load it directly.

export type Shop = {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  address: string | null;
  /** null when Google has no opening hours for the place. */
  openNow: boolean | null;
  distanceM: number;
};

type Place = {
  id?: string;
  displayName?: { text?: string };
  location?: { latitude?: number; longitude?: number };
  formattedAddress?: string;
  types?: string[];
  businessStatus?: string;
  currentOpeningHours?: { openNow?: boolean };
};

export const NEARBY_URL = 'https://places.googleapis.com/v1/places:searchNearby';
export const TEXT_URL = 'https://places.googleapis.com/v1/places:searchText';

// Billed on this mask: ask only for what the screen uses.
export const FIELD_MASK = [
  'places.id',
  'places.displayName',
  'places.location',
  'places.formattedAddress',
  'places.types',
  'places.businessStatus',
  'places.currentOpeningHours.openNow',
].join(',');

export const RADIUS_M = 3000;
const MAX_SHOPS = 20;

// Off-premise retail is the MRP outlet. On-premise places are marked up.
const RETAIL_TYPE = 'liquor_store';
const ON_PREMISE_TYPES = new Set(['bar', 'night_club', 'restaurant']);

// Indian shops are often named this way and not typed liquor_store.
const TEXT_QUERIES = ['wine shop', 'liquor store'];

/**
 * Manual fixes for places Google labels wrongly.
 * `exclude`: place ids that are not MRP retail (e.g. a bar typed liquor_store).
 */
export const OVERRIDES: { exclude: Set<string> } = { exclude: new Set([]) };

export type Fetch = (url: string, init: RequestInit) => Promise<Response>;

export class PlacesError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`Places API returned ${status}`);
    this.status = status;
  }
}

export function parseCoords(lat: string | null, lng: string | null): { lat: number; lng: number } | null {
  if (lat == null || lng == null || lat.trim() === '' || lng.trim() === '') return null;
  const la = Number(lat);
  const ln = Number(lng);
  if (!Number.isFinite(la) || !Number.isFinite(ln)) return null;
  if (la < -90 || la > 90 || ln < -180 || ln > 180) return null;
  return { lat: la, lng: ln };
}

function distanceM(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const r = (d: number) => (d * Math.PI) / 180;
  const h =
    Math.sin(r(lat2 - lat1) / 2) ** 2 +
    Math.cos(r(lat1)) * Math.cos(r(lat2)) * Math.sin(r(lng2 - lng1) / 2) ** 2;
  return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
}

async function post(fetchImpl: Fetch, url: string, key: string, body: unknown): Promise<Place[]> {
  const res = await fetchImpl(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Goog-Api-Key': key,
      'X-Goog-FieldMask': FIELD_MASK,
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new PlacesError(res.status);
  const json = (await res.json()) as { places?: Place[] };
  return json.places ?? [];
}

/** Keeps a place only if it looks like operating off-premise retail. */
export function isRetail(p: Place, fromNearby: boolean): boolean {
  if (!p.id || OVERRIDES.exclude.has(p.id)) return false;
  if (p.businessStatus && p.businessStatus !== 'OPERATIONAL') return false;
  const types = p.types ?? [];
  if (types.some((t) => ON_PREMISE_TYPES.has(t))) return false;
  // Nearby Search was already restricted to liquor_store; text hits may be untyped shops.
  return fromNearby ? types.includes(RETAIL_TYPE) : true;
}

export async function findShops(
  lat: number,
  lng: number,
  key: string,
  fetchImpl: Fetch = fetch,
): Promise<Shop[]> {
  const circle = { center: { latitude: lat, longitude: lng }, radius: RADIUS_M };

  const [nearby, ...texts] = await Promise.all([
    post(fetchImpl, NEARBY_URL, key, {
      includedTypes: [RETAIL_TYPE],
      maxResultCount: 20,
      locationRestriction: { circle },
    }),
    // Text Search only takes a circle as a bias, so its results are cut to the radius below.
    ...TEXT_QUERIES.map((textQuery) =>
      post(fetchImpl, TEXT_URL, key, { textQuery, pageSize: 20, locationBias: { circle } }),
    ),
  ]);

  const byId = new Map<string, Shop>();
  const add = (p: Place, fromNearby: boolean) => {
    if (!isRetail(p, fromNearby) || byId.has(p.id!)) return;
    const pLat = p.location?.latitude;
    const pLng = p.location?.longitude;
    if (pLat == null || pLng == null) return;
    const d = distanceM(lat, lng, pLat, pLng);
    if (d > RADIUS_M) return;
    byId.set(p.id!, {
      id: p.id!,
      name: p.displayName?.text ?? 'Unnamed shop',
      latitude: pLat,
      longitude: pLng,
      address: p.formattedAddress ?? null,
      openNow: p.currentOpeningHours?.openNow ?? null,
      distanceM: Math.round(d),
    });
  };
  nearby.forEach((p) => add(p, true));
  texts.flat().forEach((p) => add(p, false));

  return [...byId.values()].sort((a, b) => a.distanceM - b.distanceM).slice(0, MAX_SHOPS);
}
