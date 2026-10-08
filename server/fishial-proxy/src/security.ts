// Cloudflare Workers caps a single PBKDF2 operation at 100,000 iterations.
const PASSWORD_ITERATIONS = 100_000;
// Compared against when no real password hash exists, so a missing account takes as long as a wrong password.
export const DUMMY_PASSWORD_SALT = "00".repeat(16);

export async function hashPassword(password: string, saltHex: string): Promise<string> {
  const material = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const derived = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: hexToBytes(saltHex), iterations: PASSWORD_ITERATIONS },
    material,
    256,
  );
  return bytesToHex(new Uint8Array(derived));
}

export async function sha256(value: string): Promise<string> {
  return bytesToHex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))));
}

export function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return bytesToHex(bytes);
}

export function bytesToHex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function hexToBytes(value: string): Uint8Array<ArrayBuffer> {
  return new Uint8Array(value.match(/.{2}/g)?.map((byte) => parseInt(byte, 16)) || []);
}

export function constantTimeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index++) difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
  return difference === 0;
}

/** Hash the supplied password whether or not the account exists, then compare in constant time. */
export async function passwordMatches(password: string, stored: { password_hash: string; password_salt: string } | null): Promise<boolean> {
  if (!password) return false;
  const hasPassword = Boolean(stored?.password_hash);
  const derived = await hashPassword(password, hasPassword ? stored!.password_salt : DUMMY_PASSWORD_SALT);
  return hasPassword && constantTimeEqual(derived, stored!.password_hash);
}
