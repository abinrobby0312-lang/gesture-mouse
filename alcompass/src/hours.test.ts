/// <reference types="node" />
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openState, parseHours } from './hours';

// Local times, as the phone sees them. 2026-10-05 is a Monday.
const at = (day: number, hh: number, mm = 0) => new Date(2026, 9, 5 + day, hh, mm).getTime();
const MON = 0, SAT = 5, SUN = 6;

test('a plain daily span', () => {
  assert.equal(openState('10:00-22:00', at(MON, 9, 59))?.open, false);
  const s = openState('10:00-22:00', at(MON, 15));
  assert.equal(s?.open, true);
  assert.equal(s?.closesAt, at(MON, 22));
  assert.equal(openState('10:00-22:00', at(MON, 22))?.open, false);
});

test('day selectors, a later rule overriding an earlier one, and off days', () => {
  const tag = 'Mo-Sa 10:00-22:30; Su off';
  assert.equal(openState(tag, at(SAT, 22, 15))?.open, true);
  assert.equal(openState(tag, at(SUN, 12))?.open, false);
  assert.equal(openState('Mo-Su 10:00-22:00; Su 12:00-20:00', at(SUN, 11))?.open, false);
  assert.equal(openState('Mo,We,Fr 09:00-17:00', at(1, 12))?.open, false); // Tuesday
});

test('split spans and spans past midnight', () => {
  const split = '10:00-14:00,17:00-23:00';
  assert.equal(openState(split, at(MON, 15))?.open, false);
  assert.equal(openState(split, at(MON, 18))?.closesAt, at(MON, 23));
  const late = 'Fr 18:00-02:00';
  const s = openState(late, at(SAT, 1, 30)); // Saturday 01:30, still Friday night
  assert.equal(s?.open, true);
  assert.equal(s.closesAt, at(SAT, 2));
});

test('24/7 is always open', () => {
  assert.equal(openState('24/7', at(SUN, 3))?.open, true);
});

test('missing or unreadable hours give no answer rather than a guess', () => {
  assert.equal(openState(null, at(MON, 21)), null);
  assert.equal(parseHours('Mo-Fr 10:00-22:00; PH off'), null);
  assert.equal(openState('Mo-Fr 10:00-22:00; PH off', at(MON, 21)), null);
});
