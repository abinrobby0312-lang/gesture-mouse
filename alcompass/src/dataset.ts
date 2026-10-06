// The bundled shop list: every alcohol shop OpenStreetMap knows in a country, built ahead of time by
// scripts/build-shops.ts and served as a static file. The phone filters it locally, so finding shops
// needs no live request at all. No imports beyond pure code, so tests and the build script can load it.
import { distanceM, RADIUS_M, type Shop as OsmShop } from './osm';
import type { Shop } from './shops';

/** [id, name, latitude, longitude, opening_hours, address] */
type Row = [string, string, number, number, string | null, string | null];

export type Dataset = {
  v: 1;
  country: string;
  generatedAt: string;
  attribution: string;
  /** [minLat, minLng, maxLat, maxLng] of the shops, so the app knows where the file applies. */
  bbox: [number, number, number, number];
  shops: Row[];
};

export const ATTRIBUTION = '© OpenStreetMap contributors (ODbL)';
const round5 = (n: number) => Math.round(n * 1e5) / 1e5;

export function encodeDataset(country: string, shops: OsmShop[], generatedAt = new Date().toISOString()): Dataset {
  const rows: Row[] = shops
    .map((s): Row => [s.id, s.name, round5(s.latitude), round5(s.longitude), s.hours, s.address])
    .sort((a, b) => a[2] - b[2] || a[3] - b[3]);
  const lats = rows.map((r) => r[2]);
  const lngs = rows.map((r) => r[3]);
  return {
    v: 1,
    country,
    generatedAt,
    attribution: ATTRIBUTION,
    bbox: rows.length ? [Math.min(...lats), Math.min(...lngs), Math.max(...lats), Math.max(...lngs)] : [0, 0, 0, 0],
    shops: rows,
  };
}

/** Validates a fetched dataset so a bad file cannot crash the screen. */
export function parseDataset(body: unknown): Dataset {
  const d = body as Partial<Dataset>;
  if (d?.v !== 1 || !Array.isArray(d.shops) || !Array.isArray(d.bbox) || d.bbox.length !== 4) {
    throw new Error('bad dataset');
  }
  return d as Dataset;
}

// About 3 km of latitude: a position this far outside the shops' box still gets shops near the edge.
const MARGIN_DEG = 0.03;

/** Whether the dataset covers this position; outside it the app searches live instead. */
export function covers(d: Dataset, lat: number, lng: number): boolean {
  const [a, b, c, e] = d.bbox;
  return lat >= a - MARGIN_DEG && lat <= c + MARGIN_DEG && lng >= b - MARGIN_DEG && lng <= e + MARGIN_DEG;
}

/** Shops within the search radius, nearest first, up to 20. */
export function nearby(d: Dataset, lat: number, lng: number): Shop[] {
  // Rows are sorted by latitude, so only a narrow band needs the full distance check.
  const band = RADIUS_M / 111_000 + 0.001;
  return d.shops
    .filter((r) => Math.abs(r[2] - lat) <= band)
    .map((r) => ({ r, dist: distanceM(lat, lng, r[2], r[3]) }))
    .filter(({ dist }) => dist <= RADIUS_M)
    .sort((x, y) => x.dist - y.dist)
    .slice(0, 20)
    .map(({ r }) => ({ id: r[0], name: r[1], latitude: r[2], longitude: r[3], hours: r[4], address: r[5] }));
}
