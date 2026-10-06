/// <reference types="node" />
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  bearingDeg,
  compassPoint,
  distanceM,
  formatDistance,
  needleAngleDeg,
  normalizeDeg,
  shortestDeltaDeg,
} from './geo';
import { HeadingFilter } from './headingFilter';

const close = (actual: number, expected: number, tol: number) =>
  assert.ok(Math.abs(actual - expected) <= tol, `${actual} not within ${tol} of ${expected}`);

test('normalizeDeg wraps into [0, 360)', () => {
  assert.equal(normalizeDeg(-10), 350);
  assert.equal(normalizeDeg(370), 10);
  assert.equal(normalizeDeg(360), 0);
});

test('bearing along the cardinal directions', () => {
  const o = { latitude: 12.97, longitude: 77.59 };
  close(bearingDeg(o, { latitude: 13.0, longitude: 77.59 }), 0, 0.01);
  close(bearingDeg(o, { latitude: 12.97, longitude: 77.62 }), 90, 0.1);
  close(bearingDeg(o, { latitude: 12.94, longitude: 77.59 }), 180, 0.01);
  close(bearingDeg(o, { latitude: 12.97, longitude: 77.56 }), 270, 0.1);
});

test('bearing matches a known long route (London to Paris, about 148 degrees)', () => {
  close(
    bearingDeg({ latitude: 51.5074, longitude: -0.1278 }, { latitude: 48.8566, longitude: 2.3522 }),
    148.1,
    0.5,
  );
});

test('haversine distance', () => {
  // One degree of latitude is about 111.2 km.
  close(distanceM({ latitude: 0, longitude: 0 }, { latitude: 1, longitude: 0 }), 111195, 50);
  // MG Road metro to Cubbon Park metro, Bangalore: roughly 1 km.
  close(
    distanceM({ latitude: 12.9755, longitude: 77.6068 }, { latitude: 12.9810, longitude: 77.5975 }),
    1170,
    60,
  );
});

test('needle angle is bearing minus heading', () => {
  assert.equal(needleAngleDeg(90, 0), 90);
  assert.equal(needleAngleDeg(10, 350), 20);
  assert.equal(needleAngleDeg(350, 10), 340);
});

test('shortest delta never goes the long way', () => {
  assert.equal(shortestDeltaDeg(350, 10), 20);
  assert.equal(shortestDeltaDeg(10, 350), -20);
  assert.equal(shortestDeltaDeg(0, 180), 180);
});

test('formatDistance', () => {
  assert.equal(formatDistance(42.4), '42 m');
  assert.equal(formatDistance(999), '999 m');
  assert.equal(formatDistance(1000), '1.0 km');
  assert.equal(formatDistance(2349), '2.3 km');
});

test('compassPoint', () => {
  assert.equal(compassPoint(0), 'N');
  assert.equal(compassPoint(350), 'N');
  assert.equal(compassPoint(100), 'E');
  assert.equal(compassPoint(225), 'SW');
});

test('heading filter stays near north across the 0/360 seam', () => {
  const f = new HeadingFilter(0.5);
  f.push(355);
  const v = f.push(5);
  // Averaging raw degrees would give 180.
  assert.ok(v > 355 || v < 5, `got ${v}`);
});

test('heading filter converges on a steady input', () => {
  const f = new HeadingFilter(0.2);
  f.push(0);
  let v = 0;
  for (let i = 0; i < 60; i++) v = f.push(90);
  close(v, 90, 0.1);
});
