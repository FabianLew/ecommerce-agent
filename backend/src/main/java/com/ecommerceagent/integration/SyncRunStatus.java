package com.ecommerceagent.integration;

/** Outcome of an import run; {@code INCOMPLETE} means data was saved but the period is not marked complete. */
public enum SyncRunStatus {
    RUNNING,
    SUCCEEDED,
    INCOMPLETE,
    FAILED
}
