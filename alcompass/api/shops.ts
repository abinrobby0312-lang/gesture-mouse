import { findShops, parseCoords, PlacesError } from '../server/places';

// GET /api/shops?lat=12.97&lng=77.59
// Logs nothing about the caller: no coordinates, no IPs.
export async function GET(request: Request): Promise<Response> {
  const key = process.env.GOOGLE_PLACES_API_KEY;
  if (!key) return json({ error: 'server_not_configured' }, 500);

  const params = new URL(request.url).searchParams;
  const coords = parseCoords(params.get('lat'), params.get('lng'));
  if (!coords) return json({ error: 'bad_coordinates' }, 400);

  try {
    const shops = await findShops(coords.lat, coords.lng, key);
    // Caching lives in the app (per 500 m area), so coordinates never sit in a shared cache.
    return json({ shops }, 200, { 'Cache-Control': 'no-store' });
  } catch (e) {
    const status = e instanceof PlacesError ? e.status : 0;
    console.error(`places lookup failed: ${status}`);
    return json({ error: 'upstream_failed' }, 502);
  }
}

function json(body: unknown, status: number, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}
