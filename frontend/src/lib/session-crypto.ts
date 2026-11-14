import { hkdfSync } from "node:crypto";
import { EncryptJWT, jwtDecrypt, type JWTPayload } from "jose";

/**
 * Encrypted cookie payloads (ADR-0006). JWE with direct AES-256-GCM: the browser can neither read nor
 * change the content. Each purpose (session, login transaction) uses its own key derived from
 * SESSION_SECRET, so a value sealed for one purpose is rejected by the other.
 */
export type SealPurpose = "session" | "login-transaction";

function key(secret: string, purpose: SealPurpose): Uint8Array {
  return new Uint8Array(hkdfSync("sha256", secret, "operator-web", `cookie:${purpose}`, 32));
}

export async function seal(
  payload: JWTPayload,
  secret: string,
  purpose: SealPurpose,
  expiresAt: Date,
): Promise<string> {
  return new EncryptJWT(payload)
    .setProtectedHeader({ alg: "dir", enc: "A256GCM", typ: purpose })
    .setIssuedAt()
    .setExpirationTime(Math.floor(expiresAt.getTime() / 1000))
    .encrypt(key(secret, purpose));
}

/** Returns null for anything that is not a valid, unexpired value sealed for this purpose. */
export async function unseal<T extends JWTPayload>(
  value: string | undefined,
  secret: string,
  purpose: SealPurpose,
): Promise<T | null> {
  if (!value) {
    return null;
  }
  try {
    const { payload, protectedHeader } = await jwtDecrypt<T>(value, key(secret, purpose), {
      keyManagementAlgorithms: ["dir"],
      contentEncryptionAlgorithms: ["A256GCM"],
    });
    return protectedHeader.typ === purpose ? payload : null;
  } catch {
    return null;
  }
}
