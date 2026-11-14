/**
 * Where to go after sign-in. Only same-origin absolute paths are allowed, so a crafted login link
 * cannot send the user to another site (open redirect).
 */
export function safeReturnTo(value: string | null | undefined): string {
  if (!value || !value.startsWith("/") || value.startsWith("//") || value.startsWith("/\\")) {
    return "/";
  }
  if (/[\u0000-\u001f\\]/.test(value) || value.startsWith("/auth/")) {
    return "/";
  }
  return value;
}
