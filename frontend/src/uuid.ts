/**
 * A random (version 4) UUID, from crypto.getRandomValues.
 *
 * Not crypto.randomUUID: browsers expose it only in secure contexts (HTTPS, or localhost).
 * Every local run is on localhost, so it always worked there — and on the first plain-HTTP
 * deployment (ADR-023) it was undefined, the move click threw, and no move was ever sent
 * (found in 7.5 by capturing the socket's frames). getRandomValues is available in every
 * context, so this one code path runs the same locally and deployed.
 */
export function randomUuid(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6]! & 0x0f) | 0x40; // version 4
  bytes[8] = (bytes[8]! & 0x3f) | 0x80; // RFC 4122 variant
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
