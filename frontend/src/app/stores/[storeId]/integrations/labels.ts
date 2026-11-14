import type { components } from "@/api/schema";

export type Integration = components["schemas"]["IntegrationResponse"];
type Status = Integration["status"];
type ErrorCode = NonNullable<Integration["lastErrorCode"]>;
type Warning = Integration["warnings"][number];

export const STATUS_LABELS: Record<Status, string> = {
  PENDING_CREDENTIALS: "Czeka na klucz API",
  CHECK_REQUIRED: "Wymaga sprawdzenia",
  CONNECTED: "Połączono",
  ERROR: "Błąd połączenia",
};

/** One repair step per check error (ADR-0007, 04 §10). */
export const ERROR_HELP: Record<ErrorCode, string> = {
  AUTH_FAILED: "WooCommerce odrzucił klucz. Sprawdź consumer key i secret albo utwórz nowy klucz.",
  PERMISSION_DENIED: "Klucz nie może czytać produktów lub zamówień. Utwórz klucz z uprawnieniem Odczyt.",
  UNREACHABLE: "Nie udało się połączyć ze sklepem. Sprawdź adres i czy sklep działa.",
  TLS_ERROR: "Certyfikat HTTPS sklepu jest nieprawidłowy. Popraw certyfikat u dostawcy hostingu.",
  NOT_WOOCOMMERCE: "Pod tym adresem nie ma API WooCommerce. Sprawdź adres i czy włączone są bezpośrednie odnośniki.",
  ADDRESS_NOT_ALLOWED: "Ten adres wskazuje sieć prywatną i nie może być użyty.",
  UNEXPECTED_RESPONSE: "Sklep zwrócił nieoczekiwaną odpowiedź. Spróbuj ponownie później.",
};

export const WARNING_HELP: Record<Warning, string> = {
  CURRENCY_MISMATCH: "Waluta w WooCommerce różni się od waluty w ustawieniach sklepu. Popraw jedną z nich, zanim powstaną metryki.",
  CURRENCY_UNKNOWN: "Nie udało się odczytać waluty WooCommerce, więc nie została porównana z ustawieniami sklepu.",
};

/** Formats an instant in the store's timezone (04 §2: times shown in store time). */
export function formatInstant(value: string | undefined, timezone: string): string {
  if (!value) {
    return "—";
  }
  return new Intl.DateTimeFormat("pl-PL", { dateStyle: "medium", timeStyle: "short", timeZone: timezone }).format(
    new Date(value),
  );
}

