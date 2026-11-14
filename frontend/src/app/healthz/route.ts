export const dynamic = "force-dynamic";

/** Liveness for compose and CI; does not touch Keycloak or the backend. */
export function GET() {
  return Response.json({ status: "UP" });
}
