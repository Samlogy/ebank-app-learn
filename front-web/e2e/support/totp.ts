import { createHmac } from 'node:crypto';

const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

function base32Decode(secret: string): Buffer {
  const clean = secret.replace(/[\s=]/g, '').toUpperCase();
  let bits = '';
  for (const char of clean) {
    const value = BASE32.indexOf(char);
    if (value < 0) {
      throw new Error(`Not a base32 secret: ${secret}`);
    }
    bits += value.toString(2).padStart(5, '0');
  }
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    bytes.push(parseInt(bits.slice(i, i + 8), 2));
  }
  return Buffer.from(bytes);
}

/** RFC 6238 code (SHA-1, 6 digits, 30 s): what an authenticator app shows for this secret. */
export function totp(secret: string, now = Date.now()): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(now / 1000 / 30)));
  const hash = createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const offset = hash[hash.length - 1] & 0xf;
  const code = (hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
  return String(code).padStart(6, '0');
}
