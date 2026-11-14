import "server-only";

export interface ServerConfig {
  appUrl: URL;
  backendUrl: URL;
  issuer: URL;
  clientId: string;
  clientSecret: string;
  sessionSecret: string;
}

let cached: ServerConfig | undefined;

function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing environment variable ${name} (see frontend/.env.example)`);
  }
  return value;
}

/** Server-side configuration, read once. Never import this from client components. */
export function serverConfig(): ServerConfig {
  if (cached) {
    return cached;
  }
  const sessionSecret = required("SESSION_SECRET");
  if (sessionSecret.length < 32) {
    throw new Error("SESSION_SECRET must have at least 32 characters");
  }
  cached = {
    appUrl: new URL(required("APP_URL")),
    backendUrl: new URL(required("BACKEND_URL")),
    issuer: new URL(required("OIDC_ISSUER")),
    clientId: required("OIDC_CLIENT_ID"),
    clientSecret: required("OIDC_CLIENT_SECRET"),
    sessionSecret,
  };
  return cached;
}
