/// <reference types="node" />
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FIELD_MASK, findShops, NEARBY_URL, parseCoords, PlacesError, TEXT_URL, type Fetch } from './places';

// MG Road, Bangalore.
const LAT = 12.9755;
const LNG = 77.6068;

const place = (id: string, types: string[], dLat = 0.001, extra: object = {}) => ({
  id,
  displayName: { text: `Shop ${id}` },
  location: { latitude: LAT + dLat, longitude: LNG },
  formattedAddress: `${id} Road`,
  types,
  businessStatus: 'OPERATIONAL',
  ...extra,
});

function fakeFetch(nearby: object[], text: object[]) {
  const calls: { url: string; init: RequestInit }[] = [];
  const f: Fetch = async (url, init) => {
    calls.push({ url, init });
    const places = url === NEARBY_URL ? nearby : text;
    return new Response(JSON.stringify({ places }), { status: 200 });
  };
  return { f, calls };
}

test('sends the field mask and key, restricts nearby to liquor_store in 3 km', async () => {
  const { f, calls } = fakeFetch([], []);
  await findShops(LAT, LNG, 'k', f);
  assert.equal(calls.length, 3);
  for (const c of calls) {
    const h = c.init.headers as Record<string, string>;
    assert.equal(h['X-Goog-Api-Key'], 'k');
    assert.equal(h['X-Goog-FieldMask'], FIELD_MASK);
  }
  const nearbyBody = JSON.parse(calls.find((c) => c.url === NEARBY_URL)!.init.body as string);
  assert.deepEqual(nearbyBody.includedTypes, ['liquor_store']);
  assert.equal(nearbyBody.locationRestriction.circle.radius, 3000);
  const queries = calls.filter((c) => c.url === TEXT_URL).map((c) => JSON.parse(c.init.body as string).textQuery);
  assert.deepEqual(queries.sort(), ['alcohol shop', 'wine shop']);
});

test('drops closed places, keeps every open one', async () => {
  const { f } = fakeFetch(
    [
      place('shop', ['liquor_store', 'store']),
      place('barshop', ['liquor_store', 'bar']),
      place('closed', ['liquor_store'], 0.001, { businessStatus: 'CLOSED_PERMANENTLY' }),
      place('paused', ['liquor_store'], 0.001, { businessStatus: 'CLOSED_TEMPORARILY' }),
    ],
    [],
  );
  const shops = await findShops(LAT, LNG, 'k', f);
  assert.deepEqual(shops.map((s) => s.id).sort(), ['barshop', 'shop']);
});

test('merges text results on id, sorts by distance, cuts to radius', async () => {
  const { f } = fakeFetch(
    [place('a', ['liquor_store'], 0.005)],
    [
      place('a', ['liquor_store'], 0.005), // duplicate of nearby
      place('wineshop', ['store'], 0.002), // untyped "wine shop", nearer
      place('far', ['store'], 0.05), // about 5.5 km away
    ],
  );
  const shops = await findShops(LAT, LNG, 'k', f);
  assert.deepEqual(shops.map((s) => s.id), ['wineshop', 'a']);
  assert.equal(shops[0].distanceM, 222);
  assert.equal(shops[0].openNow, null);
});

test('reports upstream status as PlacesError', async () => {
  const f: Fetch = async () => new Response('{}', { status: 403 });
  await assert.rejects(findShops(LAT, LNG, 'k', f), (e: unknown) => e instanceof PlacesError && e.status === 403);
});

test('parseCoords', () => {
  assert.deepEqual(parseCoords('12.97', '77.59'), { lat: 12.97, lng: 77.59 });
  assert.equal(parseCoords(null, '1'), null);
  assert.equal(parseCoords('', '1'), null);
  assert.equal(parseCoords('abc', '1'), null);
  assert.equal(parseCoords('91', '0'), null);
  assert.equal(parseCoords('0', '181'), null);
});
