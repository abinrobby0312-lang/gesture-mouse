/// <reference types="node" />
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addFix, classify, EMPTY_TRIP, verdict, type Fix, type Trip } from './trip';

const M_PER_DEG = 111_195;
const start = { latitude: 12.9716, longitude: 77.5946 };

/** A straight trip north at `mps`, one fix every `everyS` seconds. */
function travel(meters: number, mps: number, everyS = 2, accuracy = 8): Trip {
  let trip = EMPTY_TRIP;
  const steps = Math.ceil(meters / (mps * everyS));
  for (let i = 0; i <= steps; i++) {
    const d = Math.min(meters, i * mps * everyS);
    const fix: Fix = { latitude: start.latitude + d / M_PER_DEG, longitude: start.longitude, accuracy, at: i * everyS * 1000 };
    trip = addFix(trip, fix);
  }
  return trip;
}

test('a walk is counted and classified as walking', () => {
  const t = travel(800, 1.3);
  assert.ok(Math.abs(t.distanceM - 800) < 15);
  assert.equal(classify(t), 'walk');
  const v = verdict(t);
  assert.equal(v.kcal, 28); // 0.8 km x 70 kg x 0.5
  assert.match(v.receipt, /^Walked 800 m · 28 kcal$/);
  assert.match(v.quip, /beer/);
});

test('slow walking with 1 s fixes still adds up', () => {
  const t = travel(300, 1.0, 1);
  assert.ok(Math.abs(t.distanceM - 300) < 10);
});

test('running pace is a run and burns more', () => {
  const t = travel(1500, 3.2);
  assert.equal(classify(t), 'run');
  assert.equal(verdict(t).kcal, 105);
  assert.match(verdict(t).quip, /^You ran here/);
});

test('vehicle pace is wheels: 0 kcal and a no drink-driving line', () => {
  const t = travel(2500, 9);
  assert.equal(classify(t), 'wheels');
  const v = verdict(t);
  assert.equal(v.kcal, 0);
  assert.match(v.receipt, /^On wheels 2\.5 km · 0 kcal$/);
  assert.match(v.quip, /We noticed you/);
});

test('a short hop is a stroll', () => {
  assert.equal(classify(travel(30, 1.2)), 'stroll');
  assert.match(verdict(travel(30, 1.2)).quip, /bottle cap|next door/);
});

test('standing still with GPS jitter adds nothing', () => {
  let t = EMPTY_TRIP;
  for (let i = 0; i < 60; i++) {
    const wobble = (i % 2 ? 2 : -2) / M_PER_DEG;
    t = addFix(t, { latitude: start.latitude + wobble, longitude: start.longitude, accuracy: 10, at: i * 1000 });
  }
  assert.equal(t.distanceM, 0);
});

test('rough fixes and GPS jumps are ignored', () => {
  let t = addFix(EMPTY_TRIP, { ...start, accuracy: 8, at: 0 });
  t = addFix(t, { latitude: start.latitude + 500 / M_PER_DEG, longitude: start.longitude, accuracy: 120, at: 2000 });
  assert.equal(t.distanceM, 0); // too rough
  t = addFix(t, { latitude: start.latitude + 500 / M_PER_DEG, longitude: start.longitude, accuracy: 8, at: 2000 });
  assert.equal(t.distanceM, 0); // 250 m/s: a jump, not travel
});

test('one GPS spike does not turn a walk into a drive', () => {
  let t = travel(600, 1.3);
  const last = t.anchor!;
  t = addFix(t, { latitude: last.latitude + 60 / M_PER_DEG, longitude: last.longitude, accuracy: 8, at: last.at + 5000 });
  assert.equal(classify(t), 'walk');
});

test('earned share grows with distance', () => {
  assert.match(verdict(travel(200, 1.3)).quip, /smell|look/); // 7 kcal, under a sip
  assert.match(verdict(travel(1000, 1.3)).quip, /sip/); // 35 kcal, about 4 sips
  assert.match(verdict(travel(5000, 1.3)).quip, /beer/); // 175 kcal
});
