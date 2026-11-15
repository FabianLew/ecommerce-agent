-- B-40: the per-store mutation stop (ADR-0020 point 7). On the pilot branch: renumber on merge.

-- One row per store once someone stopped or resumed; no row means mutations may run. Every change bumps the
-- version, which the API uses as its ETag; the history is in audit_event.
CREATE TABLE store_mutation_stop (
    store_id         uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL,
    stopped          boolean     NOT NULL,
    reason           text CHECK (reason IS NULL OR length(reason) <= 500),
    version          integer     NOT NULL CHECK (version > 0),
    changed_by       uuid        NOT NULL REFERENCES app_user (id),
    changed_at       timestamptz NOT NULL,
    CHECK (NOT stopped OR reason IS NOT NULL),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);
