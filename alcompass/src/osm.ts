// "Alcohol shops near me" from OpenStreetMap through the Overpass API: free, no key.
// Runs on the phone (the fast path) and in /api/shops (the fallback). No imports, so tests can load it.

export type Shop = {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  address: string | null;
  /** The raw OpenStreetMap opening_hours value, or null when the shop has none. */
  hours: string | null;
  distanceM: number;
};

export type Element = {
  type: 'node' | 'way' | 'relation';
  id: number;
  lat?: number;
  lon?: number;
  center?: { lat: number; lon: number };
  tags?: Record<string, string>;
};

// Public Overpass instances, tried in order. Both ask clients to identify themselves.
export const OVERPASS_URLS = [
  'https://overpass-api.de/api/interpreter',
  'https://overpass.private.coffee/api/interpreter',
];
export const USER_AGENT = 'Alcompass/1.0 (+https://alcompass-nine.vercel.app)';

export const RADIUS_M = 3000;
const MAX_SHOPS = 20;

/** OpenStreetMap ids that are not alcohol shops at all, as "type/id". */
export const EXCLUDE = new Set<string>([]);

export type Fetch = (url: string, init: RequestInit) => Promise<Response>;

export class UpstreamError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`Overpass returned ${status}`);
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

/**
 * Shops tagged as alcohol or wine shops, plus any shop whose name says wine or liquor, since many
 * Indian shops are named "... Wines" without the right tag.
 */
export function buildQuery(lat: number, lng: number): string {
  const around = `(around:${RADIUS_M},${lat.toFixed(5)},${lng.toFixed(5)})`;
  return (
    '[out:json][timeout:20];(' +
    `nwr["shop"~"^(alcohol|wine)$"]${around};` +
    `nwr["shop"]["name"~"wine|liquor|liqour|spirits",i]${around};` +
    ');out center tags;'
  );
}

/** Every alcohol shop in one country, for the bundled dataset. Takes a few minutes; run at build time. */
export function buildCountryQuery(iso: string): string {
  return (
    `[out:json][timeout:600];area["ISO3166-1"="${iso}"][admin_level=2]->.c;(` +
    'nwr["shop"~"^(alcohol|wine)$"](area.c);' +
    'nwr["shop"]["name"~"wine|liquor|liqour|spirits",i](area.c);' +
    ');out center tags;'
  );
}

export function distanceM(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const r = (d: number) => (d * Math.PI) / 180;
  const h =
    Math.sin(r(lat2 - lat1) / 2) ** 2 +
    Math.cos(r(lat1)) * Math.cos(r(lat2)) * Math.sin(r(lng2 - lng1) / 2) ** 2;
  return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
}

function addressOf(t: Record<string, string>): string | null {
  const parts = [t['addr:housenumber'], t['addr:street'], t['addr:suburb'] ?? t['addr:city']].filter(Boolean);
  return t['addr:full'] ?? (parts.length ? parts.join(', ') : null);
}

export type FindOptions = {
  fetchImpl?: Fetch;
  /** Sent from servers, as Overpass asks. Browsers set their own and must not send one. */
  userAgent?: string;
  /** Give up on an instance after this long and try the next. */
  timeoutMs?: number;
};

export async function query(url: string, q: string, o: Required<Omit<FindOptions, 'userAgent'>> & FindOptions): Promise<Element[]> {
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), o.timeoutMs);
  try {
    const headers: Record<string, string> = { 'Content-Type': 'application/x-www-form-urlencoded' };
    if (o.userAgent) headers['User-Agent'] = o.userAgent;
    const res = await o.fetchImpl(url, { method: 'POST', headers, body: `data=${encodeURIComponent(q)}`, signal: abort.signal });
    if (!res.ok) throw new UpstreamError(res.status);
    const json = (await res.json()) as { elements?: Element[] };
    return json.elements ?? [];
  } finally {
    clearTimeout(timer);
  }
}

export async function findShops(lat: number, lng: number, options: FindOptions = {}): Promise<Shop[]> {
  const o = { fetchImpl: options.fetchImpl ?? fetch, timeoutMs: options.timeoutMs ?? 15_000, userAgent: options.userAgent };
  const q = buildQuery(lat, lng);
  let elements: Element[] | null = null;
  let lastError: unknown = null;
  // A busy, rate-limited or hung instance is common; fall through to the next one.
  for (const url of OVERPASS_URLS) {
    try {
      elements = await query(url, q, o);
      break;
    } catch (e) {
      lastError = e;
    }
  }
  if (!elements) throw lastError instanceof UpstreamError ? lastError : new UpstreamError(0);

  return elementsToShops(elements, { lat, lng });
}

/**
 * Overpass elements to shops. With a center, only shops within RADIUS_M of it, nearest first and
 * capped at MAX_SHOPS; without one (the bundled dataset), all of them.
 */
export function elementsToShops(elements: Element[], center: { lat: number; lng: number } | null): Shop[] {
  const byId = new Map<string, Shop>();
  for (const el of elements) {
    const id = `${el.type}/${el.id}`;
    const t = el.tags ?? {};
    if (EXCLUDE.has(id) || byId.has(id)) continue;
    // Closed for good, or a shop that no longer exists.
    if (t['disused:shop'] || t['abandoned:shop'] || t.opening_hours === 'closed' || t.opening_hours === 'off') continue;
    const pLat = el.lat ?? el.center?.lat;
    const pLng = el.lon ?? el.center?.lon;
    if (pLat == null || pLng == null) continue;
    const d = center ? distanceM(center.lat, center.lng, pLat, pLng) : 0;
    if (d > RADIUS_M) continue;
    byId.set(id, {
      id,
      name: t.name ?? t['name:en'] ?? 'Liquor shop',
      latitude: pLat,
      longitude: pLng,
      address: addressOf(t),
      hours: t.opening_hours ?? null,
      distanceM: Math.round(d),
    });
  }
  const all = [...byId.values()];
  return center ? all.sort((a, b) => a.distanceM - b.distanceM).slice(0, MAX_SHOPS) : all;

}
