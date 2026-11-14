import { describe, expect, it } from "vitest";

import { seal, unseal } from "./session-crypto";

const SECRET = "test-secret-with-at-least-32-characters!";
const inOneHour = () => new Date(Date.now() + 3_600_000);

describe("sealed cookies", () => {
  it("round-trips a payload", async () => {
    const value = await seal({ sub: "u1", accessToken: "a" }, SECRET, "session", inOneHour());
    expect(await unseal(value, SECRET, "session")).toMatchObject({ sub: "u1", accessToken: "a" });
  });

  it("does not expose the payload", async () => {
    const value = await seal({ accessToken: "secret-token-value" }, SECRET, "session", inOneHour());
    expect(value).not.toContain("secret-token-value");
    expect(Buffer.from(value.split(".")[3] ?? "", "base64url").toString()).not.toContain("secret-token-value");
  });

  it("rejects another secret, another purpose, tampering and expiry", async () => {
    const value = await seal({ sub: "u1" }, SECRET, "session", inOneHour());
    expect(await unseal(value, "another-secret-with-at-least-32-characters", "session")).toBeNull();
    expect(await unseal(value, SECRET, "login-transaction")).toBeNull();
    const tampered = value.slice(0, -2) + (value.endsWith("A") ? "BB" : "AA");
    expect(await unseal(tampered, SECRET, "session")).toBeNull();
    const expired = await seal({ sub: "u1" }, SECRET, "session", new Date(Date.now() - 1000));
    expect(await unseal(expired, SECRET, "session")).toBeNull();
    expect(await unseal(undefined, SECRET, "session")).toBeNull();
    expect(await unseal("not-a-jwe", SECRET, "session")).toBeNull();
  });
});
