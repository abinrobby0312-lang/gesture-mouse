/// <reference types="node" />
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildQuery, findShops, OVERPASS_URLS, parseCoords, UpstreamError, USER_AGENT, type Fetch } from './osm';

// MG Road, Bangalore.
const LAT = 12.9755;
const LNG = 77.6068;

const node = (id: number, dLat: number, tags: Record<string, string>) => ({ type: 'node', id, lat: LAT + dLat, lon: LNG, tags });

function fakeFetch(...responses: (object[] | number)[]) {
  const calls: { url: string; init: RequestInit }[] = [];
  const f: Fetch = async (url, init) => {
    calls.push({ url, init });
    const r = responses[Math.min(calls.length - 1, responses.length - 1)];
    return typeof r === 'number' ? new Response('busy', { status: r }) : new Response(JSON.stringify({ elements: r }), { status: 200 });
  };
  return { f, calls };
}

test('asks Overpass for alcohol and wine shops and wine-named shops in 3 km, identifying itself', async () => {
  const { f, calls } = fakeFetch([]);
  await findShops(LAT, LNG, { fetchImpl: f, userAgent: USER_AGENT });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, OVERPASS_URLS[0]);
  assert.equal((calls[0].init.headers as Record<string, string>)['User-Agent'], USER_AGENT);
  const q = buildQuery(LAT, LNG);
  assert.match(q, /"shop"~"\^\(alcohol\|wine\)\$"/);
  assert.match(q, /"name"~"wine\|liquor/);
  assert.match(q, /around:3000,12\.97550,77\.60680/);
  assert.equal(decodeURIComponent(String(calls[0].init.body).slice(5)), q);
});

test('maps elements to shops, nearest first, keeps hours, drops disused and far ones', async () => {
  const { f } = fakeFetch([
    node(1, 0.005, { shop: 'alcohol', name: 'Muni Wines', opening_hours: '10:00-22:00' }),
    node(2, 0.002, { shop: 'alcohol', name: 'Tonique', 'addr:street': 'Church Street' }),
    { type: 'way', id: 3, center: { lat: LAT + 0.001, lon: LNG }, tags: { shop: 'wine' } },
    node(4, 0.001, { 'disused:shop': 'alcohol', shop: 'alcohol', name: 'Gone Wines' }),
    node(5, 0.05, { shop: 'alcohol', name: 'Far Wines' }),
  ]);
  const shops = await findShops(LAT, LNG, { fetchImpl: f });
  assert.deepEqual(shops.map((s) => s.id), ['way/3', 'node/2', 'node/1']);
  assert.equal(shops[0].name, 'Liquor shop');
  assert.equal(shops[1].address, 'Church Street');
  assert.equal(shops[2].hours, '10:00-22:00');
  assert.equal(shops[1].hours, null);
  assert.equal(shops[1].distanceM, 222);
});

test('falls through to the next instance when one is busy', async () => {
  const { f, calls } = fakeFetch(429, [node(1, 0.001, { shop: 'alcohol', name: 'A' })]);
  const shops = await findShops(LAT, LNG, { fetchImpl: f });
  assert.equal(calls.length, 2);
  assert.equal(calls[1].url, OVERPASS_URLS[1]);
  assert.equal(shops.length, 1);
});

test('reports the upstream status when every instance fails', async () => {
  const { f } = fakeFetch(504);
  await assert.rejects(findShops(LAT, LNG, { fetchImpl: f }), (e: unknown) => e instanceof UpstreamError && e.status === 504);
});

test('a hung instance times out and the next one answers', async () => {
  let n = 0;
  const f: Fetch = (url, init) => {
    n++;
    if (n === 1) {
      return new Promise((_, reject) => init.signal?.addEventListener('abort', () => reject(new Error('aborted'))));
    }
    return Promise.resolve(new Response(JSON.stringify({ elements: [node(1, 0.001, { shop: 'alcohol', name: 'A' })] })));
  };
  const shops = await findShops(LAT, LNG, { fetchImpl: f, timeoutMs: 20 });
  assert.equal(shops.length, 1);
});

test('browsers send no User-Agent header', async () => {
  const { f, calls } = fakeFetch([]);
  await findShops(LAT, LNG, { fetchImpl: f });
  assert.equal((calls[0].init.headers as Record<string, string>)['User-Agent'], undefined);
});

test('parseCoords', () => {
  assert.deepEqual(parseCoords('12.9', '77.5'), { lat: 12.9, lng: 77.5 });
  assert.equal(parseCoords('', '77.5'), null);
  assert.equal(parseCoords('91', '0'), null);
  assert.equal(parseCoords('abc', '1'), null);
});
