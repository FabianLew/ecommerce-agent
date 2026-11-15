"use server";

import { revalidatePath } from "next/cache";

import { getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

export type ProfileFormState = { status: "idle" | "error" | "saved"; message?: string; field?: string };

type Goal = "PROFITABILITY" | "GROWTH" | "BALANCED";
type Risk = "LOW" | "MEDIUM" | "HIGH";
type Priority = "INVENTORY_CONTINUITY" | "CONTRIBUTION_MARGIN" | "CASH_PRESERVATION" | "CUSTOMER_ACQUISITION";

const GOALS: Goal[] = ["PROFITABILITY", "GROWTH", "BALANCED"];
const RISKS: Risk[] = ["LOW", "MEDIUM", "HIGH"];
const PRIORITIES: Priority[] = ["INVENTORY_CONTINUITY", "CONTRIBUTION_MARGIN", "CASH_PRESERVATION", "CUSTOMER_ACQUISITION"];

function pick<T extends string>(value: FormDataEntryValue | null, allowed: T[]): T | undefined {
  return allowed.find((item) => item === value);
}

/** Converts a decimal amount typed by the user into minor units without floating point. */
function toMinorUnits(value: string): number | undefined {
  const match = /^(\d{1,12})(?:[.,](\d{1,2}))?$/.exec(value.trim());
  if (!match) {
    return undefined;
  }
  return Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
}

export async function saveProfileDraft(
  storeId: string,
  expectedRevision: number,
  _previous: ProfileFormState,
  form: FormData,
): Promise<ProfileFormState> {
  const path = storePath(storeId, "settings");
  const t = (await getMessages()).profile;
  const horizon = String(form.get("planningHorizonDays") ?? "").trim();
  const spend = String(form.get("plannedAdSpend") ?? "").trim();
  const plannedAdSpendMinor = spend ? toMinorUnits(spend) : undefined;
  if (spend && plannedAdSpendMinor === undefined) {
    return { status: "error", message: t.invalid, field: "plannedAdSpendMinor" };
  }
  const api = await backend(path);
  const saved = await api.PUT("/api/v1/stores/{storeId}/business-profile/draft", {
    params: { path: { storeId } },
    body: {
      goal: pick(form.get("goal"), GOALS),
      planningHorizonDays: horizon ? Number(horizon) : undefined,
      riskPreference: pick(form.get("riskPreference"), RISKS),
      taxBasis: form.get("taxBasis") === "INC_TAX" ? "INC_TAX" : "EX_TAX",
      priorities: form.getAll("priorities").map((value) => pick(value, PRIORITIES)).filter((v) => v !== undefined),
      plannedAdSpendMinor,
      plannedAdSpendPeriod: plannedAdSpendMinor === undefined ? undefined : String(form.get("plannedAdSpendPeriod") ?? ""),
      note: String(form.get("note") ?? "").trim() || undefined,
      expectedRevision,
    },
  });
  redirectIfUnauthenticated(saved.response.status, path);
  revalidatePath(path);
  return saved.data ? { status: "saved", message: t.saved } : failure(saved.response.status, saved.error, t);
}

export async function approveProfile(
  storeId: string,
  expectedRevision: number,
  _previous: ProfileFormState,
): Promise<ProfileFormState> {
  const path = storePath(storeId, "settings");
  const t = (await getMessages()).profile;
  const api = await backend(path);
  const approved = await api.POST("/api/v1/stores/{storeId}/business-profile/draft/approval", {
    params: { path: { storeId } },
    body: { expectedRevision },
  });
  redirectIfUnauthenticated(approved.response.status, path);
  revalidatePath(path);
  return approved.data
    ? { status: "saved", message: t.approvedMessage }
    : failure(approved.response.status, approved.error, t);
}

function failure(
  status: number,
  error: { fieldErrors?: { field: string; code: string }[]; requestId?: string } | undefined,
  t: Awaited<ReturnType<typeof getMessages>>["profile"],
): ProfileFormState {
  if (status === 403) {
    return { status: "error", message: t.noPermission };
  }
  if (status === 409) {
    return { status: "error", message: t.changed };
  }
  if (status === 422) {
    const first = error?.fieldErrors?.[0];
    return {
      status: "error",
      message: first ? `${t.invalid} ${t.fieldMessages[first.code] ?? ""}`.trim() : t.invalid,
      field: first?.field,
    };
  }
  return { status: "error", message: t.failed(error?.requestId ?? "-") };
}
