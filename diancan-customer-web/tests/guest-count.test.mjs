import test from 'node:test';
import assert from 'node:assert/strict';
import { validGuestCount, stepGuestCount, guestCharge } from '../guest-count.js';

test('people remain unconfirmed until an explicit choice and empty decrease cannot infer a count', () => {
  assert.equal(validGuestCount(null), false); assert.equal(guestCharge(null), 0);
  assert.equal(stepGuestCount(null, -1), null); assert.equal(stepGuestCount(null, 1), 1);
});
test('stepper supports typed integers and both boundaries without zero or overflow', () => {
  assert.equal(stepGuestCount(2, -1), 1); assert.equal(stepGuestCount(1, -1), 1);
  assert.equal(stepGuestCount('8', 1), 9); assert.equal(stepGuestCount(99, 1), 99);
});
test('invalid people never create a fake fee or NaN total', () => {
  for(const value of ['', 0, -1, 100, 2.5, NaN, 'abc']) { assert.equal(validGuestCount(value), false); assert.equal(guestCharge(value), 0); }
  assert.equal(32 + guestCharge(2), 34); assert.equal(guestCharge(99), 99);
});
