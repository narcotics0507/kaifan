/** People are explicitly confirmed, never inferred or silently defaulted. */
export function validGuestCount(value) {
  return value !== null && value !== '' && Number.isInteger(Number(value)) && Number(value) >= 1 && Number(value) <= 99;
}
export function stepGuestCount(value, direction) {
  if (!validGuestCount(value)) return direction > 0 ? 1 : null;
  return Math.min(99, Math.max(1, Number(value) + direction));
}
export function guestCharge(value) { return validGuestCount(value) ? Number(value) : 0; }
