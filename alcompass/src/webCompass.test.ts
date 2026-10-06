/// <reference types="node" />
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { headingFromOrientation } from './webCompass';
import { courseFrom } from './sensorTypes';

test('Chrome absolute alpha runs counter-clockwise from north', () => {
  assert.deepEqual(headingFromOrientation({ alpha: 0, absolute: true }, 0), { degrees: 0, calibrating: false });
  assert.equal(headingFromOrientation({ alpha: 90, absolute: true }, 0)?.degrees, 270);
  assert.equal(headingFromOrientation({ alpha: 270, absolute: true }, 0)?.degrees, 90);
});

test('Chrome heading follows the screen when rotated', () => {
  assert.equal(headingFromOrientation({ alpha: 0, absolute: true }, 90)?.degrees, 90);
});

test('relative orientation events are ignored', () => {
  assert.equal(headingFromOrientation({ alpha: 123, absolute: false }, 0), null);
  assert.equal(headingFromOrientation({ alpha: null, absolute: true }, 0), null);
});

test('Safari compass heading and accuracy', () => {
  assert.deepEqual(
    headingFromOrientation({ alpha: 10, absolute: false, webkitCompassHeading: 45, webkitCompassAccuracy: 10 }, 0),
    { degrees: 45, calibrating: false },
  );
  assert.equal(
    headingFromOrientation({ alpha: 10, absolute: false, webkitCompassHeading: 45, webkitCompassAccuracy: 50 }, 0)
      ?.calibrating,
    true,
  );
  assert.equal(
    headingFromOrientation({ alpha: 10, absolute: false, webkitCompassHeading: 45, webkitCompassAccuracy: -1 }, 0)
      ?.calibrating,
    true,
  );
});

test('GPS course only counts while moving', () => {
  assert.equal(courseFrom(1.2, 80), 80);
  assert.equal(courseFrom(0.3, 80), null);
  assert.equal(courseFrom(null, 80), null);
  assert.equal(courseFrom(1.2, NaN), null);
});
