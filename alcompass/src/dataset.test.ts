/// <reference types="node" />
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { covers, encodeDataset, nearby, parseDataset } from './dataset';

const shop = (id: string, lat: number, lng: number, hours: string | null = null) => ({
  id, name: id, latitude: lat, longitude: lng, address: null, hours, distanceM: 0,
});

const d = encodeDataset('IN', [
  shop('far', 13.2, 77.59),
  shop('a', 12.9741, 77.5969, '10:00-22:00'),
  shop('b', 12.9688, 77.6012),
  shop('delhi', 28.61, 77.21),
], '2026-10-06T00:00:00Z');

test('encodes compact rows sorted by latitude, with the shops\' bounding box', () => {
  assert.deepEqual(d.shops.map((r) => r[0]), ['b', 'a', 'far', 'delhi']);
  assert.deepEqual(d.bbox, [12.9688, 77.21, 28.61, 77.6012]);
  assert.match(d.attribution, /OpenStreetMap/);
});

test('nearby finds shops within 3 km, nearest first, with their hours', () => {
  const near = nearby(d, 12.9716, 77.5946);
  assert.deepEqual(near.map((s) => s.id), ['a', 'b']);
  assert.equal(near[0].hours, '10:00-22:00');
  assert.deepEqual(nearby(d, 20, 80), []);
});

test('covers knows where the file applies', () => {
  assert.equal(covers(d, 12.97, 77.59), true);
  assert.equal(covers(d, 51.5, -0.12), false); // London: search live instead
});

test('parseDataset rejects a bad file', () => {
  assert.throws(() => parseDataset({ v: 2 }));
  assert.equal(parseDataset(JSON.parse(JSON.stringify(d))).shops.length, 4);
});
