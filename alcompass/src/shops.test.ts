/// <reference types="node" />
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cellKey, nearestFirst, parseShops, shouldRefetch, type Shop } from './shops';

const here = { latitude: 12.9716, longitude: 77.5946 };
const shop = (id: string, dLat: number): Shop => ({
  id,
  name: id,
  latitude: here.latitude + dLat,
  longitude: here.longitude,
  address: null,
  hours: null,
});

test('refetch only after moving more than 500 m', () => {
  assert.equal(shouldRefetch(null, here), true);
  assert.equal(shouldRefetch(here, { ...here, latitude: here.latitude + 0.003 }), false); // ~330 m
  assert.equal(shouldRefetch(here, { ...here, latitude: here.latitude + 0.006 }), true); // ~670 m
});

test('cell key is stable nearby and changes about 500 m away', () => {
  assert.equal(cellKey(here), cellKey({ latitude: here.latitude + 0.0001, longitude: here.longitude }));
  assert.notEqual(cellKey(here), cellKey({ latitude: here.latitude + 0.005, longitude: here.longitude }));
});

test('nearestFirst sorts from the current spot and keeps five', () => {
  const list = [shop('far', 0.02), shop('a', 0.001), shop('b', -0.002), shop('c', 0.003), shop('d', 0.004), shop('e', 0.005)];
  assert.deepEqual(nearestFirst(list, here).map((s) => s.id), ['a', 'b', 'c', 'd', 'e']);
});

test('parseShops keeps valid rows and rejects a malformed body', () => {
  const out = parseShops({
    shops: [
      { id: 'x', name: 'X Wines', latitude: 1, longitude: 2, address: 'Road', hours: 'Mo-Su 10:00-22:00' },
      { id: 'y', latitude: 'bad', longitude: 2 },
      { id: 'z', latitude: 3, longitude: 4 },
    ],
  });
  assert.deepEqual(out.map((s) => s.id), ['x', 'z']);
  assert.equal(out[1].name, 'Unnamed shop');
  assert.equal(out[0].hours, 'Mo-Su 10:00-22:00');
  assert.equal(out[1].hours, null);
  assert.throws(() => parseShops({ error: 'upstream_failed' }));
});

