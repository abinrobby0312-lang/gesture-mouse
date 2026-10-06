// "Alcohol shops near me" against Google Places API (New). No imports, so tests can load it directly.

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

const SHOP_TYPE = 'liquor_store';

// The plain search a person would type, plus "wine shop", which is how many Indian shops are named
// (and often not typed liquor_store).
const TEXT_QUERIES = ['alcohol shop', 'wine shop'];

/** Place ids Google lists that are not alcohol shops at all. */
export const EXCLUDE = new Set<string>([]);

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

/** Drops excluded and closed places; everything else Google returns for the searches is kept. */
export function isOpenForBusiness(p: Place): boolean {
  if (!p.id || EXCLUDE.has(p.id)) return false;
  return !p.businessStatus || p.businessStatus === 'OPERATIONAL';
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
      includedTypes: [SHOP_TYPE],
      maxResultCount: 20,
      locationRestriction: { circle },
    }),
    // Text Search only takes a circle as a bias, so its results are cut to the radius below.
    ...TEXT_QUERIES.map((textQuery) =>
      post(fetchImpl, TEXT_URL, key, { textQuery, pageSize: 20, locationBias: { circle } }),
    ),
  ]);

  const byId = new Map<string, Shop>();
  for (const p of [...nearby, ...texts.flat()]) {
    if (!isOpenForBusiness(p) || byId.has(p.id!)) continue;
    const pLat = p.location?.latitude;
    const pLng = p.location?.longitude;
    if (pLat == null || pLng == null) continue;
    const d = distanceM(lat, lng, pLat, pLng);
    if (d > RADIUS_M) continue;
    byId.set(p.id!, {
      id: p.id!,
      name: p.displayName?.text ?? 'Unnamed shop',
      latitude: pLat,
      longitude: pLng,
      address: p.formattedAddress ?? null,
      openNow: p.currentOpeningHours?.openNow ?? null,
      distanceM: Math.round(d),
    });
  }

  return [...byId.values()].sort((a, b) => a.distanceM - b.distanceM).slice(0, MAX_SHOPS);
}
